package com.repovoyage.sign.recognition

import com.repovoyage.sign.sentence.RecognitionUpdate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

/**
 * production：识别能力随 P5 模型 / P6 SignRecognizer 适配器交付；当前未提供。
 * isAvailable=false → 管线不启动、UI 显示模型未就绪状态界面（§6 错误矩阵：
 * 禁止开始连续翻译，不输出伪造结果，不加逐句按钮兜底）。
 */
object RecognitionSourceImpl : RecognitionSource {
    override val isAvailable: Boolean = false

    override val sourceDescription: String = "识别源：云端服务未接线（P6）"
    override val updates: Flow<RecognitionUpdate> = emptyFlow()
    override fun start() {}
    override fun stop() {}
}
