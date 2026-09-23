package com.repovoyage.sign.p6

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.repovoyage.sign.recognition.ClipFeed
import com.repovoyage.sign.recognition.ClipRecognitionSource
import com.repovoyage.sign.recognition.ClipTransport
import com.repovoyage.sign.recognition.ComposeResult
import com.repovoyage.sign.recognition.CvCandidate
import com.repovoyage.sign.recognition.CvResult
import com.repovoyage.sign.recognition.RecognitionSource
import com.repovoyage.sign.recognition.RoutingRecognitionSource
import com.repovoyage.sign.sentence.BoundaryReliability
import com.repovoyage.sign.sentence.RecognitionUpdate
import com.repovoyage.sign.settings.AppSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 模型 B 固定窗口切片识别源验收（槽位制，2026-09-23 用户定稿）：每窗口一槽，
 * 拒识/失败/积压丢弃 = 空槽（＿）就地保留位置，不重排不去重不作废整句；
 * 草稿按槽序展示；「完成本句」槽序原样送 Agent（空槽=空候选组）。
 * needsConfirmation 布尔映射边界可靠性（待核对+震动 / 正常收尾）。
 */
class ClipRecognitionSourceTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private class FakeTransport : ClipTransport {
        val cvQueue = ArrayDeque<CvResult>()

        /** 非 null 时 recognize 先在此挂起（模拟识别慢于切片速度的积压场景） */
        var gate: CompletableDeferred<Unit>? = null
        @Volatile
        var composeSentence: String? = null
        @Volatile
        var composeStatus = "OK"
        @Volatile
        var composeNeedsConfirmation = false
        @Volatile
        var lastComposeRevision = -1
        @Volatile
        var composeGestureCount = -1
        @Volatile
        var lastGestures: List<CvResult> = emptyList()
        @Volatile
        var echoMismatch = false

        override suspend fun recognize(videoBytes: ByteArray, token: String): CvResult {
            assertEquals("cv-tok", token)
            gate?.await()
            return cvQueue.removeFirst()
        }

        override suspend fun compose(
            sessionId: String,
            segmentId: String,
            revision: Int,
            gestures: List<CvResult>,
            token: String,
        ): ComposeResult {
            assertEquals("agent-tok", token)
            lastComposeRevision = revision
            composeGestureCount = gestures.size
            lastGestures = gestures
            return ComposeResult(
                sentence = composeSentence,
                alternatives = emptyList(),
                status = composeStatus,
                needsConfirmation = composeNeedsConfirmation,
                segmentId = if (echoMismatch) "wrong" else segmentId,
                revision = revision,
            )
        }
    }

    private class FakeFeed : ClipFeed {
        var attached = false
        var detached = false
        var callback: ((File, Long, Long) -> Unit)? = null
        var lastWindowUs = -1L
        var failReason: String? = null

        override fun attach(
            outputDir: File,
            windowUs: Long,
            scope: CoroutineScope,
            onSegment: (File, Long, Long) -> Unit,
        ): String? {
            failReason?.let { return it }
            attached = true
            callback = onSegment
            lastWindowUs = windowUs
            outputDir.mkdirs()
            return null
        }

        override fun detach() {
            attached = false
            detached = true
            callback = null
        }
    }

    private class FakeFallback : RecognitionSource {
        override val isAvailable = true
        override val sourceDescription = "fallback"
        override val updates: Flow<RecognitionUpdate> = emptyFlow()
        override fun start() {}
        override fun stop() {}
    }

    private lateinit var settings: AppSettings
    private lateinit var transport: FakeTransport
    private lateinit var feed: FakeFeed
    private lateinit var scope: CoroutineScope
    private var cellularAcquired = 0

    @Before
    fun setUp() {
        settings = AppSettings(
            PreferenceDataStoreFactory.create(produceFile = { tmp.newFile("settings.preferences_pb") }),
        )
        transport = FakeTransport()
        feed = FakeFeed()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        cellularAcquired = 0
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun newSource() = ClipRecognitionSource(
        outputDir = File(tmp.root, "clips"),
        settings = settings,
        transport = transport,
        feed = feed,
        scope = scope,
        acquireCellular = { cellularAcquired++ },
        debugRetainDir = File(tmp.root, "retain"),
    )

    private fun ok(vararg labels: String) =
        CvResult("OK", 30, 0.9, labels.map { CvCandidate(it, 0.9) }, false)

    private suspend fun waitUntil(timeoutMs: Long = 3_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            assertTrue("waitUntil 超时", System.currentTimeMillis() < deadline)
            delay(10)
        }
    }

    private fun clip(name: String, content: ByteArray): File =
        File(tmp.root, name).also { it.writeBytes(content) }

    @Test
    fun `令牌齐备才可用；start 请求蜂窝并接通供给`() = runBlocking {
        val source = newSource()
        assertFalse(source.isAvailable)
        settings.setRecognitionTokens("cv-tok", "agent-tok")
        waitUntil(10_000) { source.isAvailable }

        source.start()
        assertTrue(feed.attached)
        assertEquals(1, cellularAcquired)
        // 切片窗口来自用户设置（默认 2s）
        waitUntil { feed.lastWindowUs == 2_000_000L }

        source.stop()
        assertTrue(feed.detached)
        assertNull(source.statusText.value)
    }

    @Test
    fun `attach 失败原因进状态行（相机未连接）`() = runBlocking {
        settings.setRecognitionTokens("cv-tok", "agent-tok")
        feed.failReason = "请先连接相机并开始取流"
        val source = newSource()
        waitUntil(10_000) { source.isAvailable }
        source.start()
        assertEquals("请先连接相机并开始取流", source.statusText.value)
    }

    @Test
    fun `槽位制——候选填槽、拒识空槽、草稿保序展示`() = runBlocking {
        settings.setRecognitionTokens("cv-tok", "agent-tok")
        val source = newSource()
        waitUntil(10_000) { source.isAvailable }
        val updates = java.util.concurrent.CopyOnWriteArrayList<RecognitionUpdate>()
        scope.launch { source.updates.collect { updates += it } }
        val repeats = java.util.concurrent.atomic.AtomicInteger()
        scope.launch { source.needsRepeat.collect { repeats.incrementAndGet() } }
        source.start()

        transport.cvQueue += ok("我")
        feed.callback!!(clip("a.mp4", byteArrayOf(1, 2, 3)), 1_000_000, 3_000_000)
        waitUntil { updates.any { it.draftText == "我" } }
        // 上传后切片文件即删；训练留存目录保留验尸副本
        waitUntil { !File(tmp.root, "a.mp4").exists() }
        assertEquals(1, File(tmp.root, "retain").listFiles()?.size)

        transport.cvQueue += CvResult("TOO_SHORT", 5, 0.1, emptyList(), false)
        feed.callback!!(clip("b.mp4", byteArrayOf(4)), 3_000_000, 5_000_000)
        waitUntil { repeats.get() == 1 }
        waitUntil { updates.last().draftText == "我 · ＿" }   // 空槽保留位置
        assertTrue(source.statusText.value!!.contains("槽空出"))

        // 完成本句：契约要求每项 candidates 1–3 条——空槽不上线，仅发 FILLED 槽
        transport.composeSentence = "我想回家"
        source.finishSentence()
        waitUntil { transport.lastComposeRevision == 1 }
        assertEquals(1, transport.composeGestureCount)
        assertEquals(listOf("我"), transport.lastGestures[0].candidates.map { it.label })
    }

    @Test
    fun `完成本句——needsConfirmation 映射 UNCERTAIN 边界，pts 取首末槽范围`() = runBlocking {
        settings.setRecognitionTokens("cv-tok", "agent-tok")
        val source = newSource()
        waitUntil(10_000) { source.isAvailable }
        val updates = java.util.concurrent.CopyOnWriteArrayList<RecognitionUpdate>()
        scope.launch { source.updates.collect { updates += it } }
        source.start()

        transport.cvQueue += ok("我")
        feed.callback!!(clip("a.mp4", byteArrayOf(1)), 1_000_000, 3_000_000)
        transport.cvQueue += ok("回", "去")
        feed.callback!!(clip("b.mp4", byteArrayOf(2)), 3_000_000, 5_000_000)
        waitUntil { updates.any { it.draftText == "我 · 回" } }

        transport.composeSentence = "我想回家"
        transport.composeNeedsConfirmation = true
        source.finishSentence()
        waitUntil { updates.any { it.boundary != null } }
        val final = updates.last()
        val boundary = final.boundary!!
        assertEquals("我想回家", final.draftText)
        assertEquals(BoundaryReliability.UNCERTAIN, boundary.reliability)
        assertNull(final.confidence)   // Agent 无数值置信度，不伪造
        assertEquals(1_000_000L, final.tokenSpans!!.first().startPtsUs)
        assertEquals(5_000_000L, boundary.cutoffPtsUs)
        waitUntil { source.statusText.value == "组句待核对" }

        // 成功后清槽：下一窗口开新句
        transport.cvQueue += ok("家")
        feed.callback!!(clip("c.mp4", byteArrayOf(3)), 6_000_000, 8_000_000)
        waitUntil { updates.any { it.boundary == null && it.draftText == "家" } }
        assertFalse(updates.last { it.boundary == null }.segmentId == final.segmentId)
    }

    @Test
    fun `完成本句——无待核实时为 RELIABLE 边界（正常收尾）`() = runBlocking {
        settings.setRecognitionTokens("cv-tok", "agent-tok")
        val source = newSource()
        waitUntil(10_000) { source.isAvailable }
        val updates = java.util.concurrent.CopyOnWriteArrayList<RecognitionUpdate>()
        scope.launch { source.updates.collect { updates += it } }
        source.start()
        transport.cvQueue += ok("我")
        feed.callback!!(clip("a.mp4", byteArrayOf(1)), 0, 2_000_000)
        waitUntil { updates.any { it.draftText == "我" } }
        transport.composeSentence = "我想回家"
        transport.composeNeedsConfirmation = false
        source.finishSentence()
        waitUntil { updates.any { it.boundary != null } }
        assertEquals(BoundaryReliability.RELIABLE, updates.last().boundary!!.reliability)
        waitUntil { source.statusText.value == null }
    }

    @Test
    fun `积压溢出——槽就地空出且整句不作废`() = runBlocking {
        settings.setRecognitionTokens("cv-tok", "agent-tok")
        val source = newSource()
        waitUntil(10_000) { source.isAvailable }
        val updates = java.util.concurrent.CopyOnWriteArrayList<RecognitionUpdate>()
        scope.launch { source.updates.collect { updates += it } }
        val repeats = java.util.concurrent.atomic.AtomicInteger()
        scope.launch { source.needsRepeat.collect { repeats.incrementAndGet() } }
        source.start()

        // 首段识别挂起 = 消费停滞；积压 2 段后第 4 段溢出 → 槽 3 就地空出
        transport.gate = CompletableDeferred()
        transport.cvQueue += ok("我")
        transport.cvQueue += ok("回")
        transport.cvQueue += ok("家")
        feed.callback!!(clip("a.mp4", byteArrayOf(1)), 0, 2_000_000)
        feed.callback!!(clip("b.mp4", byteArrayOf(2)), 2_000_000, 4_000_000)
        feed.callback!!(clip("c.mp4", byteArrayOf(3)), 4_000_000, 6_000_000)
        feed.callback!!(clip("d.mp4", byteArrayOf(4)), 6_000_000, 8_000_000)
        assertFalse(File(tmp.root, "d.mp4").exists())
        waitUntil { updates.any { it.draftText.endsWith("＿") } }   // 溢出槽立即空出

        transport.gate!!.complete(Unit)
        waitUntil { updates.any { it.draftText == "我 · 回 · 家 · ＿" } }
        assertEquals(0, repeats.get())   // 溢出是静默空槽，不触发重打提示

        transport.composeSentence = "我回家"
        source.finishSentence()
        waitUntil { transport.lastComposeRevision == 1 }
        assertEquals(3, transport.composeGestureCount)   // 空槽不上线
        assertEquals(listOf("我", "回", "家"), transport.lastGestures.map { it.candidates.first().label })
    }

    @Test
    fun `组句失败（null 句子）保留槽位可重试；回显不匹配拒绝结果`() = runBlocking {
        settings.setRecognitionTokens("cv-tok", "agent-tok")
        val source = newSource()
        waitUntil(10_000) { source.isAvailable }
        val updates = java.util.concurrent.CopyOnWriteArrayList<RecognitionUpdate>()
        scope.launch { source.updates.collect { updates += it } }
        source.start()
        transport.cvQueue += ok("我")
        feed.callback!!(clip("a.mp4", byteArrayOf(1)), 0, 2_000_000)
        waitUntil { updates.any { it.draftText == "我" } }

        transport.composeSentence = null
        transport.composeStatus = "NO_MATCH"
        source.finishSentence()
        waitUntil { source.statusText.value?.contains("NO_MATCH") == true }
        assertTrue(updates.none { it.boundary != null })
        assertEquals(1, transport.lastComposeRevision)

        transport.composeSentence = "我想回家"
        transport.echoMismatch = true
        source.finishSentence()
        waitUntil { source.statusText.value?.contains("不匹配") == true }
        assertEquals(2, transport.lastComposeRevision)
        assertTrue(updates.none { it.boundary != null })

        // 恢复后重试成功：槽位仍在（未因失败清空）
        transport.echoMismatch = false
        source.finishSentence()
        waitUntil { updates.any { it.boundary != null } }
        assertEquals("我想回家", updates.last().draftText)
        assertEquals(3, transport.lastComposeRevision)
    }

    @Test
    fun `完成本句点击即发——不等在途识别`() = runBlocking {
        settings.setRecognitionTokens("cv-tok", "agent-tok")
        val source = newSource()
        waitUntil(10_000) { source.isAvailable }
        source.start()
        transport.cvQueue += ok("我")
        feed.callback!!(clip("a.mp4", byteArrayOf(1)), 0, 2_000_000)
        waitUntil { source.statusText.value?.contains("已填 1 槽") == true }
        // 第二段识别挂起（在途）：点击完成本句应立即以已填槽组句，不被阻塞
        transport.gate = CompletableDeferred()
        transport.cvQueue += ok("回")
        feed.callback!!(clip("b.mp4", byteArrayOf(2)), 2_000_000, 4_000_000)
        transport.composeSentence = "我想回家"
        source.finishSentence()
        waitUntil { transport.lastComposeRevision == 1 }
        assertEquals(1, transport.composeGestureCount)
        // 在途槽随句子提交脱离：回填不再产生草稿/新句账本
        transport.gate!!.complete(Unit)
        delay(200)
        assertEquals(1, transport.composeGestureCount)
    }

    @Test
    fun `全空槽不调 compose（契约 gestures 每项须 1-3 候选）`() = runBlocking {
        settings.setRecognitionTokens("cv-tok", "agent-tok")
        val source = newSource()
        waitUntil(10_000) { source.isAvailable }
        source.start()
        transport.cvQueue += CvResult("TOO_SHORT", 5, 0.1, emptyList(), false)
        feed.callback!!(clip("a.mp4", byteArrayOf(1)), 0, 2_000_000)
        waitUntil { source.statusText.value?.contains("槽空出") == true }
        source.finishSentence()
        waitUntil { source.statusText.value == "本句全为空槽，无候选可组句" }
        assertEquals(-1, transport.lastComposeRevision)   // 未发起 compose
    }

    @Test
    fun `路由——模型 B 走切片源，其余走 flavor 默认源`() = runBlocking {
        settings.setRecognitionTokens("cv-tok", "agent-tok")
        val clip = newSource()
        val routing = RoutingRecognitionSource(settings, clip, FakeFallback(), scope)
        waitUntil { routing.sourceDescription == "fallback" }   // 未选择模型
        assertTrue(routing.isAvailable)                          // fallback（桩源）可用

        settings.setSelectedModelId("model-b")
        waitUntil { routing.sourceDescription.contains("模型 B") }
        assertTrue(routing.isAvailable)

        settings.setSelectedModelId("model-a")
        waitUntil { routing.sourceDescription == "fallback" }
    }
}
