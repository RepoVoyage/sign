package com.repovoyage.sign.recognition

import com.repovoyage.sign.sentence.RecognitionUpdate
import kotlinx.coroutines.flow.Flow

/**
 * 识别更新源的 flavor 缝合（与 capture/CaptureEntry 同模式）：实现类在
 * training / production 源集同名提供（`recognition/RecognitionSourceImpl`）。
 *
 * - training = 桩脚本源：模型（P5）就绪前驱动字幕/TTS/缓存全链路联调
 * - production = 真实 SignRecognizer（P6 适配器，依赖 P5 ModelSpec）；未提供
 *   前 isAvailable=false，管线拒绝启动，UI 按 §6 错误矩阵显示"模型未就绪"
 *   状态界面，不输出伪造结果
 */
interface RecognitionSource {
    /** 识别能力是否就绪；false 时管线不启动（ARCHITECTURE §6 错误矩阵） */
    val isAvailable: Boolean

    /** 识别源描述（界面状态行展示） */
    val sourceDescription: String

    val updates: Flow<RecognitionUpdate>

    fun start()
    fun stop()
}
