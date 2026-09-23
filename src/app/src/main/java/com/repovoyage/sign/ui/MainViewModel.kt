package com.repovoyage.sign.ui

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.arashivision.inskmp.insble.data.BleDeviceCore
import com.arashivision.sdk.camera.api.CameraDevice
import com.arashivision.sdk.camera.core.callback.BleScanCallback
import com.arashivision.sdk.camera.core.model.ConnectType
import com.repovoyage.sign.R
import com.repovoyage.sign.SignApp
import com.repovoyage.sign.camera.CameraSession
import com.repovoyage.sign.camera.SdkCameraSession
import com.repovoyage.sign.camera.SessionEvent
import com.repovoyage.sign.camera.SessionState
import com.repovoyage.sign.capture.CaptureEntryImpl
import com.repovoyage.sign.pipeline.SubtitleState
import com.repovoyage.sign.recognition.ModelCatalog
import com.repovoyage.sign.recognition.RecognitionSourceImpl
import com.repovoyage.sign.sentence.LangCode
import com.repovoyage.sign.service.CameraBridgeForegroundService
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 主界面 VM（MVVM，ARCHITECTURE §3.2）：BLE 扫描/连接（P2 面板逻辑迁入）、
 * 会话状态观察、翻译管线控制与字幕状态、training 采集入口与统计。
 * UI 只观察状态、转发意图，不含业务逻辑。
 */
class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val signApp = app as SignApp

    /** 采集入口（flavor 缝：training 可用，production 无入口） */
    val captureEntry get() = CaptureEntryImpl

    /**
     * 识别源可用性（路由后）：模型 B → 识别服务令牌齐备；其余 → flavor 缝
     * （training 桩源=true，production 未接模型=false）
     */
    val recognitionAvailable: StateFlow<Boolean> = combine(
        signApp.settings.selectedModelId,
        signApp.settings.recognitionTokens,
    ) { id, tokens ->
        if (id == ModelCatalog.MODEL_B_ID) tokens.isConfigured else RecognitionSourceImpl.isAvailable
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RecognitionSourceImpl.isAvailable)

    /** 切片识别模式（模型 B）：主界面显示「完成本句」与联调状态行 */
    val clipMode: StateFlow<Boolean> = signApp.settings.selectedModelId
        .map { it == ModelCatalog.MODEL_B_ID }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** 切片识别联调状态（上传/识别/组句进度与失败原因） */
    val clipStatus: StateFlow<String?> = signApp.clipSource.statusText

    init {
        // 词级拒绝（TOO_SHORT 等）→ 重打提示（非待核实、不震动，2026-09-23 定稿）
        viewModelScope.launch {
            signApp.clipSource.needsRepeat.collect { signApp.pipeline.reportNeedsRepeat() }
        }
    }

    val pipelineState: StateFlow<SubtitleState> = signApp.pipeline.state

    // ---------------------------------------------------------------- 会话

    private val _sessionState = MutableStateFlow<SessionState>(SessionState.Idle)
    val sessionState: StateFlow<SessionState> = _sessionState.asStateFlow()

    private val _sessionEvent = MutableStateFlow<String?>(null)
    val sessionEvent: StateFlow<String?> = _sessionEvent.asStateFlow()

    private val _statsText = MutableStateFlow("")
    val statsText: StateFlow<String> = _statsText.asStateFlow()

    private var session: CameraSession? = null
    private val sessionJobs = mutableListOf<Job>()

    // ---------------------------------------------------------------- 扫描

    private val _scanStatus = MutableStateFlow("")
    val scanStatus: StateFlow<String> = _scanStatus.asStateFlow()

    private val _devices = MutableStateFlow<List<BleDeviceCore>>(emptyList())
    val devices: StateFlow<List<BleDeviceCore>> = _devices.asStateFlow()

    fun deviceLabel(device: BleDeviceCore, index: Int): String =
        device.name ?: getApplication<Application>().getString(R.string.unnamed_device, index + 1)

    fun startScan() {
        _devices.value = emptyList()
        _scanStatus.value = getApplication<Application>().getString(R.string.scanning)
        // BLE 扫描是独立于会话的 UI 层职责（CameraSession 只接收已发现的设备）
        CameraDevice.get(ConnectType.BLE).scan(
            SCAN_DURATION_MS,
            object : BleScanCallback {
                override fun onStarted() {}

                override fun onScanning(bleDevice: BleDeviceCore) {
                    viewModelScope.launch {
                        val current = _devices.value.toMutableList()
                        if (current.none { it == bleDevice }) {
                            current.add(bleDevice)
                            _devices.value = current
                            _scanStatus.value = getApplication<Application>()
                                .getString(R.string.found_n_devices, current.size)
                        }
                    }
                }

                override fun onFinished(bleDeviceList: List<BleDeviceCore>) {
                    viewModelScope.launch {
                        _devices.value = bleDeviceList
                        _scanStatus.value = getApplication<Application>()
                            .getString(R.string.scan_finished, bleDeviceList.size)
                    }
                }

                override fun onError(throwable: Throwable) {
                    viewModelScope.launch {
                        _scanStatus.value = getApplication<Application>()
                            .getString(R.string.scan_failed, throwable.message ?: "?")
                    }
                }
            },
        )
    }

    fun connect(device: BleDeviceCore) {
        val app = getApplication<Application>()
        _scanStatus.value = app.getString(R.string.connecting_device, device.name ?: "?")
        CameraBridgeForegroundService.start(app)
        viewModelScope.launch {
            // 等 FGS 创建会话（onCreate 同步执行，一般一轮即可）
            var s = CameraBridgeForegroundService.session
            var tries = 0
            while (s == null && tries++ < 50) {
                delay(100)
                s = CameraBridgeForegroundService.session
            }
            if (s == null) {
                _scanStatus.value = app.getString(R.string.service_not_ready)
                return@launch
            }
            attachSession(s)
            s.start(device)
        }
    }

    fun stopSession() {
        viewModelScope.launch {
            session?.stop()
            CameraBridgeForegroundService.stop(getApplication())
            _sessionState.value = SessionState.Idle
            _sessionEvent.value = null
            _statsText.value = ""
        }
    }

    private fun attachSession(s: CameraSession) {
        if (session === s) return
        sessionJobs.forEach { it.cancel() }
        sessionJobs.clear()
        session = s
        sessionJobs += viewModelScope.launch {
            s.state.collect { state ->
                _sessionState.value = state
                if (state !is SessionState.Streaming) _statsText.value = ""
            }
        }
        sessionJobs += viewModelScope.launch {
            s.events.collect { event -> _sessionEvent.value = eventText(event) }
        }
        // 取流/解码统计 1s 采样（P3 验证期口径，走 SdkCameraSession 具体类型）
        sessionJobs += viewModelScope.launch {
            var lastFrames = -1L
            var lastDecoded = -1L
            var lastAt = 0L
            var lastDecodeAt = 0L
            while (isActive) {
                delay(1_000)
                val sdk = session as? SdkCameraSession
                val st = sdk?.streamStats?.value ?: run {
                    lastFrames = -1
                    continue
                }
                val app = getApplication<Application>()
                var text = ""
                if (lastFrames >= 0 && lastAt > 0) {
                    val fps = (st.framesCommitted - lastFrames) * 1000f / (SystemClock.elapsedRealtime() - lastAt)
                    text = app.getString(
                        R.string.stream_stats_format,
                        st.generation, st.framesCommitted, fps, st.bytesCommitted / 1024f / 1024f, st.syncFrames,
                    )
                }
                lastFrames = st.framesCommitted
                lastAt = SystemClock.elapsedRealtime()
                val ds = sdk.decodeStats?.value
                if (ds != null) {
                    if (lastDecoded >= 0 && lastDecodeAt > 0 && ds.framesDecoded >= lastDecoded) {
                        val dfps = (ds.framesDecoded - lastDecoded) * 1000f / (SystemClock.elapsedRealtime() - lastDecodeAt)
                        text += "\n" + app.getString(
                            R.string.decode_stats_format, ds.generation, ds.framesDecoded, dfps, ds.width, ds.height,
                        )
                    }
                    lastDecoded = ds.framesDecoded
                    lastDecodeAt = SystemClock.elapsedRealtime()
                } else {
                    lastDecoded = -1
                }
                // training 诊断（§2.7 缓冲占用/缺帧统计）
                val dg = sdk.diagStats.value
                if (text.isNotEmpty()) {
                    text += "\n" + app.getString(
                        R.string.diag_stats_format, dg.ingestDepth, dg.decodeDepth, dg.gaps,
                    )
                }
                if (text.isNotEmpty()) _statsText.value = text
            }
        }
    }

    private fun eventText(event: SessionEvent): String = when (event) {
        is SessionEvent.BatteryLow -> "相机低电量 ${event.levelPercent}%"
        is SessionEvent.Overheat -> "相机过热 ${event.temperatureC}°C"
        is SessionEvent.Disconnected -> "连接中断（${event.cause}）"
        is SessionEvent.StreamOverload -> "处理过载（${event.location}）"
        is SessionEvent.ReconnectFailed -> "重连失败（第 ${event.attempt} 次）"
    }

    // ---------------------------------------------------------------- 翻译管线

    fun startTranslation() = signApp.pipeline.start()

    fun stopTranslation() = signApp.pipeline.stop()

    fun discardPending(segmentId: String) = signApp.pipeline.discardPending(segmentId)

    fun replay(segmentId: String, language: LangCode) =
        signApp.pipeline.replay(segmentId, language)

    /** LLM 低置信结果的核对/纠错（§2.7 疑义核对入口） */
    fun submitCorrection(segmentId: String, language: LangCode, text: String) =
        signApp.pipeline.submitCorrection(segmentId, language, text)

    // ------------------------------------------------ 状态行/提示/诊断

    val repeatPromptCount: StateFlow<Int> = signApp.pipeline.repeatPromptCount

    val sourceDescription: StateFlow<String> = combine(
        signApp.settings.selectedModelId,
        signApp.settings.recognitionTokens,
    ) { id, _ ->
        if (id == ModelCatalog.MODEL_B_ID) signApp.clipSource.sourceDescription
        else RecognitionSourceImpl.sourceDescription
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RecognitionSourceImpl.sourceDescription)

    /** 用户「完成本句」：已收集词候选送 Agent 组句（仅切片识别模式有效） */
    fun finishSentence() = signApp.clipSource.finishSentence()

    val selectedModelName: StateFlow<String> = signApp.settings.selectedModelId
        .map { id -> ModelCatalog.ENTRIES.find { it.id == id }?.displayName ?: "未选择模型" }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "未选择模型")

    /** 语音未就绪 ∩ 语音语言（§2.5.1：仅显示字幕并明确标记）；3s 轮询引擎就绪态 */
    val voiceUnreadySpoken: StateFlow<Set<LangCode>> = combine(
        signApp.settings.spokenLanguages,
        signApp.settings.ttsEnabled,
        flow {
            while (true) {
                emit(Unit)
                delay(3_000)
            }
        },
    ) { spoken, enabled, _ ->
        if (!enabled) emptySet()
        else spoken.filterNot { signApp.ttsSpeaker.isLanguageReady(it) }.toSet()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    override fun onCleared() {
        sessionJobs.forEach { it.cancel() }
        // 管线与 TTS 归应用容器（SignApp）持有，跨界面重建存活；此处不停止
    }

    private companion object {
        const val SCAN_DURATION_MS = 10_000L
    }
}
