package com.repovoyage.sign.camera

import com.arashivision.inskmp.insble.data.BleDeviceCore
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * API.md §1.2 — 相机会话门面：UI 只观察 state/events，不直接触碰 SDK。
 *
 * 与 API.md 的偏差：`start(bleDevice: DiscoveredCamera)` 中 DiscoveredCamera
 * 成员未在文档定义，P2 暂以 SDK 扫描结果 BleDeviceCore 直传（扫描由 UI 层
 * 单独负责，不属会话职责），真机联调后再对齐。
 */
interface CameraSession {
    val state: StateFlow<SessionState>
    val events: SharedFlow<SessionEvent>

    suspend fun start(bleDevice: BleDeviceCore)
    suspend fun stop()                      // 用户停止，优先于一切延迟重试
    suspend fun resumeAfterCooldown()       // PausedHot → 用户确认后
}
