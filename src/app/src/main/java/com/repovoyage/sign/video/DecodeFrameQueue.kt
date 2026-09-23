package com.repovoyage.sign.video

import android.os.SystemClock
import kotlinx.coroutines.channels.Channel

/**
 * 待解码完整帧队列（ARCHITECTURE.md §2.2.5 表 3：最多 4 帧且合计不超过 8 MiB，
 * 最老帧排队不超过 250ms）。
 *
 * 生产侧（分片消费协程）：offer 非阻塞——入队时用 mono 时钟记龄；超限只报告一次
 * 过载（onOverload 每个过载期恰好一次）并在过载期内丢弃新帧。消费侧（解码协程）：
 * receive 取出并归还帧数/字节账目；检测到过载后调 clear 清空旧帧结束过载期
 * （配套动作为冲刷解码链、重新门控同步点与请求关键帧，由接线层负责）。
 */
class DecodeFrameQueue(
    private val maxFrames: Int = 4,
    private val maxBytes: Int = 8 * 1024 * 1024,
    private val maxQueuedAgeMs: Long = 250,
    private val onOverload: () -> Unit,
    private val monoMs: () -> Long = SystemClock::elapsedRealtime,
) {

    private val lock = Any()
    private val channel = Channel<EncodedFrame>(capacity = maxFrames)

    /** 当前待解码帧数（training 诊断显示用，§2.7 缓冲占用） */
    val depth: Int get() = synchronized(lock) { queuedMonos.size }

    /** 队列内帧的入队时间账目（头元素即最老帧），与 channel 一一对应 */
    private val queuedMonos = ArrayDeque<Long>()
    private var queuedBytes = 0
    private var overload = false

    /** 过载期是否进行中（clear 结束后才恢复接收） */
    val isOverloaded: Boolean
        get() = synchronized(lock) { overload }

    /** offer 的三态结果：决定是否在锁外触发一次性过载回调 */
    private enum class Outcome { ACCEPTED, DROPPED, OVERLOAD_STARTED }

    /**
     * 投递完整编码帧；true = 已入队，false = 被丢弃（过载期或已关闭）。
     * 过载期开始时在锁外回调一次 onOverload（不得阻塞，接线层自行 hop）。
     */
    fun offer(frame: EncodedFrame): Boolean {
        val now = monoMs()
        val outcome = synchronized(lock) {
            when {
                channel.isClosedForSend -> Outcome.DROPPED
                overload -> Outcome.DROPPED
                else -> {
                    val ageExceeded = queuedMonos.isNotEmpty() &&
                        now - queuedMonos.first() > maxQueuedAgeMs
                    val bytesExceeded = queuedBytes + frame.data.size > maxBytes
                    if (ageExceeded || bytesExceeded) {
                        overload = true
                        Outcome.OVERLOAD_STARTED
                    } else {
                        val result = channel.trySend(frame)
                        if (result.isSuccess) {
                            queuedMonos.addLast(now)
                            queuedBytes += frame.data.size
                            Outcome.ACCEPTED
                        } else {
                            overload = true
                            Outcome.OVERLOAD_STARTED
                        }
                    }
                }
            }
        }
        if (outcome == Outcome.OVERLOAD_STARTED) onOverload()
        return outcome == Outcome.ACCEPTED
    }

    /** 取出队头帧（挂起等待）；消费后归还帧数/字节账目 */
    suspend fun receive(): EncodedFrame {
        val frame = channel.receive()
        synchronized(lock) {
            // clear 可能已重置账目（该帧属被丢弃的旧数据），容忍空账
            queuedMonos.removeFirstOrNull()
            queuedBytes = (queuedBytes - frame.data.size).coerceAtLeast(0)
        }
        return frame
    }

    /** 消费侧过载恢复：清空全部旧帧并结束过载期 */
    fun clear() {
        synchronized(lock) {
            while (channel.tryReceive().isSuccess) {
                // 丢弃旧积压
            }
            queuedMonos.clear()
            queuedBytes = 0
            overload = false
        }
    }

    /** 流停止：关闭队列，此后 offer 一律拒绝且不触发过载报告 */
    fun close() {
        synchronized(lock) { channel.close() }
    }
}
