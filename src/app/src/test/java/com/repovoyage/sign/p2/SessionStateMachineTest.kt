package com.repovoyage.sign.p2

import com.repovoyage.sign.camera.AuthStatus
import com.repovoyage.sign.camera.SessionError
import com.repovoyage.sign.camera.SessionState
import com.repovoyage.sign.camera.SessionStateMachine
import com.repovoyage.sign.camera.StreamParams
import com.repovoyage.sign.camera.VideoEncodeType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Test

/**
 * P2 状态机验收（ARCHITECTURE.md §2.1.3 状态图 + API.md §1 契约）。
 * 真机连续 10 次连接成功率等集成验收在 CameraSession 装机后进行，不在此文件。
 */
class SessionStateMachineTest {

    private val machine = SessionStateMachine()

    private fun streamParams(generation: Long = 1) =
        StreamParams(640, 384, 30, VideoEncodeType.H264, generation)

    private fun reachStreaming() {
        assertTrue(machine.start())
        assertTrue(machine.transitionTo(SessionState.BleConnecting("GO 3S")))
        assertTrue(machine.transitionTo(SessionState.WifiConnecting))
        assertTrue(machine.transitionTo(SessionState.Authorizing(AuthStatus.WAITING)))
        assertTrue(machine.transitionTo(SessionState.Preparing))
        assertTrue(machine.transitionTo(SessionState.Streaming(streamParams())))
    }

    @Test
    fun `正常路径完整走通`() {
        reachStreaming()
        assertTrue(machine.state is SessionState.Streaming)
    }

    @Test
    fun `连接丢失进入 Reconnecting 退避递增且从头恢复`() {
        reachStreaming()
        assertTrue(machine.onDisconnected())
        assertEquals(SessionState.Reconnecting(1, 1_000L), machine.state)
        assertTrue(machine.onDisconnected())
        assertEquals(SessionState.Reconnecting(2, 2_000L), machine.state)
        // 恢复需重新执行网络、授权及准备步骤（不走 BLE 重扫）
        assertTrue(machine.transitionTo(SessionState.WifiConnecting))
        assertTrue(machine.transitionTo(SessionState.Authorizing(AuthStatus.WAITING)))
        assertTrue(machine.transitionTo(SessionState.Preparing))
        assertTrue(machine.transitionTo(SessionState.Streaming(streamParams(generation = 2))))
    }

    @Test
    fun `重试耗尽进入 Error`() {
        reachStreaming()
        repeat(5) { assertTrue(machine.onDisconnected()) }
        assertEquals(SessionState.Reconnecting(5, 16_000L), machine.state)
        assertTrue(machine.onDisconnected())
        assertEquals(SessionState.Error(SessionError.RETRY_EXHAUSTED), machine.state)
        // Error 后仅允许用户重试（→ Checking）
        assertFalse(machine.transitionTo(SessionState.WifiConnecting))
        assertTrue(machine.transitionTo(SessionState.Checking))
    }

    @Test
    fun `新会话重置上一会话耗尽的重试预算`() {
        // 真机发现：耗尽的退避额度若跨会话残留，新会话首次断连直接 Error
        reachStreaming()
        repeat(6) { machine.onDisconnected() }
        assertEquals(SessionState.Error(SessionError.RETRY_EXHAUSTED), machine.state)
        // 用户重试 → 进入 Checking 即新会话：预算重置，从 1s 退避重新开始
        //（P2 止于 Preparing 无出图，也不受影响）
        assertTrue(machine.transitionTo(SessionState.Checking))
        machine.transitionTo(SessionState.BleConnecting("GO 3S"))
        machine.transitionTo(SessionState.WifiConnecting)
        machine.transitionTo(SessionState.Authorizing(AuthStatus.WAITING))
        machine.transitionTo(SessionState.Preparing)
        assertTrue(machine.onDisconnected())
        assertEquals(SessionState.Reconnecting(1, 1_000L), machine.state)
    }

    @Test
    fun `成功出图后重试计数清零`() {
        reachStreaming()
        repeat(3) { machine.onDisconnected() }
        // 恢复须重走网络/授权/准备步骤，不允许从 Reconnecting 直达 Streaming
        machine.transitionTo(SessionState.WifiConnecting)
        machine.transitionTo(SessionState.Authorizing(AuthStatus.WAITING))
        machine.transitionTo(SessionState.Preparing)
        machine.transitionTo(SessionState.Streaming(streamParams(generation = 2)))
        machine.onDisconnected()
        assertEquals(SessionState.Reconnecting(1, 1_000L), machine.state)
    }

    @Test
    fun `用户停止优先于延迟重试`() {
        reachStreaming()
        machine.onDisconnected()
        assertTrue(machine.onUserStop())
        assertEquals(SessionState.Stopping, machine.state)
        // 停止后迟到断连事件不再推进状态
        assertFalse(machine.onDisconnected())
        assertTrue(machine.transitionTo(SessionState.Idle))
    }

    @Test
    fun `权限拒绝走停止不进重试`() {
        assertTrue(machine.start())
        assertTrue(machine.onUserStop())
        assertEquals(SessionState.Stopping, machine.state)
        assertTrue(machine.transitionTo(SessionState.Idle))
    }

    @Test
    fun `过热暂停且冷却后重新开始`() {
        reachStreaming()
        assertTrue(machine.onOverheat())
        assertEquals(SessionState.PausedHot, machine.state)
        assertTrue(machine.resumeAfterCooldown())
        assertEquals(SessionState.Checking, machine.state)
    }

    @Test
    fun `无初始化直连被拒绝`() {
        assertFalse(machine.transitionTo(SessionState.Streaming(streamParams())))
        assertEquals(SessionState.Idle, machine.state)
        assertFalse(machine.transitionTo(SessionState.Reconnecting(1, 1_000L)))
        assertFalse(machine.transitionTo(SessionState.WifiConnecting))
    }

    @Test
    fun `迟到事件不推进已停止的会话`() {
        reachStreaming()
        machine.onUserStop()
        machine.transitionTo(SessionState.Idle)
        assertFalse(machine.onDisconnected())
        assertFalse(machine.onOverheat())
        assertEquals(SessionState.Idle, machine.state)
    }

    @Test
    fun `重复 start 被拒绝`() {
        assertTrue(machine.start())
        assertFalse(machine.start())
        assertEquals(SessionState.Checking, machine.state)
    }

    @Test
    fun `连接尝试阶段忽略断连事件`() {
        // 逐级走连接梯子，未进入系统热点连接前的状态断言断连事件不推进
        // （此阶段失败走 connect() 回调的 Error 路径，不走退避）
        assertFalse(machine.onDisconnected())                                   // Idle
        machine.start()
        assertFalse(machine.onDisconnected())                                   // Checking
        machine.transitionTo(SessionState.BleConnecting("GO 3S"))
        assertFalse(machine.onDisconnected())
        machine.transitionTo(SessionState.WifiConnecting)
        machine.transitionTo(SessionState.Authorizing(AuthStatus.WAITING))
        machine.transitionTo(SessionState.Activating)
        assertFalse(machine.onDisconnected())                                   // Activating 需公网，不走退避
        machine.transitionTo(SessionState.Preparing)
        assertEquals(SessionState.Preparing, machine.state)
    }

    @Test
    fun `系统热点连接起的被动断连进入重连`() {
        // 断连监听在连接全程已注册（含 SDK 内部同步阶段），
        // 从系统热点连接起的连接态断连须走退避，否则会话无声卡死
        assertTrue(machine.start())
        machine.transitionTo(SessionState.BleConnecting("GO 3S"))
        machine.transitionTo(SessionState.WifiConnecting)
        assertTrue(machine.onDisconnected())
        assertEquals(SessionState.Reconnecting(1, 1_000L), machine.state)
    }

    @Test
    fun `授权与准备阶段的被动断连进入重连`() {
        // 已进入授权的连接态断连须走退避
        assertTrue(machine.start())
        machine.transitionTo(SessionState.BleConnecting("GO 3S"))
        machine.transitionTo(SessionState.WifiConnecting)
        machine.transitionTo(SessionState.Authorizing(AuthStatus.WAITING))
        assertTrue(machine.onDisconnected())
        assertEquals(SessionState.Reconnecting(1, 1_000L), machine.state)
        // 重试恢复到 Preparing（尚未出图）再次断连：退避递增且 attempt 与延迟一一对应
        machine.transitionTo(SessionState.WifiConnecting)
        machine.transitionTo(SessionState.Authorizing(AuthStatus.WAITING))
        machine.transitionTo(SessionState.Preparing)
        assertTrue(machine.onDisconnected())
        assertEquals(SessionState.Reconnecting(2, 2_000L), machine.state)
    }

    @Test
    fun `重连尝试失败从 WifiConnecting 消耗重试名额`() {
        // 真机发现：重连尝试中的系统热点连接失败若不消耗名额，退避循环无法推进，
        // 状态卡死在 WifiConnecting
        assertTrue(machine.start())
        machine.transitionTo(SessionState.BleConnecting("GO 3S"))
        machine.transitionTo(SessionState.WifiConnecting)
        machine.onDisconnected()                                            // → Reconnecting(1, 1s)
        machine.transitionTo(SessionState.WifiConnecting)                   // 重连尝试中
        assertTrue(machine.onDisconnected())
        assertEquals(SessionState.Reconnecting(2, 2_000L), machine.state)
    }

    @Test
    fun `含激活步骤的首次路径走通`() {
        assertTrue(machine.start())
        assertTrue(machine.transitionTo(SessionState.BleConnecting(null)))
        assertTrue(machine.transitionTo(SessionState.WifiConnecting))
        assertTrue(machine.transitionTo(SessionState.Authorizing(AuthStatus.WAITING)))
        assertTrue(machine.transitionTo(SessionState.Activating))
        assertTrue(machine.transitionTo(SessionState.Preparing))
        assertTrue(machine.transitionTo(SessionState.Streaming(streamParams())))
        assertTrue(machine.state is SessionState.Streaming)
    }

    @Test
    fun `Idle 无事可停`() {
        assertFalse(machine.onUserStop())
        assertFalse(machine.onOverheat())
        assertFalse(machine.onDisconnected())
        assertEquals(SessionState.Idle, machine.state)
    }

    @Test
    fun `停止幂等不重复变更状态`() {
        reachStreaming()
        assertTrue(machine.onUserStop())
        assertFalse(machine.onUserStop())
        assertEquals(SessionState.Stopping, machine.state)
    }

    @Test
    fun `过热仅来自流式且不重复触发`() {
        reachStreaming()
        assertTrue(machine.onOverheat())
        assertEquals(SessionState.PausedHot, machine.state)
        assertFalse(machine.onOverheat())
        assertEquals(SessionState.PausedHot, machine.state)
    }

    @Test
    fun `冷却恢复仅允许从 PausedHot`() {
        assertFalse(machine.resumeAfterCooldown())                    // Idle
        reachStreaming()
        assertFalse(machine.resumeAfterCooldown())                    // Streaming
        machine.onOverheat()
        assertTrue(machine.resumeAfterCooldown())
        assertEquals(SessionState.Checking, machine.state)
    }

    @Test
    fun `自环与越级转移被拒绝且状态不变`() {
        assertTrue(machine.start())
        assertFalse(machine.transitionTo(SessionState.Checking))      // 自环
        assertFalse(machine.transitionTo(SessionState.WifiConnecting)) // Checking 越级
        assertEquals(SessionState.Checking, machine.state)
        machine.transitionTo(SessionState.BleConnecting("GO 3S"))
        assertFalse(machine.transitionTo(SessionState.BleConnecting("other"))) // 自环
        assertEquals(SessionState.BleConnecting("GO 3S"), machine.state)
        machine.transitionTo(SessionState.WifiConnecting)
        machine.transitionTo(SessionState.Authorizing(AuthStatus.WAITING))
        machine.transitionTo(SessionState.Preparing)
        machine.transitionTo(SessionState.Streaming(streamParams(generation = 1)))
        // 流式自环不允许直接换参数：新代次必须经 Preparing 重进
        assertFalse(machine.transitionTo(SessionState.Streaming(streamParams(generation = 2))))
        assertEquals(1, (machine.state as SessionState.Streaming).params.streamGeneration)
    }

    @Test
    fun `Error 状态只能重试或停止，不直接回 Idle`() {
        reachStreaming()
        repeat(6) { machine.onDisconnected() }
        assertEquals(SessionState.Error(SessionError.RETRY_EXHAUSTED), machine.state)
        assertFalse(machine.transitionTo(SessionState.Idle))
        // 用户停止优先于一切：Error 下放弃也走停止清理
        assertTrue(machine.onUserStop())
        assertTrue(machine.transitionTo(SessionState.Idle))
    }

    @Test
    fun `过热后放弃冷却走停止`() {
        reachStreaming()
        assertTrue(machine.onOverheat())
        assertTrue(machine.onUserStop())
        assertEquals(SessionState.Stopping, machine.state)
        assertTrue(machine.transitionTo(SessionState.Idle))
    }

    @Test
    fun `停止后可完整重启第二会话`() {
        reachStreaming()
        machine.onUserStop()
        machine.transitionTo(SessionState.Idle)
        // 第二次会话从零开始，全路径可用
        reachStreaming()
        assertTrue(machine.state is SessionState.Streaming)
    }

    @Test
    fun `授权拒绝走停止不进重试`() {
        assertTrue(machine.start())
        machine.transitionTo(SessionState.BleConnecting("GO 3S"))
        machine.transitionTo(SessionState.WifiConnecting)
        machine.transitionTo(SessionState.Authorizing(AuthStatus.DENIED))
        assertTrue(machine.onUserStop())
        assertEquals(SessionState.Stopping, machine.state)
        assertFalse(machine.onDisconnected())
        machine.transitionTo(SessionState.Idle)
    }

    @Test
    @Ignore("P2：streamGeneration 递增与旧代次数据抑制在 CameraSession 集成层验证")
    fun `旧代次数据不推进新会话`() {
        throw NotImplementedError("P2 集成项：重连后旧代次帧/回调不得更新当前状态或句子")
    }
}
