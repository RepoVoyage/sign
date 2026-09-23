package com.repovoyage.sign.p3

import com.repovoyage.sign.video.DecodeSyncGate
import com.repovoyage.sign.video.EncodedFrame
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P3 解码投喂门控验收（ARCHITECTURE.md §2.2.2 第 5 行：参数集和可用随机访问帧齐备
 * 才开始有效出图，不直接投喂任意中间帧）。代次切换/解码链重同步后须重新门控。
 */
class DecodeSyncGateTest {

    private val gate = DecodeSyncGate()

    private fun frame(sync: Boolean, gen: Long = 1) =
        EncodedFrame(byteArrayOf(1), 0, isSyncPoint = sync, streamGeneration = gen)

    @Test
    fun `同步点前的中间帧不投喂`() {
        assertFalse(gate.shouldFeed(frame(sync = false)))
        assertFalse(gate.shouldFeed(frame(sync = false)))
    }

    @Test
    fun `同步点起开始投喂且后续 P 帧继续投喂`() {
        assertFalse(gate.shouldFeed(frame(sync = false)))
        assertTrue(gate.shouldFeed(frame(sync = true)))
        assertTrue(gate.shouldFeed(frame(sync = false)))
        assertTrue(gate.shouldFeed(frame(sync = false)))
    }

    @Test
    fun `代次切换重新门控`() {
        assertTrue(gate.shouldFeed(frame(sync = true, gen = 1)))
        // 新代次 = 新流：重新等待随机访问点
        assertFalse(gate.shouldFeed(frame(sync = false, gen = 2)))
        assertTrue(gate.shouldFeed(frame(sync = true, gen = 2)))
        assertTrue(gate.shouldFeed(frame(sync = false, gen = 2)))
    }

    @Test
    fun `reset 后重新门控（解码链冲刷或过载重同步）`() {
        assertTrue(gate.shouldFeed(frame(sync = true)))
        gate.reset()
        assertFalse(gate.shouldFeed(frame(sync = false)))
        assertTrue(gate.shouldFeed(frame(sync = true)))
    }

    @Test
    fun `旧代次迟到帧不误开门`() {
        assertTrue(gate.shouldFeed(frame(sync = true, gen = 2)))
        // 迟到的旧代次同步帧不得作为本代次的随机访问点
        assertFalse(gate.shouldFeed(frame(sync = true, gen = 1)))
        assertTrue(gate.shouldFeed(frame(sync = false, gen = 2)))
    }
}
