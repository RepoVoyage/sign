package com.repovoyage.sign.sentence

/** API.md §0.2/§5 — 语言标签（BCP 47 子集，如 zh-CN / en-US） */
@JvmInline
value class LangCode(val tag: String) {
    override fun toString(): String = tag
}

enum class SegmentState { DRAFT, FINALIZING, FINAL, NEEDS_CONFIRMATION, INTERRUPTED, DISCARDED }

enum class BoundaryReliability { RELIABLE, UNCERTAIN }
enum class BoundarySource { MODEL, TEMPORAL_MODULE }   // 占位：随 P5 边界方案定稿细化
enum class ConfirmReason { UNCERTAIN_BOUNDARY, FIDELITY_ISSUE }
enum class InterruptCause { STREAM_LOST, EPOCH_ADVANCED, USER_STOP, OVERLOAD }

/** API.md §4.2 — 自动边界信号（发布门槛） */
data class BoundarySignal(
    val cutoffPtsUs: Long,
    val requiredFutureContextUs: Long,
    val reliability: BoundaryReliability,
    val source: BoundarySource,
)

/** API.md §4.1 — 识别更新 */
data class RecognitionUpdate(
    val sequenceEpoch: Long,
    val segmentId: String?,               // 无既有段归属时为 null（开新段）
    val draftText: String,
    val tokenSpans: List<TokenSpan>? = null,
    val confidence: Float? = null,
    val boundary: BoundarySignal? = null,
)

data class TokenSpan(
    val text: String,
    val startPtsUs: Long,
    val endPtsUs: Long,
    val stable: Boolean,
)

/** API.md §5.1 — 段事件 */
sealed interface SentenceEvent {
    data class DraftUpdated(val segmentId: String, val revision: Int, val draftText: String) : SentenceEvent
    data class Finalizing(val segmentId: String) : SentenceEvent
    data class Final(val sentence: ConfirmedSentence) : SentenceEvent
    data class NeedsConfirmation(val segmentId: String, val reason: ConfirmReason) : SentenceEvent
    data class Interrupted(val segmentId: String, val cause: InterruptCause) : SentenceEvent
    data class Discarded(val segmentId: String) : SentenceEvent
}

/** API.md §5.2 — FINAL 冻结的确认句（LLM 唯一合法输入是 rawChinese） */
data class ConfirmedSentence(
    val sessionId: String,
    val streamGeneration: Long,
    val sequenceEpoch: Long,
    val segmentId: String,
    val revision: Int,
    val startPtsUs: Long,                 // 前闭后开
    val endPtsUs: Long,
    val rawChinese: String,
    val confidence: Float?,
    val userConfirmed: Boolean,
)
