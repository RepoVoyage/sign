package com.repovoyage.sign.p7

import com.repovoyage.sign.history.CachedSentenceRecord
import com.repovoyage.sign.history.SentenceCacheRetention
import com.repovoyage.sign.history.SentenceWithResults
import com.repovoyage.sign.history.exportCsv
import com.repovoyage.sign.history.exportJson
import com.repovoyage.sign.history.mergeLanguageResult
import com.repovoyage.sign.history.toLanguageResultRecord
import com.repovoyage.sign.history.toSentenceRecord
import com.repovoyage.sign.language.LanguageResult
import com.repovoyage.sign.language.OutputSource
import com.repovoyage.sign.language.OutputStatus
import com.repovoyage.sign.sentence.ConfirmedSentence
import com.repovoyage.sign.sentence.LangCode
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P7 缓存保留策略验收（API.md §8 初始值：最近 90 天且 ≤10000 条，任一超限清理）。
 */
class CacheRetentionTest {

    private val policy = SentenceCacheRetention()
    private val dayMs = 24 * 3600 * 1000L
    private val now = 1_000_000_000L

    private fun rec(key: String, ageDays: Long) = CachedSentenceRecord(key, now - ageDays * dayMs)

    @Test
    fun `超龄记录删除未满保留`() {
        val evict = policy.evict(listOf(rec("a", 91), rec("b", 89), rec("c", 90)), now)
        assertEquals(listOf("a"), evict.map { it.key })
    }

    @Test
    fun `恰满 90 天不删`() {
        assertEquals(0, policy.evict(listOf(rec("a", 90)), now).size)
    }

    @Test
    fun `超量删最老回到限内`() {
        // 10001 条全部在 90 天内（毫秒级年龄区分新旧）：it=1 最新 … it=10001 最老
        // （原用例以天为年龄单位，10001 条中 9911 条超龄，与「超龄必删」用例矛盾）
        val records = (1..10_001).map { CachedSentenceRecord("k%05d".format(10_001 - it), now - it) }
        val evict = policy.evict(records, now)
        assertEquals(1, evict.size)
        assertEquals("k00000", evict.single().key)     // 最老的被清
    }

    @Test
    fun `恰一万条不删`() {
        val records = (1..10_000).map { rec("k$it", 1) }
        assertEquals(0, policy.evict(records, now).size)
    }

    @Test
    fun `超龄与超量同时触发各自清理`() {
        // 9999 条新鲜 + 2 条超龄 = 10001 条：删 2 条超龄后回到限内，不再按量删
        val records = (1..9_999).map { rec("n$it", 1) } + listOf(rec("old1", 200), rec("old2", 300))
        val evictKeys = policy.evict(records, now).map { it.key }.toSet()
        assertEquals(setOf("old1", "old2"), evictKeys)
    }

    @Test
    fun `空缓存与空入参安全`() {
        assertTrue(policy.evict(emptyList(), now).isEmpty())
    }
}

/**
 * P7 缓存域层验收（API.md §8）：记录映射、按语言合并（不存草稿）、
 * 导出 JSON 结构、CSV 转义与公式前缀防护。
 */
class SentenceCacheDomainTest {

    private fun sentence(seg: String, revision: Int = 2) = ConfirmedSentence(
        sessionId = "s-1", streamGeneration = 3, sequenceEpoch = 1,
        segmentId = seg, revision = revision, startPtsUs = 100, endPtsUs = 9_000,
        rawChinese = "我需要帮助", confidence = 0.8f, userConfirmed = false,
    )

    private fun result(seg: String, lang: String, revision: Int = 2, text: String? = "译文") = LanguageResult(
        sessionId = "s-1", streamGeneration = 3, sequenceEpoch = 1,
        segmentId = seg, sentenceRevision = revision, settingsRevision = 5,
        language = LangCode(lang), text = text,
        status = if (text == null) OutputStatus.UNAVAILABLE else OutputStatus.READY,
        source = OutputSource.CLOUD, elapsedMs = 120,
    )

    @Test
    fun `句子映射字段完整`() {
        val r = sentence("m1").toSentenceRecord(wallTimeStartMs = 1_000, wallTimeEndMs = 2_000)
        assertEquals("s-1", r.sessionId)
        assertEquals("m1", r.segmentId)
        assertEquals(3L, r.streamGeneration)
        assertEquals(1L, r.sequenceEpoch)
        assertEquals(2, r.revision)
        assertEquals(100L, r.startPtsUs)
        assertEquals(9_000L, r.endPtsUs)
        assertEquals(1_000L, r.wallTimeStart)
        assertEquals(2_000L, r.wallTimeEnd)
        assertEquals("我需要帮助", r.rawChinese)
        assertEquals(0.8f, r.confidence)
        assertEquals(false, r.userConfirmed)
    }

    @Test
    fun `语言结果映射与状态字符串化`() {
        val r = result("m1", "zh-CN").toLanguageResultRecord(processedAtWallMs = 1_500)
        assertEquals("s-1", r.sessionId)
        assertEquals("m1", r.segmentId)
        assertEquals("zh-CN", r.language.tag)
        assertEquals(2, r.sentenceRevision)
        assertEquals(5L, r.settingsRevision)
        assertEquals("译文", r.text)
        assertEquals("READY", r.status)
        assertEquals("CLOUD", r.source)
        assertEquals(1_500L, r.processedAtWallMs)
        val u = result("m1", "ja-JP", text = null).toLanguageResultRecord(1_600)
        assertEquals("UNAVAILABLE", u.status)
        assertEquals(null, u.text)
    }

    @Test
    fun `按语言合并替换同键`() {
        val existing = listOf(
            result("m1", "zh-CN").toLanguageResultRecord(1_000),
            result("m1", "en-US").toLanguageResultRecord(1_100),
        )
        val merged = mergeLanguageResult(existing, result("m1", "en-US", text = "new").toLanguageResultRecord(1_200))
        assertEquals(2, merged.size)
        assertEquals("new", merged.first { it.language == LangCode("en-US") }.text)
        assertEquals(1_200L, merged.first { it.language == LangCode("en-US") }.processedAtWallMs)
        assertEquals("译文", merged.first { it.language == LangCode("zh-CN") }.text)
        val appended = mergeLanguageResult(merged, result("m1", "ja-JP").toLanguageResultRecord(1_300))
        assertEquals(3, appended.size)
    }

    @Test
    fun `导出 JSON 结构`() {
        val entry = SentenceWithResults(
            sentence = sentence("m1").toSentenceRecord(1_000, 2_000),
            results = listOf(
                result("m1", "zh-CN").toLanguageResultRecord(1_500),
                result("m1", "en-US").toLanguageResultRecord(1_600),
            ),
        )
        val json = exportJson(listOf(entry))
        val root = JSONObject(json)
        val sentences = root.getJSONArray("sentences")
        assertEquals(1, sentences.length())
        val s = sentences.getJSONObject(0)
        assertEquals("我需要帮助", s.getString("rawChinese"))
        assertEquals("s-1", s.getString("sessionId"))
        val results: JSONArray = s.getJSONArray("languageResults")
        assertEquals(2, results.length())
        assertEquals("READY", results.getJSONObject(0).getString("status"))
        assertEquals("zh-CN", results.getJSONObject(0).getString("language"))
    }

    @Test
    fun `CSV 转义与公式前缀防护`() {
        val rows = listOf(
            listOf("plain", "a,b", "含\"引号\"", "换行\n文本", "=cmd", "+1", "@ref", "-2"),
        )
        val csv = exportCsv(
            header = listOf("col1", "col2", "col3", "col4", "col5", "col6", "col7", "col8"),
            rows = rows,
        )
        val lines = csv.split('\n')
        assertEquals("col1,col2,col3,col4,col5,col6,col7,col8", lines[0])
        val data = lines.drop(1).joinToString("\n")
        assertTrue(data.contains("\"a,b\""))
        assertTrue(data.contains("'=cmd"))
        assertTrue(data.contains("'+1"))
        assertTrue(data.contains("'@ref"))
        assertTrue(data.contains("'-2"))
        // 内嵌换行单元格按 RFC 4180 加引号，物理行数 ≠ 逻辑行数，只验证引号包裹
        assertTrue(data.contains("\"换行\n文本\""))
    }
}
