package com.repovoyage.sign.p2

import com.repovoyage.sign.camera.ReconnectPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Ignore
import org.junit.Test

/**
 * P2 重连退避（API.md §1.2 初始值：1、2、4、8、16 秒，最多 5 次；成功出图后清零）。
 */
class ReconnectPolicyTest {

    private val policy = ReconnectPolicy()

    @Test
    fun `退避序列为 1 2 4 8 16 秒`() {
        assertEquals(1_000L, policy.onDisconnected())
        assertEquals(2_000L, policy.onDisconnected())
        assertEquals(4_000L, policy.onDisconnected())
        assertEquals(8_000L, policy.onDisconnected())
        assertEquals(16_000L, policy.onDisconnected())
    }

    @Test
    fun `第 5 次之后放弃返回 null`() {
        repeat(5) { policy.onDisconnected() }
        assertNull(policy.onDisconnected())
    }

    @Test
    fun `成功出图后计数清零从 1 秒重新开始`() {
        repeat(3) { policy.onDisconnected() }
        policy.onStreamRecovered()
        assertEquals(1_000L, policy.onDisconnected())
    }

    @Test
    fun `耗尽后 reset 可重新走完整周期`() {
        repeat(6) { policy.onDisconnected() }
        assertNull(policy.onDisconnected())
        policy.onStreamRecovered()
        // 重置后又是完整序列：5 次有效，第 6 次放弃
        assertEquals(1_000L, policy.onDisconnected())
        assertEquals(2_000L, policy.onDisconnected())
        assertEquals(4_000L, policy.onDisconnected())
        assertEquals(8_000L, policy.onDisconnected())
        assertEquals(16_000L, policy.onDisconnected())
        assertNull(policy.onDisconnected())
    }

    @Test
    fun `未断连时 reset 无副作用`() {
        policy.onStreamRecovered()
        policy.onStreamRecovered()
        assertEquals(1_000L, policy.onDisconnected())
    }

    @Test
    fun `断连与恢复交错时计数以最近一次恢复为基准`() {
        assertEquals(1_000L, policy.onDisconnected())
        assertEquals(2_000L, policy.onDisconnected())
        policy.onStreamRecovered()
        assertEquals(1_000L, policy.onDisconnected())
        assertEquals(2_000L, policy.onDisconnected())
    }
}
