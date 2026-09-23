package com.repovoyage.sign.history

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.repovoyage.sign.sentence.LangCode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Room 数据层真机验收（API.md §8 / ARCHITECTURE §2.6）：upsert 替换、按语言
 * 合并、级联删除、历史组合排序、保留扫描回退与 runner 端到端。内存库，无落盘。
 */
@RunWith(AndroidJUnit4::class)
class SentenceCacheDatabaseTest {

    private lateinit var db: SentenceDatabase
    private lateinit var dao: SentenceDao
    private lateinit var cache: RoomSentenceCache

    @Before
    fun createDb() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            SentenceDatabase::class.java,
        ).build()
        dao = db.sentenceDao()
        cache = RoomSentenceCache(dao)
    }

    @After
    fun closeDb() = db.close()

    private fun sentence(
        seg: String,
        session: String = "s1",
        start: Long = 1_000,
        raw: String = "原文",
    ) = SentenceRecord(
        sessionId = session, segmentId = seg, streamGeneration = 1, sequenceEpoch = 1,
        revision = 2, startPtsUs = 100, endPtsUs = 9_000, wallTimeStart = start,
        wallTimeEnd = start + 500, rawChinese = raw, confidence = 0.9f,
        userConfirmed = false, modelVersion = "", specChecksum = "",
    )

    private fun result(seg: String, lang: String, text: String?, at: Long) = LanguageResultRecord(
        sessionId = "s1", segmentId = seg, language = LangCode(lang), sentenceRevision = 2,
        settingsRevision = 1, text = text, status = if (text == null) "UNAVAILABLE" else "READY",
        source = "CLOUD", processedAtWallMs = at,
    )

    @Test
    fun `同主键句子整行替换`() = runTest {
        cache.upsertSentence(sentence("m1", raw = "旧"))
        cache.upsertSentence(sentence("m1", raw = "新"))
        val history = cache.observeHistory().first()
        assertEquals(1, history.size)
        assertEquals("新", history.single().sentence.rawChinese)
    }

    @Test
    fun `语言结果按语言键合并替换`() = runTest {
        cache.upsertSentence(sentence("m1"))
        cache.mergeLanguageResult(result("m1", "zh-CN", "译文A", 1_000))
        cache.mergeLanguageResult(result("m1", "zh-CN", "译文B", 1_100))
        cache.mergeLanguageResult(result("m1", "en-US", "transB", 1_200))
        val entry = cache.observeHistory().first().single()
        assertEquals(2, entry.results.size)
        val zh = entry.results.first { it.language == LangCode("zh-CN") }
        assertEquals("译文B", zh.text)
        assertEquals(1_100L, zh.processedAtWallMs)
    }

    @Test
    fun `历史按句子墙钟降序`() = runTest {
        cache.upsertSentence(sentence("m1", start = 1_000))
        cache.upsertSentence(sentence("m2", start = 3_000))
        cache.upsertSentence(sentence("m3", start = 2_000))
        val order = cache.observeHistory().first().map { it.sentence.segmentId }
        assertEquals(listOf("m2", "m3", "m1"), order)
    }

    @Test
    fun `删除段级联语言结果`() = runTest {
        cache.upsertSentence(sentence("m1"))
        cache.upsertSentence(sentence("m2"))
        cache.mergeLanguageResult(result("m1", "zh-CN", "t", 1_000))
        cache.mergeLanguageResult(result("m2", "zh-CN", "t", 1_000))
        cache.deleteBySegment("s1", "m1")
        val history = cache.observeHistory().first()
        assertEquals(listOf("m2"), history.map { it.sentence.segmentId })
        assertEquals(1, history.single().results.size)
    }

    @Test
    fun `删除会话只影响该会话`() = runTest {
        cache.upsertSentence(sentence("m1", session = "s1"))
        cache.upsertSentence(sentence("m2", session = "s2"))
        cache.deleteBySession("s1")
        val history = cache.observeHistory().first()
        assertEquals(listOf("s2" to "m2"), history.map { it.sentence.sessionId to it.sentence.segmentId })
    }

    @Test
    fun `全部删除清空两表`() = runTest {
        cache.upsertSentence(sentence("m1"))
        cache.mergeLanguageResult(result("m1", "zh-CN", "t", 1_000))
        cache.deleteAll()
        assertTrue(cache.observeHistory().first().isEmpty())
        assertTrue(dao.observeAllResults().first().isEmpty())
    }

    @Test
    fun `保留扫描无结果时回退句子墙钟`() = runTest {
        cache.upsertSentence(sentence("m1", start = 5_000))       // wallTimeEnd = 5_500
        cache.upsertSentence(sentence("m2", start = 7_000))
        cache.mergeLanguageResult(result("m2", "zh-CN", "t", 9_999))
        val ages = dao.sentenceAges().associate { it.segmentId to it.lastProcessedAtWallMs }
        assertEquals(5_500L, ages["m1"])
        assertEquals(9_999L, ages["m2"])
    }

    @Test
    fun `保留策略端到端删除超龄段并级联`() = runTest {
        val now = System.currentTimeMillis()
        val dayMs = 86_400_000L
        cache.upsertSentence(sentence("old", start = now - 91 * dayMs))
        cache.upsertSentence(sentence("fresh", start = now - dayMs))
        cache.mergeLanguageResult(result("old", "zh-CN", "t", now - 91 * dayMs))
        applyRetentionPolicy(dao, now)
        assertEquals(listOf("fresh"), cache.observeHistory().first().map { it.sentence.segmentId })
        assertTrue(dao.observeAllResults().first().isEmpty())
    }
}
