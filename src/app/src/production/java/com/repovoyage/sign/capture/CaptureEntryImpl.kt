package com.repovoyage.sign.capture

import android.content.Context

/** production：无采集入口（P8 验收：无入口/无端口/无落盘），实现为空 */
object CaptureEntryImpl : CaptureEntry {
    override val isAvailable: Boolean = false

    override fun start(context: Context) {}

    override fun stop(context: Context) {}
}
