package com.repovoyage.sign.p7

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.repovoyage.sign.history.LanguageResultRecord
import com.repovoyage.sign.history.SentenceCache
import com.repovoyage.sign.history.SentenceRecord
import com.repovoyage.sign.history.SentenceWithResults
import com.repovoyage.sign.alert.ConfirmationAlerter
import com.repovoyage.sign.language.LanguageProcessor
import com.repovoyage.sign.language.LanguageResult
import com.repovoyage.sign.language.OutputPreferences
import com.repovoyage.sign.language.OutputSource
import com.repovoyage.sign.language.OutputStatus
import com.repovoyage.sign.net.CloudNetworkManager
import com.repovoyage.sign.net.CloudNetworkProvider
import com.repovoyage.sign.pipeline.PipelinePhase
import com.repovoyage.sign.pipeline.TranslationPipeline
import com.repovoyage.sign.recognition.RecognitionSource
import com.repovoyage.sign.sentence.BoundaryReliability
import com.repovoyage.sign.sentence.BoundarySignal
import com.repovoyage.sign.sentence.BoundarySource
import com.repovoyage.sign.sentence.ConfirmedSentence
import com.repovoyage.sign.sentence.LangCode
import com.repovoyage.sign.sentence.RecognitionUpdate
import com.repovoyage.sign.sentence.TokenSpan
import com.repovoyage.sign.settings.AppSettings
import com.repovoyage.sign.settings.LlmCredentials
import com.repovoyage.sign.tts.EnqueueResult
import com.repovoyage.sign.tts.SpeakRequest
import com.repovoyage.sign.tts.TtsEvent
import com.repovoyage.sign.tts.TtsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * P7 翻译管线编排验收（ARCHITECTURE §1.2 数据流 / §2.4.4 设置快照 / §2.6 缓存
 * 门控）：识别流 → 段状态机 → FINAL → 语言处理 → 字幕/缓存/播报 的接线行为。
 * 组件全部注入替身（AppSettings 用临时文件 DataStore 真实现）。
 */
class TranslationPipelineTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private class FakeSource(override val isAvailable: Boolean = true) : RecognitionSource {
        private val _updates = MutableSharedFlow<RecognitionUpdate>(extraBufferCapacity = 64)
        override val updates: Flow<RecognitionUpdate> = _updates
        override val sourceDescription: String = "测试桩源"
        var startCount = 0
        var stopCount = 0
        override fun start() { startCount++ }
        override fun stop() { stopCount++ }
        suspend fun emit(update: RecognitionUpdate) = _updates.emit(update)
    }

    private class FakeProcessor(var status: OutputStatus = OutputStatus.READY) : LanguageProcessor {
        val processed = mutableListOf<ConfirmedSentence>()
        override fun process(sentence: ConfirmedSentence, preferences: OutputPreferences): Flow<LanguageResult> {
            processed += sentence
            return flow {
                preferences.selectedLanguages.forEach { language ->
                    emit(
                        LanguageResult(
                            sessionId = sentence.sessionId,
                            streamGeneration = sentence.streamGeneration,
                            sequenceEpoch = sentence.sequenceEpoch,
                            segmentId = sentence.segmentId,
                            sentenceRevision = sentence.revision,
                            settingsRevision = preferences.revision,
                            language = language,
                            text = if (status == OutputStatus.UNAVAILABLE) null else "整理后：${sentence.rawChinese}",
                            status = status,
                            source = OutputSource.CLOUD,
                            elapsedMs = 10,
                        ),
                    )
                }
            }
        }
    }

    private class FakeTts : TtsManager {
        val enqueued = mutableListOf<SpeakRequest>()
        var stopCount = 0
        var clearPendingCount = 0
        private val _events = MutableSharedFlow<TtsEvent>(extraBufferCapacity = 64)
        override val events: kotlinx.coroutines.flow.SharedFlow<TtsEvent> = _events
        override fun enqueue(request: SpeakRequest): EnqueueResult {
            enqueued += request
            return EnqueueResult.Queued
        }
        override fun stopCurrentAndClearQueue() { stopCount++ }
        override fun clearPendingKeepCurrent() { clearPendingCount++ }
        override fun replay(sessionId: String, segmentId: String, language: LangCode) {}
    }

    private class FakeCache : SentenceCache {
        val upserts = mutableListOf<SentenceRecord>()
        val merges = mutableListOf<LanguageResultRecord>()
        override suspend fun upsertSentence(record: SentenceRecord) { upserts += record }
        override suspend fun mergeLanguageResult(record: LanguageResultRecord) { merges += record }
        override fun observeHistory(): Flow<List<SentenceWithResults>> = emptyFlow()
        override suspend fun deleteBySegment(sessionId: String, segmentId: String) {}
        override suspend fun deleteBySession(sessionId: String) {}
        override suspend fun deleteAll() {}
    }

    private lateinit var settings: AppSettings
    private lateinit var source: FakeSource
    private lateinit var processor: FakeProcessor
    private lateinit var tts: FakeTts
    private lateinit var cache: FakeCache
    private lateinit var scope: CoroutineScope

    @Before
    fun setUp() {
        settings = AppSettings(
            PreferenceDataStoreFactory.create(produceFile = { tmp.newFile("settings.preferences_pb") }),
        )
        // 语言处理路径默认按"凭据已配置"铺底（2026-09-24 起未配置 = 静默跳过，
        // 有专门用例覆盖）；LLM 凭据模块已删，仅存 DataStore 残留值能到达这里
        runBlocking { settings.setLlmCredentials(LlmCredentials("https://api.example.com/v1", "k", "m")) }
        source = FakeSource()
        processor = FakeProcessor()
        tts = FakeTts()
        cache = FakeCache()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    }

    private fun newPipeline(finalizeDelayMs: Long = 50) = TranslationPipeline(
        source = source,
        settings = settings,
        processor = processor,
        tts = tts,
        cache = cache,
        scope = scope,
        wallMs = { 1_000 },
        finalizeDelayMs = finalizeDelayMs,
    )

    private fun update(
        text: String,
        reliability: BoundaryReliability? = null,
    ) = RecognitionUpdate(
        sequenceEpoch = 1,
        segmentId = "seg-1",
        draftText = text,
        tokenSpans = listOf(TokenSpan(text, 1_000_000, 3_000_000, stable = true)),
        confidence = 0.9f,
        boundary = reliability?.let {
            BoundarySignal(3_500_000, 500_000, it, BoundarySource.MODEL)
        },
    )

    private suspend fun waitUntil(timeoutMs: Long = 3_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            assertTrue("waitUntil 超时", System.currentTimeMillis() < deadline)
            delay(10)
        }
    }

    @Test
    fun `FINAL 全链路：字幕、pts 账本、缓存与播报`() = runBlocking {
        val pipeline = newPipeline()
        pipeline.start(sessionId = "s-test")
        assertEquals(PipelinePhase.RUNNING, pipeline.state.value.phase)
        assertEquals(1, source.startCount)

        source.emit(update("我需要帮助", BoundaryReliability.RELIABLE))
        // 草稿先显示为"正在识别"，收尾窗口（50ms）后冻结
        waitUntil { pipeline.state.value.lines.isNotEmpty() }
        val line = pipeline.state.value.lines.single()
        assertEquals("seg-1", line.segmentId)
        assertEquals("我需要帮助", line.rawChinese)
        waitUntil { pipeline.state.value.lines.single().results.isNotEmpty() }

        val result = pipeline.state.value.lines.single().results[LangCode("zh-CN")]
        assertEquals(OutputStatus.READY, result?.status)
        assertEquals("整理后：我需要帮助", result?.text)

        // 缓存/TTS 副作用在字幕状态之后异步落地（DataStore 挂起读），等齐再断言
        waitUntil { cache.upserts.isNotEmpty() && cache.merges.isNotEmpty() && tts.enqueued.isNotEmpty() }

        // pts 账本：start=首个 tokenSpan 起点，end=boundary cutoff
        val record = cache.upserts.single()
        assertEquals(1_000_000L, record.startPtsUs)
        assertEquals(3_500_000L, record.endPtsUs)
        assertEquals(1_000L, record.wallTimeStart)          // FINAL 时刻墙钟
        assertEquals(1, cache.merges.size)

        // 完成句直接朗读（2026-09-23 用户决定）：FINAL 即播识别原句，
        // orderKey=源媒体时间；润色文本只进字幕不重复播
        val request = tts.enqueued.single()
        assertEquals("我需要帮助", request.text)
        assertEquals(LangCode("zh-CN"), request.language)
        assertEquals(1_000_000L, request.orderKey)
        scope.cancel()
    }

    @Test
    fun `未配置 LLM 凭据——静默跳过语言处理，原句直读与缓存照常`() = runBlocking {
        settings.setLlmCredentials(LlmCredentials("", "", ""))
        val pipeline = newPipeline()
        pipeline.start("s-test")
        source.emit(update("帮我", BoundaryReliability.RELIABLE))
        waitUntil { pipeline.state.value.lines.isNotEmpty() && tts.enqueued.isNotEmpty() }
        waitUntil { cache.upserts.isNotEmpty() }
        delay(100)   // 给潜在的 polish 调用留窗（不应发生）
        assertTrue(processor.processed.isEmpty())
        assertTrue(pipeline.state.value.lines.single().results.isEmpty())
        assertTrue(cache.merges.isEmpty())
        // 中文直读不受影响
        assertEquals("帮我", tts.enqueued.single().text)
        assertEquals(LangCode("zh-CN"), tts.enqueued.single().language)
        scope.cancel()
    }

    @Test
    fun `缓存关闭后停止写入`() = runBlocking {
        settings.setCacheEnabled(false)
        val pipeline = newPipeline()
        pipeline.start("s-test")
        source.emit(update("谢谢", BoundaryReliability.RELIABLE))
        waitUntil { pipeline.state.value.lines.isNotEmpty() && tts.enqueued.isNotEmpty() }
        delay(50)   // 给缓存写入路径留窗口
        assertTrue(cache.upserts.isEmpty())
        assertTrue(cache.merges.isEmpty())
        scope.cancel()
    }

    @Test
    fun `UNAVAILABLE 润色结果只进字幕，原句仍直接朗读`() = runBlocking {
        processor.status = OutputStatus.UNAVAILABLE
        val pipeline = newPipeline()
        pipeline.start("s-test")
        source.emit(update("帮我", BoundaryReliability.RELIABLE))
        waitUntil { pipeline.state.value.lines.firstOrNull()?.results?.isNotEmpty() == true }
        val result = pipeline.state.value.lines.single().results[LangCode("zh-CN")]
        assertEquals(OutputStatus.UNAVAILABLE, result?.status)
        // 润色失败不产生播报，但完成句在 FINAL 时已直接朗读识别原句（2026-09-23 用户决定）
        assertEquals("帮我", tts.enqueued.single().text)
        scope.cancel()
    }

    @Test
    fun `仅 NEEDS_CONFIRMATION 触发震动`() = runBlocking {
        var vibrations = 0
        val alerter = ConfirmationAlerter(nowMs = { 0 }, canVibrate = { true }, vibrate = { vibrations++ })
        fun pipelineWithAlerter() = TranslationPipeline(
            source = source, settings = settings, processor = processor, tts = tts, cache = cache,
            scope = scope, wallMs = { 1_000 }, finalizeDelayMs = 50, alerter = alerter,
        )

        // 低置信 → 震动一次
        processor.status = OutputStatus.NEEDS_CONFIRMATION
        val pipeline = pipelineWithAlerter()
        pipeline.start("s-test")
        source.emit(update("可能有歧义", BoundaryReliability.RELIABLE))
        waitUntil { vibrations == 1 }
        pipeline.stop()

        // READY 不震动（新会话新段，播报照常入队）
        processor.status = OutputStatus.READY
        pipelineWithAlerter().start("s-test2")
        source.emit(
            RecognitionUpdate(
                sequenceEpoch = 1, segmentId = "seg-2", draftText = "正常句子",
                tokenSpans = listOf(TokenSpan("正常句子", 1_000_000, 3_000_000, true)),
                confidence = 0.9f,
                boundary = BoundarySignal(3_500_000, 500_000, BoundaryReliability.RELIABLE, BoundarySource.MODEL),
            ),
        )
        waitUntil { tts.enqueued.isNotEmpty() }
        delay(50)
        assertEquals(1, vibrations)   // READY 路径没有新增震动
        scope.cancel()
    }

    @Test
    fun `纠错落库 USER 而原样确认保留模型来源`() = runBlocking {
        processor.status = OutputStatus.NEEDS_CONFIRMATION
        val pipeline = newPipeline()
        pipeline.start("s-test")

        // seg-1：文本有改动 → source=USER、status=READY，展示层撤下待核对
        source.emit(update("我有3个孩子", BoundaryReliability.RELIABLE))
        waitUntil {
            pipeline.state.value.lines.any { line ->
                line.results.values.any { it.status == OutputStatus.NEEDS_CONFIRMATION }
            }
        }
        pipeline.submitCorrection("seg-1", LangCode("zh-CN"), "我有三个孩子。")
        waitUntil { cache.merges.any { it.text == "我有三个孩子。" } }
        val corrected = cache.merges.first { it.text == "我有三个孩子。" }
        assertEquals("USER", corrected.source)
        assertEquals("READY", corrected.status)
        val shown = pipeline.state.value.lines.first { it.segmentId == "seg-1" }
            .results[LangCode("zh-CN")]
        assertEquals(OutputSource.USER, shown?.source)
        assertTrue(shown?.userConfirmed == true)

        // seg-2：原样保存 → 人工确认，source 保留模型来源
        source.emit(
            RecognitionUpdate(
                sequenceEpoch = 1, segmentId = "seg-2", draftText = "请跟我来",
                tokenSpans = listOf(TokenSpan("请跟我来", 4_000_000, 6_000_000, true)),
                confidence = 0.9f,
                boundary = BoundarySignal(6_500_000, 500_000, BoundaryReliability.RELIABLE, BoundarySource.MODEL),
            ),
        )
        waitUntil { pipeline.state.value.lines.any { it.segmentId == "seg-2" && it.results.isNotEmpty() } }
        pipeline.submitCorrection("seg-2", LangCode("zh-CN"), "整理后：请跟我来")
        waitUntil { cache.merges.any { it.segmentId == "seg-2" && it.status == "READY" } }
        val confirmed = cache.merges.first { it.segmentId == "seg-2" && it.status == "READY" }
        assertEquals("CLOUD", confirmed.source)

        // 未知段无副作用
        val mergesBefore = cache.merges.size
        pipeline.submitCorrection("no-such-seg", LangCode("zh-CN"), "x")
        delay(30)
        assertEquals(mergesBefore, cache.merges.size)
        scope.cancel()
    }

    @Test
    fun `句子置信度低于阈值降级待核实并震动`() = runBlocking {
        var vibrations = 0
        val alerter = ConfirmationAlerter(nowMs = { 0 }, canVibrate = { true }, vibrate = { vibrations++ })
        val pipeline = TranslationPipeline(
            source = source, settings = settings, processor = processor, tts = tts, cache = cache,
            scope = scope, wallMs = { 1_000 }, finalizeDelayMs = 50, alerter = alerter,
        )
        pipeline.start("s-test")
        // 低置信 0.55 < 0.7 + RELIABLE 边界 → 降级 UNCERTAIN → 待核实 + 震动
        source.emit(
            RecognitionUpdate(
                sequenceEpoch = 1, segmentId = "seg-low", draftText = "可能是低电量",
                tokenSpans = listOf(TokenSpan("可能是低电量", 1_000_000, 3_000_000, true)),
                confidence = 0.55f,
                boundary = BoundarySignal(3_500_000, 500_000, BoundaryReliability.RELIABLE, BoundarySource.MODEL),
            ),
        )
        waitUntil { pipeline.state.value.pendingConfirm.isNotEmpty() }
        assertEquals(1, vibrations)
        // 高置信 0.9 → 正常收尾，无待核实、无震动
        source.emit(
            RecognitionUpdate(
                sequenceEpoch = 1, segmentId = "seg-high", draftText = "我需要帮助",
                tokenSpans = listOf(TokenSpan("我需要帮助", 4_000_000, 6_000_000, true)),
                confidence = 0.9f,
                boundary = BoundarySignal(6_500_000, 500_000, BoundaryReliability.RELIABLE, BoundarySource.MODEL),
            ),
        )
        waitUntil { pipeline.state.value.lines.any { it.segmentId == "seg-high" } }
        delay(50)
        assertEquals(1, vibrations)
        assertTrue(pipeline.state.value.pendingConfirm.none { it.segmentId == "seg-high" })
        scope.cancel()
    }

    @Test
    fun `识别源不可用则管线拒绝启动`() = runBlocking {
        source = FakeSource(isAvailable = false)
        val pipeline = newPipeline()
        pipeline.start("s-test")
        assertEquals(PipelinePhase.SOURCE_UNAVAILABLE, pipeline.state.value.phase)
        assertEquals(0, source.startCount)
        scope.cancel()
    }

    @Test
    fun `语音总开关关闭立即停播`() = runBlocking {
        val pipeline = newPipeline()
        pipeline.start("s-test")
        delay(50)   // 等开关收集器建立基线
        settings.setTtsEnabled(false)
        waitUntil { tts.stopCount >= 1 }
        scope.cancel()
    }

    @Test
    fun `设置修订变更清空尚未开始的旧语音任务`() = runBlocking {
        val pipeline = newPipeline()
        pipeline.start("s-test")
        delay(50)   // 等 preferences 基线
        settings.setSelectedLanguages(listOf(LangCode("zh-CN"), LangCode("en-US")))
        waitUntil { tts.clearPendingCount >= 1 }
        assertEquals(0, tts.stopCount)   // 不打断当前播报
        scope.cancel()
    }

    private class FakeCloudNetworkProvider : CloudNetworkProvider {
        var requestCount = 0
        var unregisterCount = 0
        override fun request(listener: CloudNetworkProvider.Listener) { requestCount++ }
        override fun unregister(listener: CloudNetworkProvider.Listener) { unregisterCount++ }
        override fun boundClient(handle: Any): OkHttpClient = OkHttpClient()
    }

    @Test
    fun `凭据已配置时启动申请蜂窝且停止注销`() = runBlocking {
        settings.setLlmCredentials(LlmCredentials("https://api.example.com/v1", "k", "m"))
        val provider = FakeCloudNetworkProvider()
        val cloudNetwork = CloudNetworkManager(provider)
        val pipeline = TranslationPipeline(
            source = source,
            settings = settings,
            processor = processor,
            tts = tts,
            cache = cache,
            scope = scope,
            wallMs = { 1_000 },
            finalizeDelayMs = 50,
            cloudNetwork = cloudNetwork,
        )
        pipeline.start("s-test")
        waitUntil { provider.requestCount >= 1 }
        pipeline.stop()
        assertEquals(1, provider.unregisterCount)
        scope.cancel()
    }

    @Test
    fun `UNCERTAIN 边界转待核对且可放弃`() = runBlocking {
        val pipeline = newPipeline()
        pipeline.start("s-test")
        source.emit(update("可能是低电量", BoundaryReliability.UNCERTAIN))
        waitUntil { pipeline.state.value.pendingConfirm.isNotEmpty() }
        val pending = pipeline.state.value.pendingConfirm.single()
        assertEquals("seg-1", pending.segmentId)
        assertEquals("可能是低电量", pending.draftText)
        pipeline.discardPending("seg-1")
        assertTrue(pipeline.state.value.pendingConfirm.isEmpty())
        scope.cancel()
    }
}
