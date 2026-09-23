package com.repovoyage.sign.capture

import android.content.Context

/**
 * 采集入口的 flavor 缝合：实现类在 training / production 源集同名提供
 * （`capture/CaptureEntryImpl`）。production 返回不可用——成品无采集入口、
 * 无采集服务代码（P8 验收）；UI 依 isAvailable 决定是否显示入口。
 */
interface CaptureEntry {
    val isAvailable: Boolean
    fun start(context: Context)
    fun stop(context: Context)
}
