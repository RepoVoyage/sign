package com.repovoyage.sign.p3

import com.repovoyage.sign.video.DecodeFrameQueue
import com.repovoyage.sign.video.EncodedFrame
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P3 待解码完整帧队列验收（ARCHITECTURE.md §2.2.5 表 3 / API.md §2.3）：
 * 最多 4 帧 / 合计 8 MiB / 最老排队 250ms；超限回调只报告一次过载并阻止继续积压
 * （配套动作为清空解码链重新同步，由接线层负责）；clear 清空旧帧结束过载期。
 */
class DecodeFrameQueueTest {

    private var overloadReports = 0
    private var now = 0L

    private fun frame(bytes: Int = 1, ptsUs: Long = 0) =
        EncodedFrame(ByteArray(bytes), ptsUs, isSyncPoint = false, streamGeneration = 1)

    private fun queue(
        maxFrames: Int = 4,
        maxBytes: Int = 8 * 1024 * 1024,
        maxQueuedAgeMs: Long = 250,
    ) = DecodeFrameQueue(maxFrames, maxBytes, maxQueuedAgeMs, onOverload = { overloadReports++ }, monoMs = { now })

    @Test
    fun `正常入队按序取出`() = runBlocking {
        val q = queue()
        assertTrue(q.offer(frame(ptsUs = 1)))
        assertTrue(q.offer(frame(ptsUs = 2)))
        assertEquals(1L, q.receive().ptsUs)
        assertEquals(2L, q.receive().ptsUs)
        assertEquals(0, overloadReports)
    }

    @Test
    fun `帧数超限报告一次过载并丢弃后续帧`() {
        val q = queue(maxFrames = 2)
        assertTrue(q.offer(frame()))
        assertTrue(q.offer(frame()))
        assertFalse(q.isOverloaded)
        assertFalse(q.offer(frame()))
        assertTrue(q.isOverloaded)
        // 过载期内继续丢弃，不重复报告（回调只报告一次）
        assertFalse(q.offer(frame()))
        assertEquals(1, overloadReports)
    }

    @Test
    fun `字节数超限报告过载`() {
        val q = queue(maxBytes = 10)
        assertTrue(q.offer(frame(bytes = 6)))
        assertFalse(q.offer(frame(bytes = 6)))
        assertEquals(1, overloadReports)
    }

    @Test
    fun `最老帧排队超过时限报告过载`() {
        val q = queue(maxQueuedAgeMs = 250)
        now = 0
        assertTrue(q.offer(frame()))
        now = 250
        assertTrue(q.offer(frame()))                          // 恰好 250ms 未超
        now = 251
        assertFalse(q.offer(frame()))                         // 最老已 251ms
        assertEquals(1, overloadReports)
    }

    @Test
    fun `clear 清空旧帧并结束过载期`() = runBlocking {
        val q = queue(maxFrames = 1)
        assertTrue(q.offer(frame(ptsUs = 1)))
        assertFalse(q.offer(frame()))                         // 触发过载
        q.clear()
        assertFalse(q.isOverloaded)
        // 旧积压不串入新解码链
        assertTrue(q.offer(frame(ptsUs = 2)))
        assertEquals(2L, q.receive().ptsUs)
        assertEquals(1, overloadReports)
    }

    @Test
    fun `clear 后再次过载可再次报告`() {
        val q = queue(maxFrames = 1)
        q.offer(frame())
        q.offer(frame())                                      // 过载 1
        q.clear()
        q.offer(frame())
        q.offer(frame())                                      // 过载 2（新过载期）
        assertEquals(2, overloadReports)
    }
}
