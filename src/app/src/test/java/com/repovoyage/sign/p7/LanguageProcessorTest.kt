package com.repovoyage.sign.p7

import com.repovoyage.sign.language.*
import com.repovoyage.sign.sentence.ConfirmedSentence
import com.repovoyage.sign.sentence.LangCode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * P7 LanguageProcessor 编排验收（API.md §6 / ARCHITECTURE §2.4.6）：
 * 逐语言状态映射（UNAVAILABLE > NEEDS_CONFIRMATION > READY）、期限从 FINAL
 * 起算含排队、1 执行 + 2 待处理溢出转显式未处理、新 epoch 取消旧任务、
 * 去重键经 LanguageResultGate 拦截。
 *
 * 注：language 包用通配 import（显式按类 import 在 K2 多文件编译下有
 * 包解析竞态，见提交记录）。
 */
class LanguageProcessorTest {

    /** 可控假引擎：每次调用挂起直至测试补全结果 */
    private class FakeEngine : LlmPolisher {
        val inputs = mutableListOf<PolishInput>()
        val pending = mutableListOf<CompletableDeferred<PolishOutput>>()

        override suspend fun polish(input: PolishInput, deadlineMonoMs: Long): PolishOutput {
            inputs += input
            val d = CompletableDeferred<PolishOutput>()
            pending += d
            return d.await()
        }

        fun release(output: PolishOutput?) {
            val d = pending.removeAt(0)
            if (output != null) {
                d.complete(output)
            } else {
                d.completeExceptionally(CloudPolishException("MODEL_TIMEOUT", true, "x"))
            }
        }
    }

    private var now = 1_000L
    private lateinit var engine: FakeEngine
    private lateinit var scope: CoroutineScope
    private lateinit var processor: LanguageProcessor

    @Before
    fun setUp() {
        now = 1_000L
        engine = FakeEngine()
        scope = CoroutineScope(Dispatchers.Unconfined)
        processor = LanguageProcessorImpl(
            engine = engine,
            scope = scope,
            monoMs = { now },
            deadlineMs = 10_000L,
        )
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun sentence(seg: String, epoch: Long = 1, text: String = "我需要帮助") = ConfirmedSentence(
        sessionId = "s-1", streamGeneration = 1, sequenceEpoch = epoch,
        segmentId = seg, revision = 2, startPtsUs = 0, endPtsUs = 9_000,
        rawChinese = text, confidence = null, userConfirmed = false,
    )

    private fun prefs(vararg langs: String, revision: Long = 5) = OutputPreferences(
        selectedLanguages = langs.map { LangCode(it) },
        spokenLanguages = langs.map { LangCode(it) },
        revision = revision,
    )

    private fun output(
        polished: String? = "我需要帮助。",
        translations: Map<String, String> = emptyMap(),
        issues: List<FidelityIssue> = emptyList(),
    ) = PolishOutput(polished, translations.mapKeys { LangCode(it.key) }, issues,
        OutputSource.CLOUD, 0)

    private suspend fun collect(flow: Flow<LanguageResult>) = flow.toList()

    @Test
    fun `正常路径逐语言映射 READY`() = runBlocking {
        val flow = processor.process(sentence("m1"), prefs("zh-CN", "en-US"))
        val collected = mutableListOf<LanguageResult>()
        val job = launch { collected += collect(flow) }
        // 引擎收到请求：身份 + 目标语言（排队即刻开始执行）
        assertEquals("m1", engine.inputs.single().segmentId)
        assertEquals(listOf("zh-CN", "en-US"), engine.inputs.single().targetLanguages.map { it.tag })
        engine.release(output(translations = mapOf("en-US" to "I need help.")))
        job.join()
        assertEquals(2, collected.size)
        val zh = collected.first { it.language == LangCode("zh-CN") }
        assertEquals("我需要帮助。", zh.text)
        assertEquals(OutputStatus.READY, zh.status)
        val en = collected.first { it.language == LangCode("en-US") }
        assertEquals("I need help.", en.text)
        assertEquals(OutputStatus.READY, en.status)
        assertEquals("s-1", en.sessionId)
        assertEquals(2, en.sentenceRevision)
        assertEquals(5L, en.settingsRevision)
        assertTrue(en.elapsedMs >= 0)
    }

    @Test
    fun `缺文本 UNAVAILABLE 与 issue NEEDS_CONFIRMATION`() = runBlocking {
        val flow = processor.process(sentence("m1"), prefs("zh-CN", "en-US", "ja-JP"))
        val collected = mutableListOf<LanguageResult>()
        val job = launch { collected += collect(flow) }
        engine.release(
            output(
                polished = "我需要帮助。",
                translations = mapOf("en-US" to "I need help."),
                issues = listOf(
                    FidelityIssue(LangCode("en-US"), FidelityIssueCode.AMBIGUITY, "请核对。"),
                ),
            ),
        )
        job.join()
        assertEquals(3, collected.size)
        assertEquals(OutputStatus.NEEDS_CONFIRMATION, collected.first { it.language == LangCode("en-US") }.status)
        assertEquals("I need help.", collected.first { it.language == LangCode("en-US") }.text)  // 候选保留
        val ja = collected.first { it.language == LangCode("ja-JP") }
        assertEquals(OutputStatus.UNAVAILABLE, ja.status)
        assertNull(ja.text)
    }

    @Test
    fun `引擎失败全部 UNAVAILABLE`() = runBlocking {
        val flow = processor.process(sentence("m1"), prefs("zh-CN", "en-US"))
        val collected = mutableListOf<LanguageResult>()
        val job = launch { collected += collect(flow) }
        engine.release(null)   // 抛 CloudPolishException
        job.join()
        assertEquals(2, collected.size)
        assertTrue(collected.all { it.status == OutputStatus.UNAVAILABLE && it.text == null })
    }

    @Test
    fun `排队超期不调引擎直接 UNAVAILABLE`() = runBlocking {
        val f1 = processor.process(sentence("m1"), prefs("zh-CN"))
        val c1 = mutableListOf<LanguageResult>()
        val j1 = launch { c1 += collect(f1) }
        val f2 = processor.process(sentence("m2"), prefs("zh-CN"))   // 排队
        val c2 = mutableListOf<LanguageResult>()
        val j2 = launch { c2 += collect(f2) }
        now += 10_001   // 期限（FINAL 起算 10s）已过
        engine.release(output())  // 释放 m1 → m2 出队即超期
        j1.join(); j2.join()
        assertEquals(1, engine.inputs.size)                       // m2 未调引擎
        assertEquals(OutputStatus.UNAVAILABLE, c2.single().status)
        assertNull(c2.single().text)
        assertTrue(c2.single().elapsedMs >= 10_001)               // 含排队时长
    }

    @Test
    fun `溢出转显式未处理保留最新`() = runBlocking {
        val flows = (1..4).map { i -> processor.process(sentence("m$i"), prefs("zh-CN")) }
        // m1 执行中（挂起），m2/m3 待处理，m4 入队 → 最老待处理 m2 转显式未处理
        val c2 = mutableListOf<LanguageResult>()
        val j2 = launch { c2 += collect(flows[1]) }
        j2.join()
        assertEquals(1, c2.size)
        assertEquals(OutputStatus.UNAVAILABLE, c2.single().status)
        assertNull(c2.single().text)
        engine.release(output())
        engine.release(output())
        engine.release(output())   // m3、m4 依次完成
        assertEquals(3, engine.inputs.size)   // m1/m3/m4；m2 未调引擎
    }

    @Test
    fun `新 epoch 取消执行中任务不产出`() = runBlocking {
        val f1 = processor.process(sentence("m1", epoch = 1), prefs("zh-CN"))
        val c1 = mutableListOf<LanguageResult>()
        val j1 = launch { c1 += collect(f1) }
        // 新 epoch 任务入队：m1 被取消（执行中 → 结果丢弃）
        val f2 = processor.process(sentence("m2", epoch = 2), prefs("zh-CN"))
        val c2 = mutableListOf<LanguageResult>()
        val j2 = launch { c2 += collect(f2) }
        engine.release(output())   // m1 的迟到结果被丢弃
        engine.release(output())
        j1.join(); j2.join()
        assertTrue(c1.isEmpty())   // 取消任务无产出
        assertEquals(1, c2.size)
        assertEquals(OutputStatus.READY, c2.single().status)
    }

    @Test
    fun `去重键拦截同键第二次结果`() = runBlocking {
        val f1 = processor.process(sentence("m1"), prefs("zh-CN"))
        val c1 = mutableListOf<LanguageResult>()
        val j1 = launch { c1 += collect(f1) }
        engine.release(output())
        j1.join()
        // 同句重复提交：引擎再跑一次，但去重键 (sessionId, segmentId, revision, language)
        // 已有有效结果 → 第二次不产出
        val f2 = processor.process(sentence("m1"), prefs("zh-CN"))
        val c2 = mutableListOf<LanguageResult>()
        val j2 = launch { c2 += collect(f2) }
        engine.release(output())
        j2.join()
        assertEquals(2, engine.inputs.size)
        assertTrue(c2.isEmpty())
    }
}
