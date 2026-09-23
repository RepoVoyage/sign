package com.repovoyage.sign.p4

import com.repovoyage.sign.usb.FrameSendPool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P4 发送池背压验收（API.md §9.4 / ARCHITECTURE.md §2.8.2）：
 * 预算 128 MiB（覆盖转换中/待发/在途及元数据）；缓冲目标 2 秒——帧数上限
 * ceil(captureFps×2)；帧入发送阶段到完整写出 ≤ 2s（最老帧年龄，不因零星写出续期）；
 * 入队超限/最老帧超时/连续 1s 写入无进展 → 中断采集段；用户停止 → 既有帧 2s 内
 * 收尾（不重置各帧截止）。不静默丢帧。
 */
class FrameSendPoolTest {

    private var now = 0L

    private fun pool(
        maxFrames: Int = 60,
        maxBytes: Long = 128 * 1024 * 1024,
    ) = FrameSendPool(maxFrames, maxBytes, monoMs = { now })

    private fun frame(bytes: Int, index: Long = 0) =
        FrameSendPool.Frame(bytes = ByteArray(bytes), frameIndex = index, ptsUs = index * 33_333)

    @Test
    fun `正常入队取出按序`() {
        val p = pool()
        assertNull(p.offer(frame(bytes = 10, index = 1), enteredAt = 0))
        assertNull(p.offer(frame(bytes = 10, index = 2), enteredAt = 0))
        assertEquals(1L, p.poll()?.frameIndex)
        assertEquals(2L, p.poll()?.frameIndex)
        assertNull(p.poll())
        assertFalse(p.isBroken)
    }

    @Test
    fun `帧数超限触发中断而非丢帧`() {
        val p = pool(maxFrames = 2)
        assertNull(p.offer(frame(10, 1), enteredAt = 0))
        assertNull(p.offer(frame(10, 2), enteredAt = 0))
        // 第三帧超帧数上限：不丢帧、不覆盖，报告中断（清空由接线层执行）
        val reason = p.offer(frame(10, 3), enteredAt = 0)
        assertNotNull(reason)
        assertEquals(FrameSendPool.BreakReason.CAPACITY, reason)
        assertTrue(p.isBroken)
    }

    @Test
    fun `字节超限触发中断`() {
        val p = pool(maxBytes = 100)
        assertNull(p.offer(frame(60, 1), enteredAt = 0))
        val reason = p.offer(frame(60, 2), enteredAt = 0)
        assertNotNull(reason)
        assertEquals(FrameSendPool.BreakReason.CAPACITY, reason)
    }

    @Test
    fun `入队时最老帧已超 2 秒即中断`() {
        val p = pool()
        assertNull(p.offer(frame(10, 1), enteredAt = 0))
        now = 2_000
        // 最老帧进入发送阶段已 2s（入队时刻=进入发送阶段时刻）
        val reason = p.offer(frame(10, 2), enteredAt = 2_000)
        assertNotNull(reason)
        assertEquals(FrameSendPool.BreakReason.OLDEST_FRAME_TIMEOUT, reason)
    }

    @Test
    fun `取出帧后释放账目可继续入队`() {
        val p = pool(maxFrames = 1)
        assertNull(p.offer(frame(10, 1), enteredAt = 0))
        assertNotNull(p.poll())
        now = 1
        assertNull(p.offer(frame(10, 2), enteredAt = 1))
        assertEquals(2L, p.poll()?.frameIndex)
    }

    @Test
    fun `写出进度重置无进展计时`() {
        val p = pool()
        assertNull(p.offer(frame(10, 1), enteredAt = 0))
        now = 900
        assertFalse(p.checkNoProgressTimeout())            // 900ms 无进展未超
        assertNotNull(p.poll())                            // 写出 = 有进展
        now = 1_800
        assertFalse(p.checkNoProgressTimeout())            // 距上次进展 900ms
        now = 2_801
        assertTrue(p.checkNoProgressTimeout())             // 距上次进展 1s+ → 中断
        assertEquals(FrameSendPool.BreakReason.NO_PROGRESS, p.breakReason)
    }

    @Test
    fun `中断后拒绝入队`() {
        val p = pool(maxFrames = 1)
        p.offer(frame(10, 1), enteredAt = 0)
        assertNotNull(p.offer(frame(10, 2), enteredAt = 0))  // 触发中断
        assertEquals(FrameSendPool.BreakReason.ALREADY_BROKEN, p.offer(frame(10, 3), enteredAt = 0))
    }

    @Test
    fun `clear 重置中断状态与积压`() {
        val p = pool(maxFrames = 1)
        p.offer(frame(10, 1), enteredAt = 0)
        p.offer(frame(10, 2), enteredAt = 0)                 // 中断
        p.clear()
        assertFalse(p.isBroken)
        assertNull(p.poll())                                 // 旧积压不拼新段
        now = 1
        assertNull(p.offer(frame(10, 3), enteredAt = 1))
    }

    @Test
    fun `beginShutdown 后拒绝新帧且可继续收尾排空`() {
        val p = pool()
        assertNull(p.offer(frame(10, 1), enteredAt = 0))
        p.beginShutdown(now = 0)
        assertEquals(FrameSendPool.BreakReason.SHUTDOWN, p.offer(frame(10, 2), enteredAt = 0))
        // 既有帧仍可按原截止收尾（取出/写出不受影响）
        assertEquals(1L, p.poll()?.frameIndex)
        assertFalse(p.isBroken)
    }

    @Test
    fun `收尾超 2 秒按不完整中断`() {
        val p = pool()
        assertNull(p.offer(frame(10, 1), enteredAt = 0))
        p.beginShutdown(now = 0)
        now = 2_000
        // 停止后 2s 仍未排空（帧的原截止也不允许超过）
        assertTrue(p.checkShutdownDeadline())
        assertEquals(FrameSendPool.BreakReason.SHUTDOWN_TIMEOUT, p.breakReason)
    }

    @Test
    fun `排空后收尾正常完成`() {
        val p = pool()
        assertNull(p.offer(frame(10, 1), enteredAt = 0))
        p.beginShutdown(now = 0)
        assertNotNull(p.poll())
        now = 2_000
        assertFalse(p.checkShutdownDeadline())
        assertNull(p.breakReason)
    }
}
