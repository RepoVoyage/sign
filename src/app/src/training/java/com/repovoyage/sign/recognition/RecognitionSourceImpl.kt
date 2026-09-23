package com.repovoyage.sign.recognition

import com.repovoyage.sign.sentence.BoundaryReliability
import com.repovoyage.sign.sentence.BoundarySignal
import com.repovoyage.sign.sentence.BoundarySource
import com.repovoyage.sign.sentence.RecognitionUpdate
import com.repovoyage.sign.sentence.TokenSpan
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * training：桩识别源——循环播放脚本句（每句 3 次草稿修订 → RELIABLE 边界 +
 * 句子置信度），供模型就绪前联调字幕/TTS/缓存全链路。每次 start() 递增 epoch
 * （旧段随新 epoch 中断，符合 P6 语义）。仅 training flavor 存在；
 * production 无桩（P8 验收：成品不输出伪造结果）。
 */
object RecognitionSourceImpl : RecognitionSource {

    override val isAvailable: Boolean = true

    override val sourceDescription: String = "识别源：桩源（训练调试用）"

    private val _updates = MutableSharedFlow<RecognitionUpdate>(extraBufferCapacity = 64)
    override val updates: Flow<RecognitionUpdate> = _updates

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private var epoch = 0L
    private var segmentSeq = 0

    override fun start() {
        if (job?.isActive == true) return
        epoch++
        job = scope.launch {
            while (isActive) {
                for (scripted in SCRIPT) {
                    emitSentence(scripted)
                    delay(PAUSE_MS)
                }
            }
        }
    }

    override fun stop() {
        job?.cancel()
        job = null
    }

    private suspend fun emitSentence(scripted: ScriptedSentence) {
        val seg = "stub-seg-${++segmentSeq}"
        val basePts = segmentSeq * SPAN_PTS_US
        scripted.drafts.forEachIndexed { index, draft ->
            _updates.emit(
                RecognitionUpdate(
                    sequenceEpoch = epoch,
                    segmentId = seg,
                    draftText = draft,
                    tokenSpans = listOf(
                        TokenSpan(draft, basePts, basePts + SPAN_PTS_US, stable = index == scripted.drafts.lastIndex),
                    ),
                    confidence = null,
                    boundary = null,
                ),
            )
            delay(REVISION_MS)
        }
        // 2026-09-23 用户流程：边界一律 RELIABLE（CV 侧不确定由组合 LLM 消化）；
        // 待核实唯一识别侧来源 = 句子置信度 < 0.7（管线 submit 降级为 UNCERTAIN）
        _updates.emit(
            RecognitionUpdate(
                sequenceEpoch = epoch,
                segmentId = seg,
                draftText = scripted.drafts.last(),
                tokenSpans = listOf(TokenSpan(scripted.drafts.last(), basePts, basePts + SPAN_PTS_US, stable = true)),
                confidence = scripted.confidence,
                boundary = BoundarySignal(
                    cutoffPtsUs = basePts + SPAN_PTS_US + 500_000,
                    requiredFutureContextUs = 500_000,
                    reliability = BoundaryReliability.RELIABLE,
                    source = BoundarySource.MODEL,
                ),
            ),
        )
    }

    private data class ScriptedSentence(val drafts: List<String>, val confidence: Float)

    private val SCRIPT = listOf(
        ScriptedSentence(listOf("我", "我需要", "我需要帮助"), 0.92f),
        ScriptedSentence(listOf("请", "请跟我", "请跟我来"), 0.88f),
        ScriptedSentence(listOf("今天", "今天天气", "今天天气很好"), 0.9f),
        // 低置信路径：0.55 < 阈值 0.7 → 待核实标志 + 震动（唯一触发情形演示）
        ScriptedSentence(listOf("可能", "可能是", "可能是低电量"), 0.55f),
        ScriptedSentence(listOf("谢谢", "谢谢你"), 0.91f),
        // 保真样本（plan.md P7 验收：否定/数字不得被 LLM 整理改写语义）
        ScriptedSentence(listOf("请", "请不要", "请不要碰我的头"), 0.9f),
        ScriptedSentence(listOf("我有", "我有3个", "我有3个孩子"), 0.89f),
        ScriptedSentence(listOf("我", "我没说", "我没说明天去"), 0.9f),
        ScriptedSentence(listOf("药", "药每次", "药每次吃2片"), 0.88f),
    )

    private const val PAUSE_MS = 3_000L
    private const val REVISION_MS = 600L
    private const val SPAN_PTS_US = 2_000_000L
}
