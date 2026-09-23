package com.repovoyage.sign.p3

import com.arashivision.sdk.camera.api.preview.PreviewStreamType
import com.repovoyage.sign.video.ChunkIngestQueue
import com.repovoyage.sign.video.StreamChunk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P3 编码分片入口验收（ARCHITECTURE.md §2.2.5 表 1 / API.md §2.3）：
 * 最多 128 片 / 8 MiB / 最老 250ms；超限回调只报告一次过载并阻止继续积压；
 * 过载期新分片直接丢弃；消费侧 clear 清空旧数据结束过载期，此后恢复正常并可再次报告。
 */
class ChunkIngestQueueTest {

    private var overloadReports = 0

    private fun chunk(bytes: Int = 1, monoMs: Long = 0) =
        StreamChunk(ByteArray(bytes), 0, PreviewStreamType.VIDEO, monoMs, 1)

    private fun queue(
        maxChunks: Int = 128,
        maxBytes: Int = 8 * 1024 * 1024,
        maxQueuedAgeMs: Long = 250,
    ) = ChunkIngestQueue(maxChunks, maxBytes, maxQueuedAgeMs, onOverload = { overloadReports++ })

    @Test
    fun `正常入队按序取出`() = runBlocking {
        val q = queue()
        assertTrue(q.offer(chunk().copy(data = byteArrayOf(1))))
        assertTrue(q.offer(chunk().copy(data = byteArrayOf(2))))
        assertEquals(byteArrayOf(1).toList(), q.receive().data.toList())
        assertEquals(byteArrayOf(2).toList(), q.receive().data.toList())
        assertEquals(0, overloadReports)
    }

    @Test
    fun `片数超限报告一次过载并丢弃后续分片`() {
        val q = queue(maxChunks = 2)
        assertTrue(q.offer(chunk()))
        assertTrue(q.offer(chunk()))
        assertTrue(q.isOverloaded.not())
        assertFalse(q.offer(chunk()))
        assertTrue(q.isOverloaded)
        // 过载期内继续丢弃，不重复报告（回调只报告一次）
        assertFalse(q.offer(chunk()))
        assertEquals(1, overloadReports)
    }

    @Test
    fun `字节数超限报告过载`() {
        val q = queue(maxBytes = 10)
        assertTrue(q.offer(chunk(bytes = 6)))
        assertFalse(q.offer(chunk(bytes = 6)))
        assertEquals(1, overloadReports)
    }

    @Test
    fun `最老分片排队超过时限报告过载`() {
        val q = queue(maxQueuedAgeMs = 250)
        assertTrue(q.offer(chunk(monoMs = 0)))
        assertTrue(q.offer(chunk(monoMs = 250)))          // 恰好 250ms 未超
        assertFalse(q.offer(chunk(monoMs = 251)))         // 最老已 251ms
        assertEquals(1, overloadReports)
    }

    @Test
    fun `clear 清空旧分片并结束过载期`() = runBlocking {
        val q = queue(maxChunks = 1)
        assertTrue(q.offer(chunk().copy(data = byteArrayOf(1))))
        assertFalse(q.offer(chunk()))                     // 触发过载
        q.clear()
        assertTrue(q.isOverloaded.not())
        // 旧积压不串入新流
        assertTrue(q.offer(chunk().copy(data = byteArrayOf(2))))
        assertEquals(byteArrayOf(2).toList(), q.receive().data.toList())
        assertEquals(1, overloadReports)
    }

    @Test
    fun `clear 后再次过载可再次报告`() {
        val q = queue(maxChunks = 1)
        q.offer(chunk())
        q.offer(chunk())                                  // 过载 1
        q.clear()
        q.offer(chunk())
        q.offer(chunk())                                  // 过载 2（新过载期）
        assertEquals(2, overloadReports)
    }
}
