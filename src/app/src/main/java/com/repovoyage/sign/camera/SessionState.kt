package com.repovoyage.sign.camera

/** API.md §1.1 — UI 观察的唯一状态源（契约照抄，语义以 Docs/API.md 为准） */
sealed interface SessionState {
    data object Idle : SessionState
    data object Checking : SessionState                                   // 权限/蓝牙/模式检查
    data class BleConnecting(val deviceName: String?) : SessionState
    data object WifiConnecting : SessionState                             // 系统流程 + bindProcessToNetwork
    data class Authorizing(val status: AuthStatus) : SessionState         // 等待相机授权/忙碌/拒绝
    data object Activating : SessionState                                 // 需公网，仅首次
    data object Preparing : SessionState                                  // loadJson/能力查询/参数集
    data class Streaming(val params: StreamParams) : SessionState
    data class Reconnecting(val attempt: Int, val nextRetryInMs: Long) : SessionState
    data object Stopping : SessionState
    data object PausedHot : SessionState
    data class Error(val reason: SessionError) : SessionState
}

data class StreamParams(
    val width: Int,
    val height: Int,
    val fps: Int,
    val encodeType: VideoEncodeType,
    val streamGeneration: Long,
)

// API.md §1.1 引用但未定义成员细节；P2 对齐 SDK 实际取值后细化
enum class VideoEncodeType { H264, H265 }
enum class AuthStatus { WAITING, BUSY, DENIED }
enum class SessionError { PERMISSION_DENIED, RETRY_EXHAUSTED, UNRECOVERABLE }
