package com.repovoyage.sign.history

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/**
 * [SentenceCache] 的 Room 实现（API.md §8）：合并 = 主键 upsert 整行替换；
 * 历史 = 句子/语言结果两表观察流组合（≤ 万条量级，内存分组足够）。
 * 缓存开关的写入拦截由持有方按 [com.repovoyage.sign.settings.AppSettings]
 * .cacheEnabled 门控（§2.6：关闭后停止写入新记录）。
 */
class RoomSentenceCache(private val dao: SentenceDao) : SentenceCache {

    override suspend fun upsertSentence(record: SentenceRecord) = dao.upsertSentence(record)

    override suspend fun mergeLanguageResult(record: LanguageResultRecord) = dao.upsertResult(record)

    override fun observeHistory(): Flow<List<SentenceWithResults>> =
        combine(dao.observeSentences(), dao.observeAllResults()) { sentences, results ->
            val bySentence = results.groupBy { it.sessionId to it.segmentId }
            sentences.map { s -> SentenceWithResults(s, bySentence[s.sessionId to s.segmentId].orEmpty()) }
        }

    override suspend fun deleteBySegment(sessionId: String, segmentId: String) =
        dao.deleteSentence(sessionId, segmentId)

    override suspend fun deleteBySession(sessionId: String) = dao.deleteSession(sessionId)

    override suspend fun deleteAll() {
        dao.deleteAllSentences()
        dao.deleteAllResults()
    }
}
