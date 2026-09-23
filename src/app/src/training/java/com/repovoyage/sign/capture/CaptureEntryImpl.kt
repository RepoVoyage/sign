package com.repovoyage.sign.capture

import android.content.Context
import com.repovoyage.sign.usb.UsbCaptureService

/** training：启动/停止 USB 采集前台服务 */
object CaptureEntryImpl : CaptureEntry {
    override val isAvailable: Boolean = true

    override fun start(context: Context) = UsbCaptureService.start(context)

    override fun stop(context: Context) = UsbCaptureService.stop(context)
}
