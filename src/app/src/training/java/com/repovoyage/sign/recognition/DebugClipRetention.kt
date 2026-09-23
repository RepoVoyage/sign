package com.repovoyage.sign.recognition

import android.content.Context
import java.io.File

/**
 * training：留存最近上传的识别切片（cacheDir/clips_debug，滚动保留 5 个），
 * 供联调验尸（adb 拉取后 ffprobe/ffmpeg 检查容器与可解码帧数）。
 * 帧内容落盘仅 training（与 P4 采集通道同一先例）；production 返回 null 不留存。
 */
object DebugClipRetention {
    fun dir(context: Context): File? = File(context.cacheDir, "clips_debug")
}
