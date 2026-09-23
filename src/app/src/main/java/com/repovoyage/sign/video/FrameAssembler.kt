package com.repovoyage.sign.video

import com.arashivision.sdk.camera.api.preview.PreviewStreamType

/**
 * 分片聚合器（ARCHITECTURE.md §2.2.1）：
 * 同 timestamp 分片累积，timestamp 切换提交上一帧；超限/超时/代次切换丢弃当前
 * 聚合并重同步（忽略同 timestamp 残片，等新 timestamp 干净起步）；
 * 非视频分片忽略；流结束时未确认完整的尾帧丢弃。
 * isSyncPoint 由解码准备链的关键帧验证器标记，本层恒为 false。
 */
class FrameAssembler(
    private val maxFrameBytes: Int = 4 * 1024 * 1024,
    private val maxSameTimestampSpanMs: Long = 500,
) {

    /** 进行中的聚合：timestamp 确定后才开始，重同步期间为 null */
    private class Aggregation(val timestampMs: Long, val firstChunkMonoMs: Long, val generation: Long) {
        val chunks = mutableListOf<ByteArray>()
        var bufferedBytes = 0
    }

    /** 已见最高代次：代次回退的旧数据忽略；finish 不清零，停流后迟到旧分片同样失效 */
    private var highestGeneration: Long? = null

    private var current: Aggregation? = null

    /** 重同步标记：该 timestamp 的残片忽略，新 timestamp 到来才干净起步 */
    private var resyncTimestampMs: Long? = null

    /** 投递一个分片；返回因 timestamp 切换而完成的帧（0 或 1 个） */
    fun offer(chunk: StreamChunk): List<EncodedFrame> {
        if (chunk.type != PreviewStreamType.VIDEO) return emptyList()
        val highest = highestGeneration
        when {
            highest == null -> highestGeneration = chunk.streamGeneration
            chunk.streamGeneration < highest -> return emptyList()
            chunk.streamGeneration > highest -> {
                // 代次切换：旧缓冲与重同步状态一并作废，新代次从本分片干净起步
                highestGeneration = chunk.streamGeneration
                current = null
                resyncTimestampMs = null
            }
        }
        val resyncTs = resyncTimestampMs
        if (resyncTs != null) {
            if (chunk.timestampMs == resyncTs) return emptyList()
            resyncTimestampMs = null
        }
        val agg = current
        if (agg == null) {
            startAggregation(chunk)
            return emptyList()
        }
        if (chunk.timestampMs == agg.timestampMs) {
            val spanExceeded = chunk.receivedAtMonoMs - agg.firstChunkMonoMs > maxSameTimestampSpanMs
            val sizeExceeded = agg.bufferedBytes + chunk.data.size > maxFrameBytes
            if (spanExceeded || sizeExceeded) {
                current = null
                resyncTimestampMs = agg.timestampMs
                return emptyList()
            }
            agg.chunks += chunk.data
            agg.bufferedBytes += chunk.data.size
            return emptyList()
        }
        // timestamp 切换：提交上一帧，本分片开启新聚合
        val frame = EncodedFrame(agg.concat(), agg.timestampMs * 1_000, isSyncPoint = false, agg.generation)
        startAggregation(chunk)
        return listOf(frame)
    }

    /** 流结束/停止：未确认完整的尾帧丢弃，返回空 */
    fun finish(): List<EncodedFrame> {
        current = null
        resyncTimestampMs = null
        return emptyList()
    }

    private fun startAggregation(chunk: StreamChunk) {
        val agg = Aggregation(chunk.timestampMs, chunk.receivedAtMonoMs, chunk.streamGeneration)
        agg.chunks += chunk.data
        agg.bufferedBytes = chunk.data.size
        current = agg
    }

    private fun Aggregation.concat(): ByteArray {
        val out = ByteArray(bufferedBytes)
        var pos = 0
        for (c in chunks) {
            c.copyInto(out, pos)
            pos += c.size
        }
        return out
    }
}
