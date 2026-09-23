package com.repovoyage.sign.video

import android.os.SystemClock
import kotlinx.coroutines.channels.Channel

/**
 * 编码分片入口队列（ARCHITECTURE.md §2.2.5 表 1：最多 128 片且合计不超过 8 MiB，
 * 最老数据排队不超过 250ms）。
 *
 * 生产侧（SDK onStreamDataNotify 回调线程）：offer 非阻塞——只做容量判定与入队；
 * 超限时只报告一次过载（onOverload 每个过载期恰好一次）并在过载期内丢弃新分片，
 * 阻止继续积压。消费侧（工作协程）：receive 取出并归还字节账目；检测到过载后调
 * clear 清空旧编码数据结束过载期（配套动作为聚合器重同步与请求关键帧，由接线层负责）。
 */
class ChunkIngestQueue(
    private val maxChunks: Int = 128,
    private val maxBytes: Int = 8 * 1024 * 1024,
    private val maxQueuedAgeMs: Long = 250,
    private val onOverload: () -> Unit,
    private val monoMs: () -> Long = SystemClock::elapsedRealtime,
) {

    private val lock = Any()
    private val channel = Channel<StreamChunk>(capacity = maxChunks)

    /** 当前积压分片数（training 诊断显示用，§2.7 缓冲占用） */
    val depth: Int get() = synchronized(lock) { queuedMonos.size }

    /** 队列内分片的接收时间账目（头元素即最老分片），与 channel 一一对应 */
    private val queuedMonos = ArrayDeque<Long>()
    private var queuedBytes = 0
    private var overload = false

    /** 过载期是否进行中（clear 结束后才恢复接收） */
    val isOverloaded: Boolean
        get() = synchronized(lock) { overload }

    /** offer 的三态结果：决定是否在锁外触发一次性过载回调 */
    private enum class Outcome { ACCEPTED, DROPPED, OVERLOAD_STARTED }

    /**
     * 投递分片；true = 已入队，false = 被丢弃（过载期或已关闭）。
     * 过载期开始时在锁外回调一次 onOverload（不得阻塞，接线层自行 hop）。
     */
    fun offer(chunk: StreamChunk): Boolean {
        val outcome = synchronized(lock) {
            when {
                channel.isClosedForSend -> Outcome.DROPPED
                overload -> Outcome.DROPPED
                else -> {
                    val ageExceeded = queuedMonos.isNotEmpty() &&
                        chunk.receivedAtMonoMs - queuedMonos.first() > maxQueuedAgeMs
                    val bytesExceeded = queuedBytes + chunk.data.size > maxBytes
                    if (ageExceeded || bytesExceeded) {
                        overload = true
                        Outcome.OVERLOAD_STARTED
                    } else {
                        val result = channel.trySend(chunk)
                        if (result.isSuccess) {
                            queuedMonos.addLast(chunk.receivedAtMonoMs)
                            queuedBytes += chunk.data.size
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

    /** 取出队头分片（挂起等待）；消费后归还字节/年龄账目 */
    suspend fun receive(): StreamChunk {
        val chunk = channel.receive()
        synchronized(lock) {
            // clear 可能已重置账目（该分片属被丢弃的旧数据），容忍空账
            queuedMonos.removeFirstOrNull()
            queuedBytes = (queuedBytes - chunk.data.size).coerceAtLeast(0)
        }
        return chunk
    }

    /** 消费侧过载恢复：清空全部旧编码数据并结束过载期 */
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
