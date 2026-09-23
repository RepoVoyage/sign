package com.repovoyage.sign.camera

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.SystemClock
import android.util.Log
import com.arashivision.inskmp.insble.data.BleDeviceCore
import com.arashivision.sdk.camera.api.CameraDevice
import com.arashivision.sdk.camera.api.param.listener.BatteryListener
import com.arashivision.sdk.camera.api.param.listener.DisconnectListener
import com.arashivision.sdk.camera.api.preview.CameraStreamListener
import com.arashivision.sdk.camera.api.preview.PreviewStreamFrame
import com.arashivision.sdk.camera.api.preview.PreviewStreamParamsUpdate
import com.arashivision.sdk.camera.api.preview.PreviewStreamType
import com.arashivision.sdk.camera.core.model.ConnectType
import com.arashivision.sdk.camera.core.model.option.BatteryData
import com.arashivision.sdk.camera.core.model.option.VideoEncode
import com.arashivision.sdk.camera.core.model.option.WiFiData
import com.repovoyage.sign.video.ChunkIngestQueue
import com.repovoyage.sign.video.DecodeFrameQueue
import com.repovoyage.sign.video.DecodeStats
import com.repovoyage.sign.video.DecodeSyncGate
import com.repovoyage.sign.video.DecodedFrame
import com.repovoyage.sign.video.EncodedFrame
import com.repovoyage.sign.video.FrameAssembler
import com.repovoyage.sign.video.H264DecodePrep
import com.repovoyage.sign.video.StreamChunk
import com.repovoyage.sign.video.StreamStats
import com.repovoyage.sign.video.SurfacelessH264Decoder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * CameraSession 的 Insta360 SDK 实现（P2 连接链路 + P3 取流：连接后立即开流，
 * 分片经入口队列聚合，参数上报后进入 Streaming；解码在后续接入）。
 * Authorizing 语义映射为 WIFI connect 全程。
 *
 * 连接链路照 Demo ConnectionViewModel 抄录：
 * BLE connect(isBleOnly=false) → ensureApMode → getWifiData → connectSystemWifi
 * → bindProcessToNetwork → release BLE → WIFI connect(networkHandle)。
 *
 * 线程模型：所有状态机变更与 SDK 调用收敛在 scope 的单线程上下文（Main）；
 * DisconnectListener / BatteryListener 回调先 hop 回该上下文再处理；
 * onStreamDataNotify 在回调线程只做过滤/复制/入队，消费在 IO 协程。
 * 日志红线：不得输出热点 SSID/密码与 networkHandle。
 */
class SdkCameraSession(
    context: Context,
    private val scope: CoroutineScope,
) : CameraSession {

    private val machine = SessionStateMachine()

    private val _state = MutableStateFlow<SessionState>(SessionState.Idle)
    override val state: StateFlow<SessionState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<SessionEvent>(extraBufferCapacity = 32)
    override val events: SharedFlow<SessionEvent> = _events.asSharedFlow()

    /** P3 验证期取流统计（单写者：分片消费协程） */
    private val _streamStats = MutableStateFlow<StreamStats?>(null)
    val streamStats: StateFlow<StreamStats?> = _streamStats.asStateFlow()

    /** P3 验证期解码统计（单写者：解码消费协程） */
    private val _decodeStats = MutableStateFlow<DecodeStats?>(null)
    val decodeStats: StateFlow<DecodeStats?> = _decodeStats.asStateFlow()

    /** training 诊断（§2.7 缓冲占用/缺帧统计）；生产界面不展示 */
    private val _diagStats = MutableStateFlow(DiagStats(0, 0, 0))
    val diagStats: StateFlow<DiagStats> = _diagStats.asStateFlow()

    private val gapCount = java.util.concurrent.atomic.AtomicLong(0)

    private fun reportGap(event: FrameGapEvent) {
        gapCount.incrementAndGet()
        decodedFrameSink?.onGap(event)
    }

    /** 解码输出消费方（flavor 注入，API.md §2.2）；null = 仅统计（P3 验证期形态） */
    @Volatile
    var decodedFrameSink: DecodedFrameSink? = null

    /**
     * 编码帧分流（P6 固定窗口切片识别，解码前）：收 prep 之后的 H.264 帧
     * （含随机访问点标记）。回调必须非阻塞（切片器入口是有界 Channel）；
     * null = 不分流。异常不影响取流主链路。
     */
    @Volatile
    var encodedFrameTap: ((EncodedFrame) -> Unit)? = null

    /** 主动请求随机访问帧（切片段起点/重同步用；GOP 实测超长，不能干等下一个 IDR） */
    fun requestKeyFrame() {
        scope.launch { runCatching { currentDevice?.preview?.requestStreamIframe() } }
    }

    /** 相机静态信息（§2.8.3 采集元数据；连接成功时刷新，取 SDK 缓存不发起 RPC） */
    @Volatile
    var cameraModel: String? = null
        private set

    @Volatile
    var cameraFirmware: String? = null
        private set

    private val appContext = context.applicationContext
    private val connectivityManager =
        appContext.getSystemService(ConnectivityManager::class.java)
    private val wifiManager = appContext.getSystemService(WifiManager::class.java)

    private var currentDevice: CameraDevice? = null
    private var listenersDevice: CameraDevice? = null
    private var connectionAttemptJob: Job? = null
    private var attemptWatchdogJob: Job? = null
    private var healthWatchJob: Job? = null
    private var systemWifiNetworkCallback: ConnectivityManager.NetworkCallback? = null

    /**
     * 进行中的取流（一次 startStream 一个实例）：入口队列 + 聚合器 + 解码准备链 +
     * 消费/解码两条协程。每次开流 generation 递增；旧实例的数据回调与分片因代次/
     * 实例不匹配被丢弃。
     */
    private class ActiveStream(
        val camera: CameraDevice,
        val generation: Long,
        val queue: ChunkIngestQueue,
        val assembler: FrameAssembler,
        val prep: H264DecodePrep,
        val frameQueue: DecodeFrameQueue,
        val gate: DecodeSyncGate,
        val job: Job,
        val decodeJob: Job,
    ) {
        /** 声明尺寸（getPreviewParams 实测回填），供解码器 configure 提示值 */
        @Volatile var declaredWidth: Int = 0
        @Volatile var declaredHeight: Int = 0
    }

    @Volatile
    private var activeStream: ActiveStream? = null
    private var streamGenerationCounter = 0L

    /** stop()/release() 触发的 SDK 断连回调不算被动断连（Demo 同名开关） */
    @Volatile
    private var suppressDisconnectCallback = false

    /** 同一次物理断连可能同时触发 listener 与健康监测，2s 内去重防止重试名额被双计 */
    private var lastDisconnectHandledAtMs = 0L

    /** 当前连接尝试的起点（分段耗时诊断，launchAttempt 重置） */
    private var attemptStageStartedAtMs = 0L

    private var lastBleDevice: BleDeviceCore? = null
    private var lastWifi: WiFiData? = null

    /**
     * 初连失败的瞬时性重试计数（真机验证：相机深度休眠时唤醒握手失败、
     * 停止后快速重连 GATT 133 均为瞬时错误，直接报 Error 无法满足连续
     * 连接成功率）。重试走完整 BLE 链路，成功后清零。
     */
    private var initialAttemptFailures = 0

    // ---------------------------------------------------------------- 回调

    private val disconnectListener = object : DisconnectListener {
        override fun onDisconnect(throwable: Throwable?) {
            if (suppressDisconnectCallback) {
                suppressDisconnectCallback = false
                return
            }
            val cause = if (throwable == null) DisconnectCause.WIFI_LINK_LOST
            else DisconnectCause.SDK_ERROR
            scope.launch { handleDisconnected(cause) }
        }
    }

    private val batteryListener = object : BatteryListener {
        override fun onBatteryLevelChange(batteryData: BatteryData) {
            // 常规电量变化不广播，仅 UI 主动查询时关心
        }

        override fun onLowBatteryWarning() {
            scope.launch {
                val device = currentDevice ?: return@launch
                device.system.getBatteryData().onSuccess {
                    _events.emit(SessionEvent.BatteryLow(it.level))
                }
            }
        }
    }

    // ---------------------------------------------------------------- 取流

    private val streamListener = object : CameraStreamListener {
        override fun onOpening() {
        }

        override fun onOpened() {
            scope.launch {
                // 立即请求关键帧，加快出首帧（官方建议）
                runCatching { currentDevice?.preview?.requestStreamIframe() }
                pollStreamParams()
            }
        }

        override fun onIdle() {
            Log.w(TAG, "streamListener.onIdle")
        }

        override fun onParamsChanged(paramsUpdate: PreviewStreamParamsUpdate) {
            Log.i(TAG, "onParamsChanged w=${paramsUpdate.previewWidth} h=${paramsUpdate.previewHeight} fps=${paramsUpdate.previewFps}")
            if (paramsUpdate.previewWidth > 0 &&
                paramsUpdate.previewHeight > 0 &&
                paramsUpdate.previewFps > 0
            ) {
                scope.launch { onStreamParams(paramsUpdate) }
            }
        }

        override fun onStreamDataNotify(frame: PreviewStreamFrame) {
            // 回调线程不定：只过滤、有界复制、入队，不做解码/转换/阻塞（§2.2.1-1）
            if (frame.type != PreviewStreamType.VIDEO) return
            val stream = activeStream ?: return
            // SDK 未承诺回调后数组不复用，复制取得所有权（§2.2.1-2）
            val chunk = StreamChunk(
                frame.data.copyOf(),
                frame.timestamp,
                frame.type,
                SystemClock.elapsedRealtime(),
                stream.generation,
            )
            stream.queue.offer(chunk)
        }
    }

    /** 连接完成后立即开流：相机在已连接无流空闲态约 1 分钟自动休眠（plan.md 真机发现） */
    private fun startStreaming(camera: CameraDevice) {
        val generation = ++streamGenerationCounter
        val queue = ChunkIngestQueue(
            onOverload = {
                emitEvent(SessionEvent.StreamOverload(OverloadLocation.ENCODE_ENTRY))
                decodedFrameSink?.onOverload(OverloadLocation.ENCODE_ENTRY)
            },
        )
        val assembler = FrameAssembler()
        val prep = H264DecodePrep()
        val frameQueue = DecodeFrameQueue(
            onOverload = {
                emitEvent(SessionEvent.StreamOverload(OverloadLocation.DECODER))
                decodedFrameSink?.onOverload(OverloadLocation.DECODER)
            },
        )
        val gate = DecodeSyncGate()
        val job = scope.launch(Dispatchers.IO) { consumeChunks(camera, queue, assembler, prep, frameQueue) }
        val decodeJob = scope.launch(Dispatchers.IO) { decodeLoop(camera, frameQueue, gate, prep) }
        activeStream = ActiveStream(camera, generation, queue, assembler, prep, frameQueue, gate, job, decodeJob)
        runCatching {
            camera.preview.init(appContext as android.app.Application)
            camera.preview.registerCameraStreamListener(streamListener)
            camera.preview.startStream()
        }.onFailure {
            Log.w(TAG, "startStream failed: ${it.message}")
            stopStreaming()
            failAttempt("startStream failed: ${it.message}")
        }
    }

    /** 分片消费协程：聚合提交帧 → 解码准备链（SPS/PPS/IDR 验证）→ 待解码队列 + 过载恢复 */
    private suspend fun CoroutineScope.consumeChunks(
        camera: CameraDevice,
        queue: ChunkIngestQueue,
        assembler: FrameAssembler,
        prep: H264DecodePrep,
        frameQueue: DecodeFrameQueue,
    ) {
        while (isActive) {
            val chunk = queue.receive()
            if (queue.isOverloaded) {
                queue.clear()
                // 重建同步状态：丢弃聚合中的半帧，等待新 timestamp
                assembler.finish()
                scope.launch { runCatching { camera.preview.requestStreamIframe() } }
                continue
            }
            // 纯参数集/SEI 帧被吸收（null），仅 VCL 帧产出
            val frames = assembler.offer(chunk).mapNotNull { prep.process(it) }
            if (frames.isNotEmpty()) {
                val st = _streamStats.value
                _streamStats.value = StreamStats(
                    generation = chunk.streamGeneration,
                    framesCommitted = (st?.framesCommitted ?: 0) + frames.size,
                    bytesCommitted = (st?.bytesCommitted ?: 0) + frames.sumOf { it.data.size },
                    lastPtsUs = frames.last().ptsUs,
                    syncFrames = (st?.syncFrames ?: 0) + frames.count { it.isSyncPoint },
                )
                encodedFrameTap?.let { tap -> frames.forEach { runCatching { tap(it) } } }
                frames.forEach { frameQueue.offer(it) }
                _diagStats.value = _diagStats.value.copy(ingestDepth = queue.depth, gaps = gapCount.get())
            }
        }
    }

    /**
     * 解码消费协程：门控（从本代次首个随机访问帧起投喂）→ MediaCodec 无 Surface 解码。
     * 待解码队列过载或解码器异常 → 清空解码链重新同步（冲刷/重建 + 重新门控 +
     * 请求关键帧——GOP 实测超长，不能干等下一个 IDR）。
     */
    private suspend fun CoroutineScope.decodeLoop(
        camera: CameraDevice,
        frameQueue: DecodeFrameQueue,
        gate: DecodeSyncGate,
        prep: H264DecodePrep,
    ) {
        var decoder: SurfacelessH264Decoder? = null
        var gatedGeneration = -1L
        val resync: () -> Unit = {
            frameQueue.clear()
            decoder?.flush()
            gate.reset()
            reportGap(FrameGapEvent(GapReason.DECODE_RESET, gatedGeneration))
            scope.launch { runCatching { camera.preview.requestStreamIframe() } }
        }
        val rebuild: () -> Unit = {
            frameQueue.clear()
            decoder?.stop()
            decoder = null
            gate.reset()
            reportGap(FrameGapEvent(GapReason.DECODE_RESET, gatedGeneration))
            scope.launch { runCatching { camera.preview.requestStreamIframe() } }
        }
        try {
            while (isActive) {
                val frame = frameQueue.receive()
                _diagStats.value = _diagStats.value.copy(decodeDepth = frameQueue.depth, gaps = gapCount.get())
                if (frameQueue.isOverloaded) {
                    resync()
                    continue
                }
                // 换代 = 断流重连：旧代残留帧与新一代之间必有缺口
                if (frame.streamGeneration != gatedGeneration) {
                    if (gatedGeneration >= 0) {
                        reportGap(FrameGapEvent(GapReason.RECONNECT, frame.streamGeneration))
                    }
                    gatedGeneration = frame.streamGeneration
                }
                if (!gate.shouldFeed(frame)) continue
                if (decoder == null) {
                    val csd = prep.csd()
                    val w = activeStream?.declaredWidth ?: 0
                    if (csd == null || w == 0) {
                        // 参数集或声明尺寸未就绪：请求关键帧重发（含 CSD），不投喂中间帧
                        scope.launch { runCatching { camera.preview.requestStreamIframe() } }
                        continue
                    }
                    decoder = SurfacelessH264Decoder(onFrame = ::onDecodedFrame)
                        .also { it.start(w, activeStream!!.declaredHeight, csd) }
                }
                try {
                    if (!decoder!!.feed(frame)) {
                        // input buffer 持续拿不到 = 解码端积压：重建解码链
                        rebuild()
                    } else {
                        decoder!!.drain()
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "decoder failure: ${e.message}")
                    rebuild()
                }
            }
        } catch (e: ClosedReceiveChannelException) {
            // 流停止：正常退出
        } finally {
            decoder?.stop()
        }
    }

    /** 解码输出：记统计 + 送 flavor 注入的 sink（API.md §2.2；P4 起接入） */
    private fun onDecodedFrame(frame: DecodedFrame) {
        val st = _decodeStats.value
        _decodeStats.value = DecodeStats(
            generation = frame.streamGeneration,
            framesDecoded = (st?.takeIf { it.generation == frame.streamGeneration }?.framesDecoded ?: 0) + 1,
            width = frame.width,
            height = frame.height,
        )
        decodedFrameSink?.onFrame(frame)
    }

    /**
     * GO 3S 实测不触发 onParamsChanged（真机验证 00:56–00:57 全程零回调），
     * 参数在 onOpened 后主动查询 getPreviewParams 取得。
     */
    private suspend fun pollStreamParams() {
        val camera = currentDevice ?: return
        repeat(STREAM_PARAMS_POLL_TIMES) {
            if (machine.state is SessionState.Streaming) return
            // getPreviewParams 返回 Result<PreviewParams>（非挂起；已销毁时为 failure）
            val params = runCatching { camera.preview.getPreviewParams().getOrNull() }.getOrNull()
            val first = params?.firstStream
            if (first != null && first.width > 0 && first.height > 0 && first.fps > 0) {
                enterStreaming(first.width, first.height, first.fps)
                return
            }
            delay(STREAM_PARAMS_POLL_INTERVAL_MS)
        }
        Log.w(TAG, "stream params unavailable after poll; stay in Preparing")
    }

    /** 首个有效参数（查询或上报）→ Streaming；编码类型运行时查询，失败不得默认（§2.2.2） */
    private suspend fun onStreamParams(update: PreviewStreamParamsUpdate) {
        enterStreaming(update.previewWidth, update.previewHeight, update.previewFps)
    }

    private suspend fun enterStreaming(width: Int, height: Int, fps: Int) {
        if (machine.state is SessionState.Streaming) return
        val camera = currentDevice ?: return
        val encode = fetchEncodeTypeWithRetry(camera)
        if (encode == null) {
            // 查询失败不能默认为 H.264（§2.2.2）：留在 Preparing，等下一次参数回调重试
            Log.w(TAG, "fetchVideoEncodeType failed; waiting for next onParamsChanged")
            return
        }
        val stream = activeStream ?: return
        stream.declaredWidth = width
        stream.declaredHeight = height
        Log.i(TAG, "stream params ready: ${width}x${height}@${fps} $encode gen=${stream.generation}")
        goto(
            SessionState.Streaming(StreamParams(width, height, fps, encode, stream.generation)),
        )
    }

    private suspend fun fetchEncodeTypeWithRetry(camera: CameraDevice): VideoEncodeType? {
        repeat(ENCODE_QUERY_RETRIES) {
            when (camera.system.fetchVideoEncodeType().getOrNull()) {
                VideoEncode.ENCODE_H264 -> return VideoEncodeType.H264
                VideoEncode.ENCODE_H265 -> return VideoEncodeType.H265
                else -> delay(ENCODE_QUERY_RETRY_INTERVAL_MS)
            }
        }
        return null
    }

    /** 停流清理：取消消费/解码协程 → 关闭队列 → 注销监听 → stopStream（须在 release 前） */
    private fun stopStreaming() {
        val stream = activeStream ?: return
        activeStream = null
        stream.job.cancel()
        stream.decodeJob.cancel()
        stream.queue.close()
        stream.frameQueue.close()
        runCatching { stream.camera.preview.unregisterCameraStreamListener(streamListener) }
        runCatching { stream.camera.preview.stopStream() }
    }

    // ---------------------------------------------------------------- API

    override suspend fun start(bleDevice: BleDeviceCore) {
        if (!machine.start()) return
        publish()
        lastBleDevice = bleDevice
        lastWifi = null
        initialAttemptFailures = 0
        launchAttempt { connectViaBle(bleDevice) }
    }

    override suspend fun stop() {
        machine.onUserStop()
        publish()
        suppressDisconnectCallback = true
        stopStreaming()
        connectionAttemptJob?.cancel()
        connectionAttemptJob = null
        cancelAttemptWatchdog()
        healthWatchJob?.cancel()
        healthWatchJob = null
        unregisterSystemListeners()
        val device = currentDevice
        currentDevice?.unregisterDisconnectListener(disconnectListener)
        currentDevice = null
        // release() 内含断连语义，须在解绑进程网络前完成，否则相机占着会话导致快速重连冲突
        runCatching { device?.release() }
        unbindNetwork()
        machine.transitionTo(SessionState.Idle)
        publish()
    }

    override suspend fun resumeAfterCooldown() {
        if (!machine.resumeAfterCooldown()) return
        publish()
        lastBleDevice?.let { launchAttempt { connectViaBle(it) } }
    }

    // ------------------------------------------------------- 连接链路（初连）

    /** BLE 引导 → 系统热点 → WIFI connect */
    private suspend fun connectViaBle(bleDevice: BleDeviceCore) {
        goto(SessionState.BleConnecting(bleDevice.name))
        val bleCamera = CameraDevice.get(ConnectType.BLE)
        setCurrentDevice(bleCamera)
        bleCamera
            // BLE 不是最终目标，isBleOnly = false
            .connect(bleDevice, false)
            .onSuccess { launchOnAttempt { connectViaWifi(bleCamera) } }
            .onFailure { failAttempt(it.message) }
    }

    private suspend fun connectViaWifi(bleCamera: CameraDevice) {
        goto(SessionState.WifiConnecting)
        ensureApMode(bleCamera)?.let {
            failAttempt(it)
            return
        }
        val wifi = bleCamera.system.getWifiData().getOrElse {
            failAttempt(it.message ?: "getWifiData failed")
            return
        }
        lastWifi = wifi
        val network = connectSystemWifiWithRetry(wifi.ssid, wifi.pwd)
        if (network == null) {
            failAttempt("system wifi unavailable")
            return
        }
        if (!bindNetwork(network)) {
            failAttempt("bindProcessToNetwork failed")
            return
        }
        // BLE 与 WIFI 是独立通道，拿到 Network 即可释放 BLE 控制通道
        runCatching { bleCamera.release() }
        val wifiCamera = CameraDevice.get(ConnectType.WIFI)
        setCurrentDevice(wifiCamera)
        authorizeAndConnect(wifiCamera, network.networkHandle, isRetry = false)
    }

    /** WIFI connect + 连接后置；重连时与初连共用（网络/授权/准备重新执行） */
    private suspend fun authorizeAndConnect(
        camera: CameraDevice,
        networkHandle: Long,
        isRetry: Boolean,
    ) {
        goto(SessionState.Authorizing(AuthStatus.WAITING))
        camera.connect(networkHandle)
            .onSuccess { launchOnAttempt { onConnected(camera) } }
            .onFailure {
                if (isRetry) onReconnectAttemptFailed(it.message)
                else failAttempt(it.message)
            }
    }

    private suspend fun onConnected(camera: CameraDevice) {
        goto(SessionState.Preparing)
        initialAttemptFailures = 0
        cameraModel = camera.system.getCameraType().getOrNull()?.displayName
        cameraFirmware = camera.system.getFirmwareRevision().getOrNull()
        registerSystemListeners(camera)
        startHealthWatch(camera)
        // 连接完成后立即开流（相机无流空闲会休眠）；onParamsChanged → Streaming(params)
        startStreaming(camera)
    }

    // ------------------------------------------------------------- 重连

    /** 非主动断连：立即通知 → Reconnecting(退避) → 到点重走网络/授权/准备（无 BLE 重扫） */
    private fun handleDisconnected(cause: DisconnectCause) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastDisconnectHandledAtMs < DISCONNECT_DEDUPE_MS) return
        lastDisconnectHandledAtMs = now

        stopHealthWatch()
        // 取消整条连接尝试链路，防止迟到的模式切换超时覆盖刚写入的断连状态（Demo 教训）
        cancelAttemptWatchdog()
        connectionAttemptJob?.cancel()
        connectionAttemptJob = null
        emitEvent(SessionEvent.Disconnected(cause))
        // 无论是否重连都要清理：不 release 的话相机会占着会话，快速重连返回冲突错误码；
        // ≥1s 的退避等待同时给了 release 送达的时间窗
        releaseDeviceAndUnbind()
        if (!machine.onDisconnected()) {
            // 连接尝试期（BLE/WIFI 连接中/Checking/Activating）断连：尝试链已取消，
            // 必须显式失败，否则状态永远停在连接中（Stopping/Idle 时转移不合法，自然跳过）
            machine.transitionTo(SessionState.Error(SessionError.UNRECOVERABLE))
            publish()
            return
        }
        publish()
        if (machine.state is SessionState.Error) {
            emitEvent(SessionEvent.ReconnectFailed(ReconnectPolicy.MAX_ATTEMPTS))
            return
        }
        scheduleReconnect()
    }

    private fun scheduleReconnect() {
        val st = machine.state as? SessionState.Reconnecting ?: return
        connectionAttemptJob = scope.launch {
            delay(st.nextRetryInMs)
            // 退避等待期间用户可能已停止会话
            if (machine.state !is SessionState.Reconnecting) return@launch
            armAttemptWatchdog { onReconnectAttemptFailed("attempt timeout") }
            runReconnect()
        }
    }

    /** 重连：凭缓存的相机凭据重走 系统热点 → 授权 → 准备 */
    private suspend fun runReconnect() {
        val wifi = lastWifi
        if (wifi == null) {
            // 无凭据（理论上不会出现）：退回 BLE 全链路
            val ble = lastBleDevice
            if (ble == null) {
                onReconnectAttemptFailed("no cached wifi credentials")
                return
            }
            connectViaBle(ble)
            return
        }
        goto(SessionState.WifiConnecting)
        val network = connectSystemWifiWithRetry(wifi.ssid, wifi.pwd)
        if (network == null) {
            onReconnectAttemptFailed("system wifi unavailable")
            return
        }
        if (!bindNetwork(network)) {
            onReconnectAttemptFailed("bindProcessToNetwork failed")
            return
        }
        val camera = CameraDevice.get(ConnectType.WIFI)
        setCurrentDevice(camera)
        authorizeAndConnect(camera, network.networkHandle, isRetry = true)
    }

    /** 重连尝试失败：消耗一次重试名额，进入下一轮退避或耗尽报错 */
    private fun onReconnectAttemptFailed(message: String?) {
        Log.w(TAG, "reconnect attempt failed: $message")
        stopHealthWatch()
        cancelAttemptWatchdog()
        connectionAttemptJob?.cancel()
        connectionAttemptJob = null
        releaseDeviceAndUnbind()
        if (!machine.onDisconnected()) {
            publish()
            return
        }
        publish()
        if (machine.state is SessionState.Error) {
            emitEvent(SessionEvent.ReconnectFailed(ReconnectPolicy.MAX_ATTEMPTS))
            return
        }
        scheduleReconnect()
    }

    /** 初连失败：瞬时性错误先自动重试（完整链路），耗尽才 Error */
    private fun failAttempt(message: String?) {
        Log.w(TAG, "connect attempt failed: $message")
        cancelAttemptWatchdog()
        connectionAttemptJob?.cancel()
        connectionAttemptJob = null
        stopHealthWatch()
        if (initialAttemptFailures < MAX_INITIAL_RETRIES) {
            initialAttemptFailures += 1
            Log.i(TAG, "initial connect auto-retry $initialAttemptFailures/$MAX_INITIAL_RETRIES")
            connectionAttemptJob = scope.launch {
                delay(INITIAL_RETRY_DELAY_MS)
                attemptStageStartedAtMs = SystemClock.elapsedRealtime()
                // 等待期间用户可能已停止（Stopping/Idle/Error）则放弃重试
                val s = machine.state
                val inConnectLadder = s is SessionState.Checking ||
                    s is SessionState.BleConnecting ||
                    s is SessionState.WifiConnecting ||
                    s is SessionState.Authorizing ||
                    s is SessionState.Activating ||
                    s is SessionState.Preparing
                val ble = lastBleDevice
                if (!inConnectLadder || ble == null) return@launch
                armAttemptWatchdog { failAttempt("attempt timeout") }
                connectViaBle(ble)
            }
            return
        }
        machine.transitionTo(SessionState.Error(SessionError.UNRECOVERABLE))
        publish()
        releaseDeviceAndUnbind()
    }

    // ------------------------------------------------------------ SDK 细节

    /**
     * 确保相机 WiFi 处于 AP 模式（照 Demo 抄录）：切模式后相机重启 WiFi，
     * 轮询确认完成，最多约 5s。已是 AP 返回 null（无错误）。
     */
    private suspend fun ensureApMode(bleCamera: CameraDevice): String? {
        val currentMode = bleCamera.system.fetchWifiData().getOrNull()?.mode
        if (currentMode == WiFiData.Mode.AP) return null
        if (!bleCamera.system.setWifiMode(WiFiData.Mode.AP, "").isSuccess) {
            return "setWifiMode failed"
        }
        return if (awaitApWifiData(bleCamera) != null) null else "AP mode switch timeout"
    }

    private suspend fun awaitApWifiData(bleCamera: CameraDevice): WiFiData? {
        repeat(AP_MODE_POLL_TIMES) {
            val data = bleCamera.system.fetchWifiData().getOrNull()
            if (data?.mode == WiFiData.Mode.AP) return data
            delay(AP_MODE_POLL_INTERVAL_MS)
        }
        return null
    }

    /**
     * 经 WifiNetworkSpecifier 发起进程专属系统热点连接（照 Demo 抄录）。
     * 回调必须持续注册以维持 Network 存活，直到断连/清理才 unregister。
     */
    /**
     * 系统热点连接（同一尝试内局部重试）：上一会话残留的关联拆除、或冷扫描
     * 都可能让单次 requestNetwork 拖满 10s 仍 unavailable。局部重试避免整链路
     * 重跑（BLE 释放→重连会撞 GATT 快速重连瞬断窗口，实测 24s 才连上）。
     * BLE 与 AP 模式此时已就绪，重试只花时间不换通道。
     */
    private suspend fun connectSystemWifiWithRetry(ssid: String, password: String): Network? {
        repeat(WIFI_CONNECT_TRIES) { i ->
            if (i > 0) {
                delay(WIFI_CONNECT_RETRY_GAP_MS)
                Log.w(TAG, "system wifi retry ${i + 1}/$WIFI_CONNECT_TRIES")
            }
            // 系统对不可用网络可能拖 30s+ 才报 onUnavailable，自设上限保证退避节奏可控
            val network = withTimeoutOrNull(SYSTEM_WIFI_TIMEOUT_MS) { connectSystemWifi(ssid, password) }
            if (network != null) return network
        }
        return null
    }

    private suspend fun connectSystemWifi(ssid: String, password: String): Network? {
        if (!wifiManager.isWifiEnabled) return null
        unregisterSystemWifiNetworkCallback()
        return suspendCancellableCoroutine { cont ->
            val specifier = WifiNetworkSpecifier.Builder()
                .setSsid(ssid)
                .setWpa2Passphrase(password)
                .build()
            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .setNetworkSpecifier(specifier)
                .build()
            val callback = object : ConnectivityManager.NetworkCallback() {
                private var finished = false

                private fun finishOnce(network: Network?) {
                    if (finished) return
                    finished = true
                    cont.resume(network)
                }

                override fun onAvailable(network: Network) = finishOnce(network)

                override fun onUnavailable() = finishOnce(null)
            }
            connectivityManager.requestNetwork(request, callback)
            systemWifiNetworkCallback = callback
            // 尝试链路被取消时同步撤销请求，避免陈旧 Network 干扰下一次同 SSID 重连
            cont.invokeOnCancellation {
                runCatching { connectivityManager.unregisterNetworkCallback(callback) }
            }
        }
    }

    private fun unregisterSystemWifiNetworkCallback() {
        val callback = systemWifiNetworkCallback ?: return
        systemWifiNetworkCallback = null
        runCatching { connectivityManager.unregisterNetworkCallback(callback) }
    }

    private fun bindNetwork(network: Network): Boolean =
        ProcessNetworkBinder.bind(connectivityManager, network)

    private fun unbindNetwork() {
        ProcessNetworkBinder.unbind(connectivityManager)
    }

    private fun setCurrentDevice(device: CameraDevice?) {
        if (currentDevice === device) return
        unregisterSystemListeners()
        currentDevice?.unregisterDisconnectListener(disconnectListener)
        currentDevice = device
        // 连接期间（含 SDK 内部同步阶段）即注册，否则窗口期断连被无声丢弃（Demo 教训）
        device?.registerDisconnectListener(disconnectListener)
    }

    private fun registerSystemListeners(device: CameraDevice) {
        unregisterSystemListeners()
        device.system.registerBatteryListener(batteryListener)
        listenersDevice = device
    }

    private fun unregisterSystemListeners() {
        val d = listenersDevice ?: return
        d.system.unregisterBatteryListener(batteryListener)
        listenersDevice = null
    }

    /** isConnected() 轮询兜底：listener 丢失的静默断连（如 MIUI 冻结）也能发现 */
    private fun startHealthWatch(device: CameraDevice) {
        healthWatchJob?.cancel()
        healthWatchJob = scope.launch {
            while (isActive) {
                delay(HEALTH_CHECK_INTERVAL_MS)
                if (currentDevice !== device) return@launch
                val connected = runCatching { device.isConnected() }.getOrDefault(false)
                if (!connected) {
                    scope.launch { handleDisconnected(DisconnectCause.HEALTH_CHECK_TIMEOUT) }
                    return@launch
                }
            }
        }
    }

    private fun stopHealthWatch() {
        healthWatchJob?.cancel()
        healthWatchJob = null
    }

    /**
     * 断连/失败后的清理：release 须在解绑进程网络前送达（否则相机占着会话，
     * 快速重连返回冲突错误码）。异步执行，不阻塞状态广播。
     */
    private fun releaseDeviceAndUnbind() {
        stopHealthWatch()
        cancelAttemptWatchdog()
        connectionAttemptJob?.cancel()
        connectionAttemptJob = null
        suppressDisconnectCallback = true
        stopStreaming()
        unregisterSystemListeners()
        val device = currentDevice
        currentDevice?.unregisterDisconnectListener(disconnectListener)
        currentDevice = null
        scope.launch {
            runCatching { device?.release() }
            unbindNetwork()
            unregisterSystemWifiNetworkCallback()
        }
    }

    // ---------------------------------------------------------------- 框架

    /** 新连接尝试：取消旧链路，root Job 挂在 scope 上，后续步骤以它为父 */
    private fun launchAttempt(block: suspend CoroutineScope.() -> Unit) {
        connectionAttemptJob?.cancel()
        attemptStageStartedAtMs = SystemClock.elapsedRealtime()
        connectionAttemptJob = scope.launch { coroutineScope(block) }
        armAttemptWatchdog { failAttempt("attempt timeout") }
    }

    /**
     * 尝试级 watchdog：SDK 的 BLE connect 在相机半死状态下可能内循环重试
     * 永不返回（真机挂死 108s），无自身超时。60s 上限（正常成功路径 ≤29s），
     * 超时按尝试失败处理，走既有自动重试/退避。
     */
    private fun armAttemptWatchdog(onTimeout: () -> Unit) {
        val attemptJob = connectionAttemptJob ?: return
        attemptWatchdogJob?.cancel()
        attemptWatchdogJob = scope.launch {
            delay(ATTEMPT_TIMEOUT_MS)
            if (attemptJob.isActive) {
                Log.w(TAG, "connect attempt watchdog fired after ${ATTEMPT_TIMEOUT_MS}ms")
                onTimeout()
            }
        }
    }

    private fun cancelAttemptWatchdog() {
        attemptWatchdogJob?.cancel()
        attemptWatchdogJob = null
    }

    /** 在当前尝试链路内续挂步骤（onSuccess 回调非 suspend，需显式挂到 root Job） */
    private fun launchOnAttempt(block: suspend CoroutineScope.() -> Unit) {
        val parent = connectionAttemptJob
        if (parent != null) scope.launch(parent) { block() }
        else scope.launch { block() }
    }

    private fun emitEvent(event: SessionEvent) {
        scope.launch { _events.emit(event) }
    }

    private fun goto(candidate: SessionState): Boolean {
        val ok = machine.transitionTo(candidate)
        // 连接链路分段耗时诊断（P2 灵敏度实测用）：attempt 起点重置
        if (ok) {
            val now = SystemClock.elapsedRealtime()
            if (attemptStageStartedAtMs == 0L) attemptStageStartedAtMs = now
            Log.i(TAG, "stage $candidate +${now - attemptStageStartedAtMs}ms")
        }
        publish()
        return ok
    }

    private fun publish() {
        _state.value = machine.state
    }

    private companion object {
        const val TAG = "SdkCameraSession"
        const val HEALTH_CHECK_INTERVAL_MS = 1_000L
        const val AP_MODE_POLL_TIMES = 10
        const val AP_MODE_POLL_INTERVAL_MS = 500L
        const val DISCONNECT_DEDUPE_MS = 2_000L
        const val MAX_INITIAL_RETRIES = 2
        const val INITIAL_RETRY_DELAY_MS = 2_000L
        const val SYSTEM_WIFI_TIMEOUT_MS = 10_000L
        const val WIFI_CONNECT_TRIES = 3
        const val WIFI_CONNECT_RETRY_GAP_MS = 1_000L
        const val ATTEMPT_TIMEOUT_MS = 60_000L
        const val ENCODE_QUERY_RETRIES = 3
        const val ENCODE_QUERY_RETRY_INTERVAL_MS = 1_000L
        const val STREAM_PARAMS_POLL_TIMES = 10
        const val STREAM_PARAMS_POLL_INTERVAL_MS = 300L
    }
}

/** training 诊断统计（§2.7 缓冲占用/缺帧）：入流/待解码队列深度 + 累计 gap 次数 */
data class DiagStats(val ingestDepth: Int, val decodeDepth: Int, val gaps: Long)
