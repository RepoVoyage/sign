package com.repovoyage.sign.recognition

import com.repovoyage.sign.sentence.BoundaryReliability
import com.repovoyage.sign.sentence.BoundarySignal
import com.repovoyage.sign.sentence.BoundarySource
import com.repovoyage.sign.sentence.RecognitionUpdate
import com.repovoyage.sign.sentence.TokenSpan
import com.repovoyage.sign.settings.AppSettings
import com.repovoyage.sign.settings.RecognitionTokens
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/** 切片识别传输（HTTP 实现见 [HttpClipTransport]；JVM 测试注入替身） */
interface ClipTransport {
    suspend fun recognize(videoBytes: ByteArray, token: String): CvResult
    suspend fun compose(
        sessionId: String,
        segmentId: String,
        revision: Int,
        gestures: List<CvResult>,
        token: String,
    ): ComposeResult
}

/**
 * 切片供给：把相机编码帧流接到切片器，产出的每个 MP4 段回调 [onSegment]。
 * @return attach 失败原因（展示用）；null = 成功
 */
interface ClipFeed {
    fun attach(
        outputDir: File,
        windowUs: Long,
        scope: CoroutineScope,
        onSegment: (File, Long, Long) -> Unit,
        onDropped: () -> Unit,
        onSentenceBoundary: () -> Unit,
    ): String?
    fun finishSentence(): Boolean
    fun detach()
}

/**
 * 模型 B 固定窗口切片识别源（P6 联调，2026-09-23 用户定义切分 + 直接进
 * 字幕管线）：相机编码帧 → 固定时长 MP4 段 → 词级 CV 候选 → 用户「完成本句」
 * → Agent 组句 → [RecognitionUpdate] 进入既有管线（段状态机/翻译/TTS/缓存）。
 *
 * 置信度映射（2026-09-23 定稿策略）：Agent 无数值句子置信度，
 * needsConfirmation 布尔直接映射边界可靠性——true → UNCERTAIN（待核对+震动，
 * 管线既有路径），false → RELIABLE（正常收尾）。CV 侧词级不确定（非 OK 状态）
 * 不入句，仅触发重打提示（needs_repeat），不打扰、不震动。
 */
class ClipRecognitionSource(
    private val outputDir: File,
    private val settings: AppSettings,
    private val transport: ClipTransport,
    private val feed: ClipFeed,
    private val scope: CoroutineScope,
    private val acquireCellular: () -> Unit,
    /** 联调验尸留存目录（flavor 缝：training=cacheDir/clips_debug，production=null 不落盘） */
    private val debugRetainDir: File? = null,
) : RecognitionSource {

    override val isAvailable: Boolean get() = tokens.isConfigured

    override val sourceDescription: String
        get() = if (tokens.isConfigured) "识别源：模型 B 固定窗口切片云端识别"
        else "识别源：模型 B（识别服务令牌未配置）"

    private val _updates = MutableSharedFlow<RecognitionUpdate>(extraBufferCapacity = 64)
    override val updates: Flow<RecognitionUpdate> = _updates

    /** 联调状态行（上传/识别/组句进度与失败原因）；null = 无提示 */
    private val _statusText = MutableStateFlow<String?>(null)
    val statusText: StateFlow<String?> = _statusText.asStateFlow()

    /** 词级拒绝（TOO_SHORT 等）→ VM 转发管线 reportNeedsRepeat()（重打提示，不震动） */
    private val _needsRepeat = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
    val needsRepeat: SharedFlow<Unit> = _needsRepeat

    @Volatile private var tokens = RecognitionTokens("", "")
    @Volatile private var windowUs = (DEFAULT_WINDOW_SECONDS * 1_000_000).toLong()

    private var epoch = 0L
    private var sentenceSeq = 0
    private var composeRevision = 0
    private var sessionId = ""
    private var currentSegmentId: String? = null
    private var sentenceStartPtsUs = 0L
    private var lastClipEndPtsUs = 0L
    private val gestures = mutableListOf<CvResult>()
    private val lock = Mutex()

    @Volatile private var running = false

    /**
     * 待识别切片积压队列（有界）：识别速度跟不上切片速度（如服务端限流/
     * 推理慢于窗口）时不允许无限排队——溢出的切片丢弃且**本句作废**
     * （中间缺词的句子不可信），提示重打并建议加大窗口。
     */
    private sealed interface QueueItem {
        data class Clip(val file: File, val startPtsUs: Long, val endPtsUs: Long, val sentenceId: Long) : QueueItem
        data class Finish(val sentenceId: Long) : QueueItem
    }

    private class ClipQueue {
        val items = Channel<QueueItem>(Channel.UNLIMITED)
        val pendingClips = AtomicInteger()
    }

    private var clipQueue: ClipQueue? = null
    private var consumerJob: Job? = null
    private val composeLock = Mutex()
    private val brokenSentences = mutableSetOf<Long>()
    @Volatile private var captureSentenceId = 0L
    @Volatile private var finishPending = false

    init {
        scope.launch {
            settings.recognitionTokens.collect { tokens = it }
        }
        scope.launch {
            settings.clipWindowSeconds.collect { windowUs = (it * 1_000_000L).toLong() }
        }
    }

    override fun start() {
        if (running) return
        running = true
        epoch++
        sentenceSeq = 0
        composeRevision = 0
        currentSegmentId = null
        gestures.clear()
        synchronized(brokenSentences) { brokenSentences.clear() }
        captureSentenceId = 0L
        finishPending = false
        sessionId = UUID.randomUUID().toString()
        outputDir.mkdirs()
        outputDir.listFiles()?.forEach { it.delete() }
        // §2.4.7：相机在线时进程默认网络无公网，切片上传必须走蜂窝；
        // 释放归管线 stop()（与 LLM 共用同一持有者，幂等）
        acquireCellular()
        val queue = ClipQueue()
        clipQueue = queue
        consumerJob = scope.launch {
            for (item in queue.items) {
                when (item) {
                    is QueueItem.Clip -> try {
                        onClip(item)
                    } finally {
                        queue.pendingClips.decrementAndGet()
                    }
                    is QueueItem.Finish -> onSentenceBoundary(item.sentenceId)
                }
            }
        }
        _statusText.value = feed.attach(
            outputDir, windowUs, scope,
            onSegment = { file, startPtsUs, endPtsUs ->
                val sentenceId = captureSentenceId
                if (clipQueue !== queue || !running) {
                    file.delete()
                } else if (queue.pendingClips.incrementAndGet() > MAX_PENDING_CLIPS) {
                    queue.pendingClips.decrementAndGet()
                    file.delete()
                    breakSentence(sentenceId, "识别跟不上切片速度：本句已丢弃，请重打（可加大切片窗口）")
                } else if (queue.items.trySend(QueueItem.Clip(file, startPtsUs, endPtsUs, sentenceId)).isFailure) {
                    queue.pendingClips.decrementAndGet()
                    file.delete()
                    breakSentence(sentenceId, "切片队列已关闭，请重打本句")
                }
            },
            onDropped = {
                breakSentence(captureSentenceId, "相机切片封装失败或过短，本句已丢弃；请重打")
            },
            onSentenceBoundary = {
                val sentenceId = captureSentenceId++
                finishPending = false
                if (clipQueue === queue && running) queue.items.trySend(QueueItem.Finish(sentenceId))
            },
        )
    }

    override fun stop() {
        running = false
        feed.detach()
        consumerJob?.cancel()
        consumerJob = null
        // 清空积压切片文件（隐私：帧数据不滞留磁盘）
        val q = clipQueue
        clipQueue = null
        if (q != null) {
            q.items.close()
            while (true) {
                val item = q.items.tryReceive().getOrNull() ?: break
                if (item is QueueItem.Clip) {
                    item.file.delete()
                    q.pendingClips.decrementAndGet()
                }
            }
        }
        gestures.clear()
        currentSegmentId = null
        synchronized(brokenSentences) { brokenSentences.clear() }
        finishPending = false
        _statusText.value = null
    }

    private fun breakSentence(sentenceId: Long, message: String) {
        if (synchronized(brokenSentences) { brokenSentences.add(sentenceId) }) {
            _statusText.value = message
            _needsRepeat.tryEmit(Unit)
        }
    }

    private fun isBroken(sentenceId: Long): Boolean =
        synchronized(brokenSentences) { sentenceId in brokenSentences }

    private data class SentenceSnapshot(
        val gestures: List<CvResult>,
        val segmentId: String,
        val revision: Int,
        val sessionId: String,
        val epoch: Long,
        val startPtsUs: Long,
        val endPtsUs: Long,
        val agentToken: String,
    )

    /** Seal the current clip first; the feed delivers its clip before the boundary marker. */
    fun finishSentence() {
        if (!running || finishPending) return
        finishPending = true
        _statusText.value = "正在等待句尾切片识别…"
        if (!feed.finishSentence()) {
            finishPending = false
            breakSentence(captureSentenceId, "句尾切片未能提交，请停止并重新开始识别")
        }
    }

    private suspend fun onSentenceBoundary(sentenceId: Long) {
        if (!running) return
        if (synchronized(brokenSentences) { brokenSentences.remove(sentenceId) }) {
            val discardedId = lock.withLock {
                val id = currentSegmentId
                gestures.clear()
                currentSegmentId = null
                id
            }
            if (discardedId != null) discardDraft(discardedId, epoch)
            _statusText.value = "本句有切片识别失败，已丢弃；请重打整句"
            return
        }
        val snapshot = lock.withLock {
            val segId = currentSegmentId
            if (gestures.isEmpty() || segId == null) null else SentenceSnapshot(
                gestures = gestures.toList(),
                segmentId = segId,
                revision = ++composeRevision,
                sessionId = sessionId,
                epoch = epoch,
                startPtsUs = sentenceStartPtsUs,
                endPtsUs = lastClipEndPtsUs,
                agentToken = tokens.agentToken,
            ).also {
                gestures.clear()
                currentSegmentId = null
            }
        }
        if (snapshot == null) {
            _statusText.value = "本句还没有识别到词"
            return
        }
        _statusText.value = "正在补全句子…"
        // Agent may take seconds. Keep consuming new camera clips while it runs.
        scope.launch {
            composeLock.withLock { composeSnapshot(snapshot) }
        }
    }

    private suspend fun composeSnapshot(snapshot: SentenceSnapshot) {
        val result = try {
            transport.compose(
                snapshot.sessionId, snapshot.segmentId, snapshot.revision,
                snapshot.gestures, snapshot.agentToken,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            if (running && epoch == snapshot.epoch) {
                discardDraft(snapshot.segmentId, snapshot.epoch)
                _statusText.value = error.message ?: "补全请求失败，请重打"
            }
            return
        }
        if (!running || epoch != snapshot.epoch || sessionId != snapshot.sessionId) return
        if (result.segmentId != snapshot.segmentId || result.revision != snapshot.revision) {
            discardDraft(snapshot.segmentId, snapshot.epoch)
            _statusText.value = "Agent 返回的段 ID 或修订号不匹配"
            return
        }
        val sentence = result.sentence
        if (sentence == null) {
            discardDraft(snapshot.segmentId, snapshot.epoch)
            _statusText.value = "未能确定句子：${result.status}；请重打"
            return
        }
        _updates.emit(
            RecognitionUpdate(
                sequenceEpoch = snapshot.epoch,
                segmentId = snapshot.segmentId,
                draftText = sentence,
                tokenSpans = listOf(TokenSpan(sentence, snapshot.startPtsUs, snapshot.endPtsUs, stable = true)),
                confidence = null,   // Agent 无数值置信度，不伪造
                boundary = BoundarySignal(
                    cutoffPtsUs = snapshot.endPtsUs,
                    requiredFutureContextUs = 0,
                    reliability = if (result.needsConfirmation) {
                        BoundaryReliability.UNCERTAIN
                    } else {
                        BoundaryReliability.RELIABLE
                    },
                    source = BoundarySource.MODEL,
                ),
            ),
        )
        _statusText.value = if (result.needsConfirmation) "组句待核对" else null
    }

    private suspend fun discardDraft(segmentId: String, updateEpoch: Long) {
        _updates.emit(RecognitionUpdate(updateEpoch, segmentId, "", discarded = true))
    }

    // ---------------------------------------------------------------- 内部

    private suspend fun onClip(clip: QueueItem.Clip) {
        if (isBroken(clip.sentenceId)) {
            clip.file.delete()
            return
        }
        val bytes = runCatching { clip.file.readBytes() }.getOrNull()
        clip.file.delete()
        if (!running) return
        if (bytes == null) {
            breakSentence(clip.sentenceId, "无法读取切片，本句已丢弃；请重打")
            return
        }
        retainForDebug(bytes)
        val runEpoch = epoch
        _statusText.value = "正在识别切片…"
        val result = try {
            transport.recognize(bytes, tokens.cvToken)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            breakSentence(clip.sentenceId, error.message ?: "识别请求失败，本句已丢弃；请重打")
            return
        }
        if (!running || epoch != runEpoch || isBroken(clip.sentenceId)) return
        if (result.status != "OK" || result.candidates.isEmpty()) {
            breakSentence(
                clip.sentenceId,
                "该词未加入：${result.status}（${result.frames} 帧、手部 " +
                    "${(result.anyHandFraction * 100).toInt()}%）；本句已丢弃，请重打",
            )
            return
        }
        val (update, acceptedCount) = lock.withLock {
            if (gestures.size >= MAX_GESTURES) {
                null to 0
            } else {
                if (gestures.isEmpty()) {
                    sentenceStartPtsUs = clip.startPtsUs
                    currentSegmentId = "clip-$epoch-${++sentenceSeq}"
                }
                lastClipEndPtsUs = clip.endPtsUs
                gestures += result
                RecognitionUpdate(
                    sequenceEpoch = epoch,
                    segmentId = currentSegmentId,
                    draftText = gestures.joinToString(" · ") { it.candidates.first().label },
                ) to gestures.size
            }
        }
        if (update == null) {
            breakSentence(clip.sentenceId, "本句超过 $MAX_GESTURES 个切片，已丢弃；请重打")
        } else {
            _updates.emit(update)
            _statusText.value = "已收 $acceptedCount 词；打完点「完成本句」"
        }
    }

    /** training 联调验尸：滚动保留最近 [MAX_RETAINED_CLIPS] 个已上传切片 */
    private fun retainForDebug(bytes: ByteArray) {
        val dir = debugRetainDir ?: return
        runCatching {
            dir.mkdirs()
            val existing = dir.listFiles()?.sortedBy { it.lastModified() } ?: emptyList()
            existing.take(maxOf(0, existing.size - MAX_RETAINED_CLIPS + 1)).forEach { it.delete() }
            File(dir, "clip-${System.currentTimeMillis()}.mp4").writeBytes(bytes)
        }
    }

    companion object {
        /** 切片窗口默认值（秒）；用户可在设置中定义，start 时读取 */
        const val DEFAULT_WINDOW_SECONDS = 2.0

        /** 待识别积压上限（另有一段在识别中）；溢出即丢句重打，不无限排队 */
        const val CLIP_BACKLOG_CAPACITY = 2

        private const val MAX_PENDING_CLIPS = CLIP_BACKLOG_CAPACITY + 1
        private const val MAX_GESTURES = 12

        private const val MAX_RETAINED_CLIPS = 5
    }
}

/** HTTP 传输实现：词级 CV + 组句 Agent（客户端由 SignApp 注入蜂窝绑定 provider） */
class HttpClipTransport(
    private val cvClient: LocalVideoCvClient,
    private val composeClient: LocalVideoComposeClient,
) : ClipTransport {
    override suspend fun recognize(videoBytes: ByteArray, token: String): CvResult =
        cvClient.recognizeBytes(videoBytes, token)

    override suspend fun compose(
        sessionId: String,
        segmentId: String,
        revision: Int,
        gestures: List<CvResult>,
        token: String,
    ): ComposeResult = composeClient.compose(sessionId, segmentId, revision, gestures, token)
}
