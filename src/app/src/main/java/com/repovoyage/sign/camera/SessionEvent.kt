package com.repovoyage.sign.camera

/** API.md §1.2 — 会话向 UI 广播的一次性事件（契约照抄，语义以 Docs/API.md 为准） */
sealed interface SessionEvent {
    data class BatteryLow(val levelPercent: Int) : SessionEvent          // 震动 5 分钟限一次
    data class Overheat(val temperatureC: Int) : SessionEvent
    data class Disconnected(val cause: DisconnectCause) : SessionEvent   // 立即通知，进入 Reconnecting
    data class StreamOverload(val location: OverloadLocation) : SessionEvent
    data class ReconnectFailed(val attempt: Int) : SessionEvent          // 第 5 次触发长震
}

// API.md §1.2 引用但未定义成员；P2 按真机表现细化
enum class DisconnectCause { BLE_LINK_LOST, WIFI_LINK_LOST, SDK_ERROR, HEALTH_CHECK_TIMEOUT }

// API.md §2.4 引用但未定义成员；P3 取流落地时对齐
enum class OverloadLocation { ENCODE_ENTRY, ASSEMBLER, DECODER }
