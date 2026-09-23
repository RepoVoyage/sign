package com.repovoyage.sign.camera

/**
 * 会话状态机（ARCHITECTURE.md §2.1.3）：纯逻辑、无 SDK/协程依赖。
 * CameraSession 驱动本机并在 StateFlow 上广播状态；streamGeneration 的递增
 * 与旧代次数据抑制在 CameraSession 集成层处理，不在此处。
 *
 * 规则：用户停止/权限拒绝/过热不进入断线重试；用户停止优先于任何延迟重试。
 * 被动断连的触发面覆盖"已进入系统热点连接"的连接态（Streaming/Reconnecting/
 * WifiConnecting/Authorizing/Preparing）：断连监听在整个连接期间就已注册，
 * SDK 内部同步阶段的断连不能被无声丢弃（否则会话永远卡在连接中）；重连尝试
 * 中（WifiConnecting 起）的失败同样消耗重试名额，否则退避循环无法推进。
 */
class SessionStateMachine(
    private val reconnectPolicy: ReconnectPolicy = ReconnectPolicy(),
) {

    var state: SessionState = SessionState.Idle
        private set

    /** 重试次数与退避序列一一对应：每消费一个退避延迟计一次，成功出图清零 */
    private var reconnectAttempts = 0

    /** 开始会话：Idle → Checking */
    fun start(): Boolean = transitionTo(SessionState.Checking)

    /** 非主动断连：退避重连，重试耗尽 → Error(RETRY_EXHAUSTED) */
    fun onDisconnected(): Boolean {
        val from = state
        val reconnectable = from is SessionState.Streaming ||
            from is SessionState.Reconnecting ||
            from is SessionState.WifiConnecting ||
            from is SessionState.Authorizing ||
            from is SessionState.Preparing
        if (!reconnectable) return false
        val delayMs = reconnectPolicy.onDisconnected() ?: run {
            state = SessionState.Error(SessionError.RETRY_EXHAUSTED)
            return true
        }
        reconnectAttempts += 1
        state = SessionState.Reconnecting(reconnectAttempts, delayMs)
        return true
    }

    /** 用户停止/权限拒绝：任何状态 → Stopping（优先于延迟重试） */
    fun onUserStop(): Boolean = transitionTo(SessionState.Stopping)

    /** 相机过热：Streaming → PausedHot */
    fun onOverheat(): Boolean {
        if (state !is SessionState.Streaming) return false
        state = SessionState.PausedHot
        return true
    }

    /** 冷却后用户确认重新开始：仅 PausedHot → Checking */
    fun resumeAfterCooldown(): Boolean {
        if (state != SessionState.PausedHot) return false
        return transitionTo(SessionState.Checking)
    }

    /** 通用合法转移：合法则更新状态并返回 true；非法保持原状态返回 false */
    fun transitionTo(candidate: SessionState): Boolean {
        if (!isLegal(state, candidate)) return false
        if (candidate is SessionState.Streaming) {
            reconnectPolicy.onStreamRecovered()
            reconnectAttempts = 0
        }
        if (candidate is SessionState.Checking) {
            // 进入 Checking 均为用户发起的新一轮会话（start/resumeAfterCooldown/
            // Error 后重试）：重试预算随之重置，不残留上一会话的耗尽状态。
            // 成功出图（→ Streaming）是运行期内的第二个重置点（API.md §1.2）。
            reconnectPolicy.onStreamRecovered()
            reconnectAttempts = 0
        }
        state = candidate
        return true
    }

    /** §2.1.3 状态图的转移合法性 */
    private fun isLegal(from: SessionState, to: SessionState): Boolean = when (from) {
        SessionState.Idle -> to == SessionState.Checking
        SessionState.Checking -> to is SessionState.BleConnecting ||
            to == SessionState.Stopping || to is SessionState.Error
        is SessionState.BleConnecting -> to == SessionState.WifiConnecting ||
            to == SessionState.Stopping || to is SessionState.Error
        SessionState.WifiConnecting -> to is SessionState.Authorizing ||
            to == SessionState.Stopping || to is SessionState.Error
        is SessionState.Authorizing -> to == SessionState.Activating ||
            to == SessionState.Preparing || to == SessionState.Stopping || to is SessionState.Error
        SessionState.Activating -> to == SessionState.Preparing ||
            to == SessionState.Stopping || to is SessionState.Error
        SessionState.Preparing -> to is SessionState.Streaming ||
            to == SessionState.Stopping || to is SessionState.Error
        is SessionState.Streaming -> to is SessionState.Reconnecting ||
            to == SessionState.PausedHot || to == SessionState.Stopping || to is SessionState.Error
        is SessionState.Reconnecting -> to == SessionState.WifiConnecting ||
            to == SessionState.Stopping || to is SessionState.Error
        SessionState.Stopping -> to == SessionState.Idle
        SessionState.PausedHot -> to == SessionState.Checking || to == SessionState.Stopping
        is SessionState.Error -> to == SessionState.Checking || to == SessionState.Stopping
    }
}
