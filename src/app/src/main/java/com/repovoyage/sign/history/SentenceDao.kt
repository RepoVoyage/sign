package com.repovoyage.sign.history

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** 保留策略扫描行：句子主键 + 最近处理墙钟（无语言结果时回退句子 wallTimeEnd） */
data class SentenceAgeRow(
    val sessionId: String,
    val segmentId: String,
    val lastProcessedAtWallMs: Long,
)

@Dao
interface SentenceDao {

    @Upsert
    suspend fun upsertSentence(record: SentenceRecord)

    /** 主键 (sessionId, segmentId, language) 冲突即整行替换 = mergeLanguageResult 语义 */
    @Upsert
    suspend fun upsertResult(record: LanguageResultRecord)

    @Query("SELECT * FROM sentences ORDER BY wallTimeStart DESC")
    fun observeSentences(): Flow<List<SentenceRecord>>

    @Query("SELECT * FROM language_results")
    fun observeAllResults(): Flow<List<LanguageResultRecord>>

    @Query(
        """
        SELECT s.sessionId AS sessionId, s.segmentId AS segmentId,
               COALESCE(MAX(r.processedAtWallMs), s.wallTimeEnd) AS lastProcessedAtWallMs
        FROM sentences s
        LEFT JOIN language_results r
               ON r.sessionId = s.sessionId AND r.segmentId = s.segmentId
        GROUP BY s.sessionId, s.segmentId
        """,
    )
    suspend fun sentenceAges(): List<SentenceAgeRow>

    /** 语言结果随外键 CASCADE 级联删除 */
    @Query("DELETE FROM sentences WHERE sessionId = :sessionId AND segmentId = :segmentId")
    suspend fun deleteSentence(sessionId: String, segmentId: String)

    @Query("DELETE FROM sentences WHERE sessionId = :sessionId")
    suspend fun deleteSession(sessionId: String)

    @Query("DELETE FROM sentences")
    suspend fun deleteAllSentences()

    @Query("DELETE FROM language_results")
    suspend fun deleteAllResults()

    /**
     * 清除 status=UNAVAILABLE 的结果行（2026-09-24：LLM 配置模块移除前的润色
     * 失败残留——无文本、纯噪音，且未配置凭据时语言处理不再运行、永不会更新）。
     * 仅在未配置 LLM 凭据时调用；已配置时保留真实失败标记。
     */
    @Query("DELETE FROM language_results WHERE status = 'UNAVAILABLE'")
    suspend fun deleteUnavailableResults(): Int
}
