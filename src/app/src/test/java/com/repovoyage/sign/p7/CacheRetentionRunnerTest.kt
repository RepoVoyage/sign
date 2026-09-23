package com.repovoyage.sign.p7

import com.repovoyage.sign.history.LanguageResultRecord
import com.repovoyage.sign.history.SentenceAgeRow
import com.repovoyage.sign.history.SentenceDao
import com.repovoyage.sign.history.SentenceRecord
import com.repovoyage.sign.history.applyRetentionPolicy
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 只实现保留策略用到的面；其余方法为空实现 */
private class FakeDao(private val ages: List<SentenceAgeRow>) : SentenceDao {
    val deleted = mutableListOf<Pair<String, String>>()
    override suspend fun upsertSentence(record: SentenceRecord) {}
    override suspend fun upsertResult(record: LanguageResultRecord) {}
    override fun observeSentences(): Flow<List<SentenceRecord>> = MutableStateFlow(emptyList())
    override fun observeAllResults(): Flow<List<LanguageResultRecord>> = MutableStateFlow(emptyList())
    override suspend fun sentenceAges(): List<SentenceAgeRow> = ages
    override suspend fun deleteSentence(sessionId: String, segmentId: String) {
        deleted += sessionId to segmentId
    }
    override suspend fun deleteSession(sessionId: String) {}
    override suspend fun deleteAllSentences() {}
    override suspend fun deleteAllResults() {}
}

/**
 * 保留策略 runner（CacheRetentionRunner）：域层 evict 判定已有 CacheRetentionTest
 * 覆盖，这里验证 Room 扫描行 → 域记录 → 按段删除的适配（含复合键编解码）。
 */
class CacheRetentionRunnerTest {

    private val now = 1_000_000_000L
    private val dayMs = 24 * 3600 * 1000L

    @Test
    fun `超龄段删除且复合键正确拆回`() = runBlocking {
        val dao = FakeDao(
            listOf(
                SentenceAgeRow("s1", "m1", now - 91 * dayMs),
                SentenceAgeRow("s1", "m2", now - dayMs),
            ),
        )
        applyRetentionPolicy(dao, now)
        assertEquals(listOf("s1" to "m1"), dao.deleted)
    }

    @Test
    fun `超量仅删最老一条回到限内`() = runBlocking {
        val rows = (1..10_001).map { SentenceAgeRow("s1", "m$it", now - it) }
        val dao = FakeDao(rows)
        applyRetentionPolicy(dao, now)
        assertEquals(listOf("s1" to "m10001"), dao.deleted)
    }

    @Test
    fun `空表无操作`() = runBlocking {
        val dao = FakeDao(emptyList())
        applyRetentionPolicy(dao, now)
        assertTrue(dao.deleted.isEmpty())
    }
}
