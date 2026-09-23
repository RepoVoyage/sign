package com.repovoyage.sign.camera

/**
 * 会话状态 → 用户可读文案（主界面与 FGS 通知共用）。
 * 初始文案，随真机验收打磨；不含热点/设备标识等敏感信息（§2.6 日志红线）。
 */
fun sessionStateText(state: SessionState): String = when (state) {
    SessionState.Idle -> "未启动"
    SessionState.Checking -> "检查中"
    is SessionState.BleConnecting -> "蓝牙连接中 ${state.deviceName ?: ""}"
    SessionState.WifiConnecting -> "Wi-Fi 连接中"
    is SessionState.Authorizing -> "相机授权中（${state.status}）"
    SessionState.Activating -> "激活中"
    SessionState.Preparing -> "准备取流"
    is SessionState.Streaming -> "取流中 ${state.params.width}x${state.params.height}@${state.params.fps}"
    is SessionState.Reconnecting -> "重连中（第 ${state.attempt} 次）"
    SessionState.Stopping -> "停止中"
    SessionState.PausedHot -> "过热暂停"
    is SessionState.Error -> "错误（${state.reason}）"
}
