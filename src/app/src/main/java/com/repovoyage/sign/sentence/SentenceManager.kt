package com.repovoyage.sign.sentence

/**
 * P6 句子段状态机（API.md §5 / ARCHITECTURE.md §2.4）。纯同步领域对象：
 * 无时钟、无协程——Finalizing 后的收尾时限由上层驱动（收 [SentenceEvent.Finalizing]
 * 后等【初始值】2s 再调 [finalize]），迟到结果丢弃同样以调用顺序表达。
 *
 * 规则：重叠窗口更新同段草稿（增 revision）；segmentId=null 开新段，识别器
 * 自带 id 的未知段同样纳入（跨段并发推进）；sequenceEpoch 前进中断旧 epoch
 * 全部活跃段，旧 epoch 的迟到提交整体丢弃；FINAL/INTERRUPTED/DISCARDED 段
 * 不再接受更新（迟到推理不覆盖冻结内容）；UNCERTAIN 边界转待核对不阻塞
 * 后续表达，后续可靠边界可将其送入收尾。
 */
class SentenceManager(
    private val sessionId: String,
    private val streamGeneration: Long,
) {
    private val segments = mutableMapOf<String, Segment>()

    /** 当前 sequenceEpoch 基线；-1 = 尚未收到任何更新，首个 update 建立基线 */
    private var currentEpoch = -1L
    private var nextSegmentNumber = 0

    private class Segment(
        val id: String,
        val epoch: Long,
        var revision: Int,
        var draftText: String,
        var confidence: Float?,
        var state: SegmentState,
    )

    /** 提交一次识别更新，返回该步产生的全部事件（可能为空 = 结果过期被丢弃） */
    fun submit(update: RecognitionUpdate): List<SentenceEvent> {
        if (update.sequenceEpoch < currentEpoch) return emptyList()   // 旧 epoch 迟到结果：过期
        val events = mutableListOf<SentenceEvent>()
        if (update.sequenceEpoch > currentEpoch) {
            currentEpoch = update.sequenceEpoch
            // 新 epoch：旧 epoch 的活跃段全部中断（含收尾中/待核对）
            segments.values
                .filter { it.epoch < currentEpoch && it.state.isLive }
                .forEach {
                    it.state = SegmentState.INTERRUPTED
                    events += SentenceEvent.Interrupted(it.id, InterruptCause.EPOCH_ADVANCED)
                }
        }
        val segment = resolveSegment(update) ?: return events
        if (!segment.state.isLive) return events                       // 冻结/中断/已放弃：迟到不覆盖
        segment.revision += 1
        segment.draftText = update.draftText
        segment.confidence = update.confidence
        events += SentenceEvent.DraftUpdated(segment.id, segment.revision, segment.draftText)
        when (update.boundary?.reliability) {
            BoundaryReliability.RELIABLE -> {
                segment.state = SegmentState.FINALIZING
                events += SentenceEvent.Finalizing(segment.id)
            }
            BoundaryReliability.UNCERTAIN -> {
                segment.state = SegmentState.NEEDS_CONFIRMATION
                events += SentenceEvent.NeedsConfirmation(segment.id, ConfirmReason.UNCERTAIN_BOUNDARY)
            }
            null -> Unit
        }
        return events
    }

    /** 冻结段（收尾窗口期满或用户确认）：非活跃段/未知段返回空（迟到收尾丢弃） */
    fun finalize(
        segmentId: String, startPtsUs: Long, endPtsUs: Long, userConfirmed: Boolean = false,
    ): List<SentenceEvent> {
        val segment = segments[segmentId] ?: return emptyList()
        if (!segment.state.isLive) return emptyList()
        segment.state = SegmentState.FINAL
        return listOf(
            SentenceEvent.Final(
                ConfirmedSentence(
                    sessionId = sessionId,
                    streamGeneration = streamGeneration,
                    sequenceEpoch = segment.epoch,
                    segmentId = segment.id,
                    revision = segment.revision,
                    startPtsUs = startPtsUs,
                    endPtsUs = endPtsUs,
                    rawChinese = segment.draftText,
                    confidence = segment.confidence,
                    userConfirmed = userConfirmed,
                ),
            ),
        )
    }

    /** 用户放弃待核对项；仅对 NEEDS_CONFIRMATION 段生效 */
    fun discard(segmentId: String): List<SentenceEvent> {
        val segment = segments[segmentId] ?: return emptyList()
        if (segment.state != SegmentState.NEEDS_CONFIRMATION) return emptyList()
        segment.state = SegmentState.DISCARDED
        return listOf(SentenceEvent.Discarded(segmentId))
    }

    /** 识别源发现缺失切片时，撤下该段草稿，防止旧词残留在字幕中。 */
    fun discardIncomplete(segmentId: String): List<SentenceEvent> {
        val segment = segments[segmentId] ?: return emptyList()
        if (!segment.state.isLive) return emptyList()
        segment.state = SegmentState.DISCARDED
        return listOf(SentenceEvent.Discarded(segmentId))
    }

    // ---------------------------------------------------------------- 内部

    /** segmentId=null 生成新段；非 null 未见过则按识别器分段的段 id 纳入 */
    private fun resolveSegment(update: RecognitionUpdate): Segment? {
        val id = update.segmentId ?: "seg-${++nextSegmentNumber}"
        return segments.getOrPut(id) {
            Segment(id, currentEpoch, 0, "", update.confidence, SegmentState.DRAFT)
        }
    }

    private val SegmentState.isLive: Boolean
        get() = this == SegmentState.DRAFT || this == SegmentState.FINALIZING ||
            this == SegmentState.NEEDS_CONFIRMATION
}
