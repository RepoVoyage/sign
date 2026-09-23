package com.repovoyage.sign.usb

import android.os.SystemClock
import com.repovoyage.sign.video.FramePlane
import org.json.JSONObject
import java.io.IOException
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.concurrent.thread

/**
 * USB 采集桥服务器（API.md §9，仅 training flavor）：仅监听 127.0.0.1，
 * PC 经 `adb forward` 接入。单客户端——accept 线程逐个服务连接，断开后恢复
 * 可连接状态；每个连接一个 BridgeSession，新连接重置采集段账目并清发送池
 * （新旧积压不拼接，API.md §9.4）。
 *
 * 线程模型：
 * - **accept 线程**：跑 reader 循环（FrameCodec 读满 + onMessage），产物经
 *   controlSends 队列交给 pump；
 * - **pump 线程**：唯一 socket 写者（AUTH_RESULT/SESSION_CONFIG/HEARTBEAT/
 *   FRAME/GAP_EVENT/END 全部串行写出），驱动 onTick 心跳/超时与池收尾；
 * - **offer（相机解码回调线程）**：I420 转换 + 入池，不阻塞协议线程。
 *
 * 背压中断（入队超限/最老帧超时/无进展）→ GAP_EVENT(OVERLOAD) + END
 * INCOMPLETE + 断开；用户 stop() → 拒绝新帧、2 秒内排空既有帧、END COMPLETE
 * （池中断则 INCOMPLETE）。token 不写日志（本类不打日志）。
 */
class UsbBridgeServer(
    private val token: String,
    private val sessionConfig: JSONObject,
    private val pool: FrameSendPool,
    private val port: Int = 9999,
    private val monoMs: () -> Long = SystemClock::elapsedRealtime,
) {
    val sessionId: String = UUID.randomUUID().toString()

    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null

    @Volatile private var stopped = false          // 用户/服务停止（服务级，终态）
    @Volatile private var segmentBroken = false    // 采集段已中断（背压/连续性失效）
    @Volatile private var segmentBreakReason: String? = null
    @Volatile private var readerDone = false       // reader 已退出（断连/违规/停止）

    /** reader/offer 线程 → pump 的控制消息（含 GAP_EVENT） */
    private val controlSends = ConcurrentLinkedQueue<JSONObject>()

    private val indexLock = Any()
    private var nextFrameIndex = 0L

    // 采集段账目（pump 单写者；GAP_EVENT 组装跨线程读，@Volatile 保证可见性）
    @Volatile private var lastWrittenFrameIndex = -1L
    @Volatile private var validStartPtsUs = -1L
    @Volatile private var validEndPtsUs = -1L

    val boundPort: Int
        get() = serverSocket?.localPort ?: -1

    fun start() {
        check(serverSocket == null) { "already started" }
        val socket = ServerSocket(port, 1, InetAddress.getByName("127.0.0.1"))
        serverSocket = socket
        acceptThread = thread(name = "usb-bridge-accept", isDaemon = true) {
            while (!stopped) {
                val client = try {
                    socket.accept()
                } catch (_: IOException) {
                    break   // serverSocket 被关（停止）
                }
                serveClient(client)
            }
        }
    }

    /**
     * 解码帧进入发送阶段（相机解码回调线程调用，I420 转换在该线程执行，
     * 不阻塞协议线程）。
     * @return true = 已入池；false = 采集段已中断/收尾，帧被拒收（不静默丢帧）
     */
    fun offer(
        planes: List<FramePlane>,
        width: Int,
        height: Int,
        cropLeft: Int,
        cropTop: Int,
        cropRight: Int,
        cropBottom: Int,
        ptsUs: Long,
        streamGeneration: Long,
    ): Boolean {
        val bytes = I420.compact(planes, cropLeft, cropTop, cropRight, cropBottom)
        val frameIndex = synchronized(indexLock) { nextFrameIndex++ }
        val frame = FrameSendPool.Frame(bytes, frameIndex, ptsUs, width, height, streamGeneration)
        return when (pool.offer(frame, enteredAt = monoMs())) {
            null -> true
            FrameSendPool.BreakReason.SHUTDOWN,
            FrameSendPool.BreakReason.ALREADY_BROKEN,
            -> false
            else -> {
                // CAPACITY / OLDEST_FRAME_TIMEOUT / NO_PROGRESS：中断采集段（§9.4 过载）
                breakSegment("OVERLOAD", gapEvent("OVERLOAD", startPtsUs = ptsUs, endPtsUs = ptsUs))
                false
            }
        }
    }

    /**
     * 解码链连续性失效上报（DecodedFrameSink.onGap/onOverload 语义）：
     * 中断当前采集段——GAP_EVENT + END INCOMPLETE + 断开，PC 重连续段
     * （前后帧不得拼连续样本，API.md §9.3 GAP_EVENT）。幂等。
     */
    fun reportGap(reason: String) {
        breakSegment(reason, gapEvent(reason, startPtsUs = validEndPtsUs, endPtsUs = validEndPtsUs))
    }

    /** 中断当前采集段（记原因 + 入队 GAP_EVENT）；幂等，停止后不再中断 */
    private fun breakSegment(reason: String, gap: JSONObject) {
        if (segmentBroken || stopped) return
        segmentBroken = true
        segmentBreakReason = reason
        controlSends.add(gap)
    }

    /** 用户停止：拒绝新帧、2 秒内收尾既有帧、发 END、关连接（阻塞至收尾完成） */
    fun stop() {
        if (stopped) return
        stopped = true
        serverSocket?.close()          // 解除 accept 阻塞
        acceptThread?.join(6_000)      // serveClient 内部等 pump 排空 + END（≤2s + 余量）
    }

    // ---- accept/reader 线程 ----

    private fun serveClient(client: Socket) {
        resetSegment()
        val sess = BridgeSession(token, sessionId, sessionConfig, startedAtMonoMs = monoMs())
        try {
            client.tcpNoDelay = true
            client.soTimeout = READER_WAKEUP_MS
        } catch (_: IOException) {
            runCatching { client.close() }
            return
        }
        val codec = FrameCodec(client.getInputStream(), monoMs)
        val pump = thread(name = "usb-bridge-pump", isDaemon = true) { pumpLoop(client, sess) }
        try {
            while (!stopped) {
                val msg = try {
                    codec.readMessage()
                } catch (_: SocketTimeoutException) {
                    continue    // 空闲唤醒：回到停止/循环条件判定
                }
                if (msg == null) break     // EOF/协议违规/超读截止 → 断开
                val outcome = sess.onMessage(msg.header, monoMs())
                outcome.sends.forEach { controlSends.add(it) }
                if (outcome.close) break
            }
        } catch (_: IOException) {
            // pump 已关闭 socket 等情形
        }
        readerDone = true
        pump.join(6_000)
        runCatching { client.close() }
    }

    /** 新连接 = 新采集段：重置账目并清池（新旧积压不拼接） */
    private fun resetSegment() {
        synchronized(indexLock) {
            nextFrameIndex = 0
            lastWrittenFrameIndex = -1
            validStartPtsUs = -1
            validEndPtsUs = -1
            segmentBroken = false
            segmentBreakReason = null
        }
        readerDone = false
        controlSends.clear()
        pool.clear()
    }

    // ---- pump 线程（唯一 socket 写者） ----

    private fun pumpLoop(client: Socket, sess: BridgeSession) {
        val out = client.getOutputStream()
        try {
            var shutdownBegun = false
            while (true) {
                if (stopped && !shutdownBegun) {
                    pool.beginShutdown(monoMs())
                    shutdownBegun = true
                }
                // 1. 控制消息全部写出（reader/offer 产出，GAP_EVENT 先于 END）
                var wrote = false
                while (true) {
                    val c = controlSends.poll() ?: break
                    write(out, c)
                    wrote = true
                }
                // 2. 会话 tick：心跳 / 认证窗口 / 空闲超时
                val tick = sess.onTick(monoMs())
                tick.sends.forEach { write(out, it); wrote = true }
                if (tick.close) {
                    // END 是采集段收尾消息：握手期关闭（BAD_TOKEN/认证超时/违规）无段可报，不发
                    if (sess.phase == BridgeSession.Phase.STREAMING || lastWrittenFrameIndex >= 0) {
                        finish(out, incomplete = true, reason = "IDLE_TIMEOUT")
                    }
                    break
                }
                // 3. 采集段中断（GAP_EVENT 已在步骤 1 写出）
                if (segmentBroken) {
                    if (controlSends.isNotEmpty()) {   // offer 刚入队的 GAP：下轮先写它
                        Thread.sleep(10)
                        continue
                    }
                    finish(out, incomplete = true, reason = segmentBreakReason ?: "OVERLOAD")
                    break
                }
                // 4. 帧写出（串行写通道）
                if (sess.phase == BridgeSession.Phase.STREAMING && !readerDone) {
                    val f = pool.poll()
                    if (f != null) {
                        writeFrame(out, f)
                        wrote = true
                    } else if (stopped) {
                        // 用户停止且积压已排空 → 正常收尾
                        finish(out, incomplete = pool.isBroken, reason = pool.breakReason?.name)
                        break
                    } else if (pool.checkShutdownDeadline()) {
                        finish(out, incomplete = true, reason = "SHUTDOWN_TIMEOUT")
                        break
                    } else if (!segmentBroken && lastWrittenFrameIndex >= 0 && pool.checkNoProgressTimeout()) {
                        // 帧流开始后连续 1 秒无写入进展（无新帧入队、无帧写出）→ 缺帧
                        // 不能静默拼接 → 中断采集段。段尚未开始出帧（PC 已连、相机未出帧）
                        // 不算停滞；write 阻塞由 offer 线程的最老帧超时兜底。
                        breakSegment("OVERLOAD", gapEvent("OVERLOAD", startPtsUs = validEndPtsUs, endPtsUs = validEndPtsUs))
                        continue
                    }
                }
                // 5. reader 已退出且控制消息写尽 → 断连收尾
                if (readerDone && controlSends.isEmpty()) {
                    if (sess.phase == BridgeSession.Phase.STREAMING || lastWrittenFrameIndex >= 0) {
                        finish(out, incomplete = true, reason = "DISCONNECT")
                    }
                    break
                }
                if (!wrote) Thread.sleep(10)
            }
        } catch (_: IOException) {
            // 写失败 = 连接已断：尽力收尾到此为止（PC 侧未收到 END 按不完整处理）
        } finally {
            runCatching { client.close() }   // 同时解除 reader 的读阻塞
        }
    }

    // ---- 消息组装与写出（仅 pump 线程调用） ----

    private fun write(out: OutputStream, json: JSONObject) {
        out.write(FrameCodec.encode(json))
        out.flush()
    }

    private fun writeFrame(out: OutputStream, f: FrameSendPool.Frame) {
        val header = JSONObject()
            .put("type", "FRAME")
            .put("proto", 1)
            .put("sessionId", sessionId)
            .put("streamGeneration", f.streamGeneration)
            .put("frameIndex", f.frameIndex)
            .put("ptsUs", f.ptsUs)
            .put("ptsUnit", "us")
            .put("width", f.width)
            .put("height", f.height)
            .put("pixelFormat", "I420")
            .put("preprocessVersion", sessionConfig.optString("preprocessVersion"))
            .put("payloadLen", f.bytes.size)
        out.write(FrameCodec.encode(header, f.bytes))
        out.flush()
        lastWrittenFrameIndex = f.frameIndex
        if (validStartPtsUs < 0) validStartPtsUs = f.ptsUs
        validEndPtsUs = f.ptsUs
    }

    private fun finish(out: OutputStream, incomplete: Boolean, reason: String?) {
        val end = JSONObject()
            .put("type", "END")
            .put("proto", 1)
            .put("sessionId", sessionId)
            .put("status", if (incomplete) "INCOMPLETE" else "COMPLETE")
        if (reason != null) end.put("reason", reason)
        end.put("lastFrameIndex", lastWrittenFrameIndex)
        end.put("validStartPtsUs", validStartPtsUs)
        end.put("validEndPtsUs", validEndPtsUs)
        write(out, end)
    }

    private fun gapEvent(reason: String, startPtsUs: Long, endPtsUs: Long) = JSONObject()
        .put("type", "GAP_EVENT")
        .put("proto", 1)
        .put("sessionId", sessionId)
        .put("reason", reason)
        .put("lastFrameIndex", lastWrittenFrameIndex)
        .put("startPtsUs", startPtsUs)
        .put("endPtsUs", endPtsUs)

    private companion object {
        const val READER_WAKEUP_MS = 500
    }
}
