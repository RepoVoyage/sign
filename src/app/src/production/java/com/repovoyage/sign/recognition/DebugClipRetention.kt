package com.repovoyage.sign.recognition

import android.content.Context
import java.io.File

/** production：帧内容不落盘（ARCHITECTURE §2.6）；无调试留存 */
object DebugClipRetention {
    fun dir(context: Context): File? = null
}
