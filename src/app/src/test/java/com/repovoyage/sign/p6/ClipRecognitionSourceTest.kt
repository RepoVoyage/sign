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
 * 模型 B 固定窗口切片识别源验收（P6 联调，2026-09-23 用户流程）：
 * 词级候选累积草稿、拒绝词触发重打提示（不打扰不震动）、组句 needsConfirmation
 * 布尔直接映射边界可靠性（无数值置信度不伪造）、pts 账本、句尾切片先识别再组句。
 */
class ClipRecognitionSourceTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private class FakeTransport : ClipTransport {
        val cvQueue = ArrayDeque<CvResult>()
        val recognizedSizes = mutableListOf<Int>()

        /** 非 null 时 recognize 先在此挂起（模拟识别慢于切片速度的积压场景） */
        var gate: CompletableDeferred<Unit>? = null
        var composeSentence: String? = null
        var composeStatus = "OK"
        var composeNeedsConfirmation = false
        var lastComposeSegmentId: String? = null
        var lastComposeRevision = -1
        var composeGestureCount = -1
        var composedLabels = emptyList<List<String>>()
        var echoMismatch = false

        override suspend fun recognize(videoBytes: ByteArray, token: String): CvResult {
            assertEquals("cv-tok", token)
            gate?.await()
            recognizedSizes += videoBytes.size
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
            lastComposeSegmentId = segmentId
            lastComposeRevision = revision
            composeGestureCount = gestures.size
            composedLabels = gestures.map { result -> result.candidates.map { it.label } }
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
        var boundary: (() -> Unit)? = null
        var dropped: (() -> Unit)? = null
        var lastWindowUs = -1L
        var failReason: String? = null

        override fun attach(
            outputDir: File,
            windowUs: Long,
            scope: CoroutineScope,
            onSegment: (File, Long, Long) -> Unit,
            onDropped: () -> Unit,
            onSentenceBoundary: () -> Unit,
        ): String? {
            failReason?.let { return it }
            attached = true
            callback = onSegment
            boundary = onSentenceBoundary
            dropped = onDropped
            lastWindowUs = windowUs
            outputDir.mkdirs()
            return null
        }

        override fun finishSentence(): Boolean {
            boundary?.invoke()
            return boundary != null
        }

        override fun detach() {
            attached = false
            detached = true
            callback = null
            boundary = null
            dropped = null
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
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
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
    fun `词候选累积为草稿；拒绝词触发重打提示且不入句`() = runBlocking {
        settings.setRecognitionTokens("cv-tok", "agent-tok")
        val source = newSource()
        waitUntil(10_000) { source.isAvailable }
        val updates = mutableListOf<RecognitionUpdate>()
        scope.launch { source.updates.collect { updates += it } }
        var repeats = 0
        scope.launch { source.needsRepeat.collect { repeats++ } }
        source.start()

        transport.cvQueue += ok("我")
        feed.callback!!(clip("a.mp4", byteArrayOf(1, 2, 3)), 1_000_000, 3_000_000)
        waitUntil { updates.isNotEmpty() }
        assertEquals("我", updates.last().draftText)
        assertNull(updates.last().boundary)
        // 上传后切片文件即删（不滞留隐私数据）；训练留存目录保留验尸副本
        waitUntil { !File(tmp.root, "a.mp4").exists() }
        assertEquals(1, File(tmp.root, "retain").listFiles()?.size)

        transport.cvQueue += CvResult("TOO_SHORT", 5, 0.1, emptyList(), false)
        feed.callback!!(clip("b.mp4", byteArrayOf(4)), 3_000_000, 5_000_000)
        waitUntil { repeats == 1 }
        assertEquals(1, updates.size)   // 拒绝词不产生段更新
        assertTrue(source.statusText.value!!.contains("TOO_SHORT"))
    }

    @Test
    fun `完成本句——needsConfirmation 映射 UNCERTAIN 边界，pts 账本取词段范围`() = runBlocking {
        settings.setRecognitionTokens("cv-tok", "agent-tok")
        val source = newSource()
        waitUntil(10_000) { source.isAvailable }
        val updates = mutableListOf<RecognitionUpdate>()
        scope.launch { source.updates.collect { updates += it } }
        source.start()

        transport.cvQueue += ok("我")
        feed.callback!!(clip("a.mp4", byteArrayOf(1)), 1_000_000, 3_000_000)
        transport.cvQueue += ok("回", "去")
        feed.callback!!(clip("b.mp4", byteArrayOf(2)), 3_000_000, 5_000_000)
        waitUntil { updates.size == 2 }
        assertEquals("我 · 回", updates.last().draftText)   // 草稿取各词 top-1

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
        assertEquals(2, transport.composeGestureCount)
        assertEquals(listOf(listOf("我"), listOf("回", "去")), transport.composedLabels)
        assertEquals("组句待核对", source.statusText.value)

        // 成功后清空：下一词开新段
        transport.cvQueue += ok("家")
        feed.callback!!(clip("c.mp4", byteArrayOf(3)), 6_000_000, 8_000_000)
        waitUntil { updates.size == 4 }
        assertEquals("家", updates.last().draftText)
        assertFalse(updates.last().segmentId == final.segmentId)
    }

    @Test
    fun `完成本句——无待核实时为 RELIABLE 边界（正常收尾）`() = runBlocking {
        settings.setRecognitionTokens("cv-tok", "agent-tok")
        val source = newSource()
        waitUntil(10_000) { source.isAvailable }
        val updates = mutableListOf<RecognitionUpdate>()
        scope.launch { source.updates.collect { updates += it } }
        source.start()
        transport.cvQueue += ok("我")
        feed.callback!!(clip("a.mp4", byteArrayOf(1)), 0, 2_000_000)
        waitUntil { updates.size == 1 }
        transport.composeSentence = "我想回家"
        transport.composeNeedsConfirmation = false
        source.finishSentence()
        waitUntil { updates.any { it.boundary != null } }
        assertEquals(BoundaryReliability.RELIABLE, updates.last().boundary!!.reliability)
        assertNull(source.statusText.value)
    }

    @Test
    fun `句尾切片尚在识别时不提前组句，等待边界后包含最后一词`() = runBlocking {
        settings.setRecognitionTokens("cv-tok", "agent-tok")
        val source = newSource()
        waitUntil(10_000) { source.isAvailable }
        source.start()
        val gate = CompletableDeferred<Unit>()
        transport.gate = gate
        transport.cvQueue += ok("我")
        transport.cvQueue += ok("家")
        feed.callback!!(clip("first.mp4", byteArrayOf(1)), 0, 2_000_000)
        feed.callback!!(clip("tail.mp4", byteArrayOf(2)), 2_000_000, 3_000_000)
        transport.composeSentence = "我想回家"
        source.finishSentence()
        assertNull(transport.lastComposeSegmentId)
        gate.complete(Unit)
        waitUntil { transport.composeGestureCount == 2 }
        assertEquals(2, transport.recognizedSizes.size)
    }

    @Test
    fun `切片封装失败后句子不提交 Agent`() = runBlocking {
        settings.setRecognitionTokens("cv-tok", "agent-tok")
        val source = newSource()
        waitUntil(10_000) { source.isAvailable }
        source.start()
        transport.cvQueue += ok("我")
        feed.callback!!(clip("first.mp4", byteArrayOf(1)), 0, 2_000_000)
        waitUntil { transport.recognizedSizes.size == 1 }
        feed.dropped!!()
        source.finishSentence()
        waitUntil { source.statusText.value?.contains("已丢弃") == true }
        assertNull(transport.lastComposeSegmentId)
    }

    @Test
    fun `组句失败后不会把旧候选带到下一句；回显不匹配拒绝结果`() = runBlocking {
        settings.setRecognitionTokens("cv-tok", "agent-tok")
        val source = newSource()
        waitUntil(10_000) { source.isAvailable }
        val updates = mutableListOf<RecognitionUpdate>()
        scope.launch { source.updates.collect { updates += it } }
        source.start()
        transport.cvQueue += ok("我")
        feed.callback!!(clip("a.mp4", byteArrayOf(1)), 0, 2_000_000)
        waitUntil { updates.size == 1 }

        transport.composeSentence = null
        transport.composeStatus = "NO_MATCH"
        source.finishSentence()
        waitUntil { source.statusText.value?.contains("NO_MATCH") == true }
        assertTrue(updates.none { it.boundary != null })
        assertEquals(1, transport.lastComposeRevision)

        // 上句候选已封存；新句需重新识别
        transport.cvQueue += ok("你")
        feed.callback!!(clip("b.mp4", byteArrayOf(2)), 3_000_000, 5_000_000)
        waitUntil { updates.size == 2 }
        // 回显不匹配：拒绝，不产出边界更新
        transport.composeSentence = "我想回家"
        transport.echoMismatch = true
        source.finishSentence()
        waitUntil { source.statusText.value?.contains("不匹配") == true }
        assertEquals(2, transport.lastComposeRevision)
        assertTrue(updates.none { it.boundary != null })

        // 再打新句后成功
        transport.echoMismatch = false
        transport.cvQueue += ok("家")
        feed.callback!!(clip("c.mp4", byteArrayOf(3)), 6_000_000, 8_000_000)
        waitUntil { updates.size == 3 }
        source.finishSentence()
        waitUntil { updates.any { it.boundary != null } }
        assertEquals("我想回家", updates.last().draftText)
        assertEquals(3, transport.lastComposeRevision)
    }

    @Test
    fun `积压超限——溢出切片丢弃且本句作废，提示重打`() = runBlocking {
        settings.setRecognitionTokens("cv-tok", "agent-tok")
        val source = newSource()
        waitUntil(10_000) { source.isAvailable }
        val updates = mutableListOf<RecognitionUpdate>()
        scope.launch { source.updates.collect { updates += it } }
        var repeats = 0
        scope.launch { source.needsRepeat.collect { repeats++ } }
        source.start()

        // 首段识别被 gate 挂起 = 消费停滞；积压 3 段后第 4 段溢出
        transport.gate = CompletableDeferred()
        transport.cvQueue += ok("我")
        transport.cvQueue += ok("回")
        transport.cvQueue += ok("家")
        feed.callback!!(clip("a.mp4", byteArrayOf(1)), 0, 2_000_000)
        feed.callback!!(clip("b.mp4", byteArrayOf(2)), 2_000_000, 4_000_000)
        feed.callback!!(clip("c.mp4", byteArrayOf(3)), 4_000_000, 6_000_000)
        feed.callback!!(clip("d.mp4", byteArrayOf(4)), 6_000_000, 8_000_000)
        // 溢出切片即删（不滞留磁盘）
        assertFalse(File(tmp.root, "d.mp4").exists())

        transport.gate!!.complete(Unit)
        source.finishSentence()
        waitUntil { repeats == 1 }
        // 溢出触发一次重打提示；本句全部作废，不向 Agent 提交缺词句
        assertEquals(1, repeats)
        assertTrue(updates.none { it.boundary != null })
        assertNull(transport.lastComposeSegmentId)
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
