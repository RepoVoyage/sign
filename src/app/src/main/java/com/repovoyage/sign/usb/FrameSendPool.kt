package com.repovoyage.sign.usb

import android.os.SystemClock
import java.util.ArrayDeque

/**
 * 训练像素发送池（API.md §9.4 / ARCHITECTURE.md §2.8.2）：
 * 预算【初始值 128 MiB】覆盖待发帧及元数据；帧数上限 = ceil(captureFps×2)（2 秒缓冲
 * 目标）；帧进入发送阶段（入队）到完整写出 ≤ 2 秒——最老帧年龄用入队时刻起算，
 * 不因零星写出续期。入队超限（帧数/字节）或最老帧超龄 → 中断当前采集段并报告
 * reason，不静默丢帧、不覆盖旧帧；连续 1 秒写入无进展 → 中断。
 *
 * 用户正常停止：beginShutdown 后拒绝新帧，既有帧在原截止内收尾（不重置各帧截止），
 * 停止后 2 秒仍未排空按 SHUTDOWN_TIMEOUT 中断。clear 结束中断状态并丢弃积压
 * （新旧积压不拼接）。实际写 socket 由接线层 poll→写出驱动。
 */
class FrameSendPool(
    private val maxFrames: Int,
    private val maxBytes: Long,
    private val monoMs: () -> Long = SystemClock::elapsedRealtime,
    private val frameAgeTimeoutMs: Long = 2_000,
    private val noProgressTimeoutMs: Long = 1_000,
) {

    /** 待发帧（不含账目字段，账目由池内部跟踪）；尺寸/代数随帧透传给 FRAME header */
    data class Frame(
        val bytes: ByteArray,
        val frameIndex: Long,
        val ptsUs: Long,
        val width: Int = 0,
        val height: Int = 0,
        val streamGeneration: Long = 0,
    )

    /** 中断/拒绝原因；ALREADY_BROKEN 表池已中断（非新事件） */
    enum class BreakReason {
        CAPACITY,               // 帧数或字节超限
        OLDEST_FRAME_TIMEOUT,   // 最老帧超过 2s 写出期限
        NO_PROGRESS,            // 连续 1s 写入无进展
        SHUTDOWN,               // 停止收尾期拒绝新帧（非中断）
        SHUTDOWN_TIMEOUT,       // 停止后 2s 未排空（按不完整收尾）
        ALREADY_BROKEN,
    }

    private class Queued(val frame: Frame, val enteredAt: Long)

    private val lock = Any()
    private val queue = ArrayDeque<Queued>()
    private var queuedBytes = 0L
    private var broken = false
    private var reason: BreakReason? = null
    private var shutdown = false
    private var shutdownAt = 0L
    /** 最近一次进展时刻（入队/取出均算） */
    private var lastProgressAt = monoMs()

    val isBroken: Boolean
        get() = synchronized(lock) { broken }

    val breakReason: BreakReason?
        get() = synchronized(lock) { reason }

    /** 队列中是否还有待发帧 */
    val hasBacklog: Boolean
        get() = synchronized(lock) { queue.isNotEmpty() }

    /**
     * 帧进入发送阶段；null = 接受，非 null = 拒绝并给出原因（触发采集段中断）。
     * @param enteredAt 该帧进入发送阶段的时刻（接线层拿到解码帧的时刻，mono 时钟）
     */
    fun offer(frame: Frame, enteredAt: Long): BreakReason? = synchronized(lock) {
        when {
            broken -> BreakReason.ALREADY_BROKEN
            shutdown -> BreakReason.SHUTDOWN
            queue.size >= maxFrames || queuedBytes + frame.bytes.size > maxBytes ->
                breakWith(BreakReason.CAPACITY)
            queue.isNotEmpty() && enteredAt - queue.peekFirst().enteredAt >= frameAgeTimeoutMs ->
                breakWith(BreakReason.OLDEST_FRAME_TIMEOUT)
            else -> {
                queue.addLast(Queued(frame, enteredAt))
                queuedBytes += frame.bytes.size
                lastProgressAt = enteredAt
                null
            }
        }
    }

    /** 取出最老帧（视为写出进展）；无帧返回 null */
    fun poll(): Frame? = synchronized(lock) {
        val f = queue.pollFirst() ?: return null
        queuedBytes -= f.frame.bytes.size
        lastProgressAt = monoMs()
        f.frame
    }

    /** 周期检查：距上次进展连续 1s → 中断 */
    fun checkNoProgressTimeout(): Boolean = synchronized(lock) {
        if (broken) return true
        if (monoMs() - lastProgressAt >= noProgressTimeoutMs) {
            breakWith(BreakReason.NO_PROGRESS)
            return true
        }
        false
    }

    /** 用户停止：拒绝新帧，既有帧按原截止收尾 */
    fun beginShutdown(now: Long) = synchronized(lock) {
        shutdown = true
        shutdownAt = now
    }

    /** 停止后 2 秒仍未排空 → 不完整收尾 */
    fun checkShutdownDeadline(): Boolean = synchronized(lock) {
        if (!shutdown || queue.isEmpty()) return false
        if (monoMs() - shutdownAt >= 2_000) {
            breakWith(BreakReason.SHUTDOWN_TIMEOUT)
            return true
        }
        false
    }

    /** 中断恢复/新采集段：结束中断状态并丢弃旧积压（新旧不拼接） */
    fun clear() = synchronized(lock) {
        queue.clear()
        queuedBytes = 0
        broken = false
        reason = null
        shutdown = false
        lastProgressAt = monoMs()
    }

    private fun breakWith(r: BreakReason): BreakReason {
        broken = true
        reason = r
        return r
    }
}
