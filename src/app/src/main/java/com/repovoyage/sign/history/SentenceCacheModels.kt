package com.repovoyage.sign.history

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import com.repovoyage.sign.language.LanguageResult
import com.repovoyage.sign.sentence.ConfirmedSentence
import com.repovoyage.sign.sentence.LangCode
import kotlinx.coroutines.flow.Flow

/**
 * P7 文本缓存域层（API.md §8）。数据结构与行为语义（映射/合并/导出）；
 * Room 落库（@Entity/DAO/export 分享机制）随字幕 UI 阶段接线——届时
 * 墙钟时间来源、模型版本回填与真机验证一并处理。保留策略见
 * [SentenceCacheRetention]（90 天/万条）。
 */

/** 历史句子记录（复合主键 sessionId + segmentId） */
@Entity(tableName = "sentences", primaryKeys = ["sessionId", "segmentId"])
data class SentenceRecord(
    val sessionId: String,
    val segmentId: String,
    val streamGeneration: Long,
    val sequenceEpoch: Long,
    val revision: Int,
    val startPtsUs: Long,
    val endPtsUs: Long,
    val wallTimeStart: Long,                 // 展示用墙上时间
    val wallTimeEnd: Long,
    val rawChinese: String,
    val confidence: Float?,
    val userConfirmed: Boolean,
    val modelVersion: String,                // 模型未定（P5）：占位空串，随 ModelSpec 回填
    val specChecksum: String,
)

/** 语言结果记录（复合主键 sessionId + segmentId + language）；随句子级联删除 */
@Entity(
    tableName = "language_results",
    primaryKeys = ["sessionId", "segmentId", "language"],
    foreignKeys = [ForeignKey(
        entity = SentenceRecord::class,
        parentColumns = ["sessionId", "segmentId"],
        childColumns = ["sessionId", "segmentId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("sessionId", "segmentId")],
)
data class LanguageResultRecord(
    val sessionId: String,
    val segmentId: String,
    val language: LangCode,
    val sentenceRevision: Int,
    val settingsRevision: Long,
    val text: String?,                       // UNAVAILABLE 时为 null，不得伪装正常译文
    val status: String,
    val source: String,
    val processedAtWallMs: Long,
)

data class SentenceWithResults(
    val sentence: SentenceRecord,
    val results: List<LanguageResultRecord>,
)

/**
 * 缓存门面（Room 实现随 UI 阶段落地；保留策略由持有方按
 * [SentenceCacheRetention] 定期套用 delete*）。
 */
interface SentenceCache {
    suspend fun upsertSentence(record: SentenceRecord)
    /** 同 (sessionId, segmentId) 按语言合并替换；只存 FINAL 结果，不存草稿版本 */
    suspend fun mergeLanguageResult(record: LanguageResultRecord)
    fun observeHistory(): Flow<List<SentenceWithResults>>
    suspend fun deleteBySegment(sessionId: String, segmentId: String)
    suspend fun deleteBySession(sessionId: String)
    suspend fun deleteAll()
}

// ---------------------------------------------------------------- 映射

/** FINAL 冻结句 → 记录（墙钟时间由调用方在 FINAL 时刻捕获） */
fun ConfirmedSentence.toSentenceRecord(wallTimeStartMs: Long, wallTimeEndMs: Long) = SentenceRecord(
    sessionId = sessionId,
    segmentId = segmentId,
    streamGeneration = streamGeneration,
    sequenceEpoch = sequenceEpoch,
    revision = revision,
    startPtsUs = startPtsUs,
    endPtsUs = endPtsUs,
    wallTimeStart = wallTimeStartMs,
    wallTimeEnd = wallTimeEndMs,
    rawChinese = rawChinese,
    confidence = confidence,
    userConfirmed = userConfirmed,
    modelVersion = "",
    specChecksum = "",
)

/** 语言结果 → 记录（status/source 字符串化；UNAVAILABLE 保持 text=null） */
fun LanguageResult.toLanguageResultRecord(processedAtWallMs: Long) = LanguageResultRecord(
    sessionId = sessionId,
    segmentId = segmentId,
    language = language,
    sentenceRevision = sentenceRevision,
    settingsRevision = settingsRevision,
    text = text,
    status = status.name,
    source = source.name,
    processedAtWallMs = processedAtWallMs,
)

// ---------------------------------------------------------------- 合并

/**
 * mergeLanguageResult 的纯语义：同 (sessionId, segmentId, language) 替换、
 * 其余保留；列表有序稳定（新记录接替原位置）。
 */
fun mergeLanguageResult(
    existing: List<LanguageResultRecord>,
    incoming: LanguageResultRecord,
): List<LanguageResultRecord> {
    var replaced = false
    val out = existing.map {
        if (it.sessionId == incoming.sessionId && it.segmentId == incoming.segmentId &&
            it.language == incoming.language
        ) {
            replaced = true
            incoming
        } else {
            it
        }
    }
    return if (replaced) out else out + incoming
}
