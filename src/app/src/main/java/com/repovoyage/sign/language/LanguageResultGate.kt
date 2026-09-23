package com.repovoyage.sign.language

import com.repovoyage.sign.sentence.LangCode

/** 结果来源（2026-09-23 用户决定仅云端：本地引擎砍掉，LOCAL/FALLBACK 枚举值移除） */
enum class OutputSource { CLOUD, USER }   // USER = 人工核对修正（仅纠错路径产生）
enum class OutputStatus { READY, NEEDS_CONFIRMATION, UNAVAILABLE }

/** API.md §6.1 — 语言处理结果 */
data class LanguageResult(
    val sessionId: String,
    val streamGeneration: Long,
    val sequenceEpoch: Long,
    val segmentId: String,
    val sentenceRevision: Int,
    val settingsRevision: Long,
    val language: LangCode,
    val text: String?,                        // UNAVAILABLE 时为 null
    val status: OutputStatus,
    val source: OutputSource,
    val elapsedMs: Long,
)

/**
 * 语言结果门（API.md §6.2）：去重键 (sessionId, segmentId, sentenceRevision, language)
 * 仅保留一个有效结果；settingsRevision 落后于已见快照的迟到结果拒绝。
 */
class LanguageResultGate {

    private data class DedupKey(
        val sessionId: String,
        val segmentId: String,
        val revision: Int,
        val language: LangCode,
    )

    private val acceptedKeys = mutableSetOf<DedupKey>()
    private var latestSettingsRevision = Long.MIN_VALUE

    fun accept(result: LanguageResult): Boolean {
        if (result.settingsRevision < latestSettingsRevision) return false
        latestSettingsRevision = maxOf(latestSettingsRevision, result.settingsRevision)
        return acceptedKeys.add(
            DedupKey(result.sessionId, result.segmentId, result.sentenceRevision, result.language),
        )
    }
}
