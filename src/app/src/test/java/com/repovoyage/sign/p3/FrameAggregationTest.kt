package com.repovoyage.sign.p3

import com.arashivision.sdk.camera.api.preview.PreviewStreamType
import com.repovoyage.sign.video.EncodedFrame
import com.repovoyage.sign.video.FrameAssembler
import com.repovoyage.sign.video.StreamChunk
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P3 分片聚合验收（ARCHITECTURE.md §2.2.1 / API.md §2）。
 * 开工时移除 @Ignore：先红（TODO）→ 实现 → 绿。
 */
class FrameAggregationTest {

    private val assembler = FrameAssembler()
    private val committed = mutableListOf<EncodedFrame>()

    private fun offer(tsMs: Long, vararg bytes: Int, type: PreviewStreamType = PreviewStreamType.VIDEO,
                      monoMs: Long = 0, generation: Long = 1) {
        committed += assembler.offer(
            StreamChunk(bytes.map { it.toByte() }.toByteArray(), tsMs, type, monoMs, generation)
        )
    }

    @Test
    fun `同 timestamp 分片聚合为单帧且毫秒转微秒`() {
        offer(100, 1, 2)
        offer(100, 3, 4)
        offer(100, 5, 6)
        assertTrue(committed.isEmpty())
        offer(200, 9)
        assertEquals(1, committed.size)
        val f = committed.single()
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5, 6), f.data)
        assertEquals(100_000L, f.ptsUs)
        assertEquals(1L, f.streamGeneration)
        // 聚合层不判定关键帧，isSyncPoint 由解码准备链的验证器标记
        assertFalse(f.isSyncPoint)
    }

    @Test
    fun `timestamp 切换提交上一帧`() {
        offer(10, 1)
        offer(20, 2)                       // 提交 t=10
        offer(30, 3)                       // 提交 t=20
        assertEquals(2, committed.size)
        assertEquals(10_000L, committed[0].ptsUs)
        assertEquals(20_000L, committed[1].ptsUs)
    }

    @Test
    fun `非视频分片被忽略不进聚合`() {
        offer(100, 1, type = PreviewStreamType.GYRO)
        offer(100, 2, type = PreviewStreamType.AUDIO)
        offer(200, 9)
        assertTrue(committed.isEmpty())
        offer(300, 8)                      // 提交仅含视频分片的帧
        assertEquals(1, committed.size)
        assertArrayEquals(byteArrayOf(9), committed.single().data)
    }

    @Test
    fun `未确认完整的尾帧在流结束时丢弃`() {
        offer(100, 1, 2)
        assertTrue(assembler.finish().isEmpty())
    }

    @Test
    fun `聚合超过 4MiB 丢弃并等待新 timestamp 重同步`() {
        val big = ByteArray(3 * 1024 * 1024)
        committed += assembler.offer(StreamChunk(big, 100, PreviewStreamType.VIDEO, 0, 1))
        committed += assembler.offer(StreamChunk(big, 100, PreviewStreamType.VIDEO, 0, 1))   // 6MiB 超限
        offer(100, 7)                      // 同 timestamp 残片：重同步中忽略
        offer(200, 8)                      // 新 timestamp 干净起步
        offer(300, 9)                      // 提交 [8]
        assertEquals(1, committed.size)
        assertArrayEquals(byteArrayOf(8), committed.single().data)
    }

    @Test
    fun `同 timestamp 跨度超 500 毫秒丢弃并重同步`() {
        offer(100, 1, monoMs = 0)
        offer(100, 2, monoMs = 499)        // 未超时
        offer(100, 3, monoMs = 501)        // 超时 → 丢弃当前聚合
        offer(100, 4, monoMs = 600)        // 重同步中忽略
        offer(200, 5, monoMs = 700)
        offer(300, 6, monoMs = 800)        // 提交 [5]
        assertEquals(1, committed.size)
        assertArrayEquals(byteArrayOf(5), committed.single().data)
    }

    @Test
    fun `streamGeneration 切换丢弃旧缓冲不混帧`() {
        offer(100, 1, generation = 1)
        offer(100, 2, generation = 1)
        offer(100, 3, generation = 2)      // 代次切换：旧缓冲丢弃
        offer(200, 4, generation = 2)      // 提交新代次首帧
        assertEquals(1, committed.size)
        assertArrayEquals(byteArrayOf(3), committed.single().data)
        assertEquals(2L, committed.single().streamGeneration)
    }

    @Test
    fun `代次回退的旧数据被忽略`() {
        offer(100, 1, generation = 2)
        offer(100, 2, generation = 1)      // 旧代次分片：忽略
        offer(200, 3, generation = 2)
        assertEquals(1, committed.size)
        assertArrayEquals(byteArrayOf(1), committed.single().data)
    }
}
