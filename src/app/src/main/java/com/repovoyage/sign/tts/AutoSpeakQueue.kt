package com.repovoyage.sign.tts

import com.repovoyage.sign.sentence.LangCode

/** API.md §7 — 播报请求 */
data class SpeakRequest(
    val sessionId: String,
    val segmentId: String,
    val language: LangCode,
    val text: String,
    val orderKey: Long,                   // 源媒体时间序，按表达顺序播报
)

sealed interface EnqueueResult {
    data object Queued : EnqueueResult
    data object Duplicate : EnqueueResult    // 自动播报键 (sessionId, segmentId, language) 已存在
    data class Rejected(val reason: RejectReason) : EnqueueResult
}

enum class RejectReason { QUEUE_FULL, VOICE_NOT_READY }

/**
 * 自动播报队列纯逻辑（API.md §7 初始值）：串行一次一条、待播缓存 3 句、
 * 去重键 (sessionId, segmentId, language) 会话内终身有效、
 * 进入待播 5 秒未开始 → 未播报。时钟（monoMs）由调用方注入。
 */
class AutoSpeakQueue(
    private val capacity: Int = 3,
    private val unspokenAfterMs: Long = 5_000,
) {

    private data class SpeakKey(val sessionId: String, val segmentId: String, val language: LangCode)

    private class Entry(val request: SpeakRequest, val enqueuedAtMonoMs: Long)

    /** 待播（尚未开始），按 orderKey 升序（源媒体时间序，按表达顺序播报） */
    private val waiting = mutableListOf<Entry>()

    /** 正在播报的一条（串行一次一条） */
    private var current: SpeakRequest? = null

    /** 去重键会话内终身有效：播报过的键再次入队仍判重复 */
    private val seenKeys = mutableSetOf<SpeakKey>()

    /** 待播是否有积压（TtsManager ticker 保活判断用） */
    val hasBacklog: Boolean
        get() = waiting.isNotEmpty()

    fun enqueue(request: SpeakRequest, nowMonoMs: Long, bypassDedup: Boolean = false): EnqueueResult {
        if (waiting.size >= capacity) return EnqueueResult.Rejected(RejectReason.QUEUE_FULL)
        if (!bypassDedup && !seenKeys.add(SpeakKey(request.sessionId, request.segmentId, request.language))) {
            return EnqueueResult.Duplicate
        }
        waiting += Entry(request, nowMonoMs)
        waiting.sortBy { it.request.orderKey }
        return EnqueueResult.Queued
    }

    /** 取队头开始播报；上一条未结束时返回 null（串行一次一条） */
    fun pollNext(): SpeakRequest? {
        if (current != null) return null
        val next = waiting.removeFirstOrNull() ?: return null
        current = next.request
        return next.request
    }

    /** 当前播报结束，允许下一条 */
    fun onPlaybackFinished() {
        current = null
    }

    /** 用户停止/显式重播：清空待播（不产生事件；正在播报的由引擎回调终结） */
    fun clear() {
        waiting.clear()
    }

    /**
     * 进入待播超过 5 秒仍未开始的请求 → 转未播报：从待播队列移除并返回
     * （已开始播报的条目不在待播队列，自然不参与）。
     */
    fun checkOverdue(nowMonoMs: Long): List<SpeakRequest> {
        val overdue = waiting.filter { nowMonoMs - it.enqueuedAtMonoMs >= unspokenAfterMs }
        if (overdue.isNotEmpty()) waiting.removeAll(overdue)
        return overdue.map { it.request }
    }
}
