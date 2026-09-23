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
import java.io.File
import java.util.UUID

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
    fun attach(outputDir: File, windowUs: Long, scope: CoroutineScope, onSegment: (File, Long, Long) -> Unit): String?
    fun detach()
}

/**
 * 模型 B 固定窗口切片识别源（P6 联调，2026-09-23 用户定义切分 + 槽位语义）。
 *
 * **槽位制（用户定稿）**：每个固定窗口 = 句内一个槽，节奏由窗口决定而非由
 * CV 响应决定。槽的三种状态：PENDING（已切片待识别）→ FILLED（OK 候选组）
 * 或 EMPTY（服务端拒识/HTTP 失败/积压丢弃——就地空出，不重排、不去重、
 * 不作废整句）。草稿按槽序展示（空槽=＿，待识别=…）；「完成本句」把槽序
 * 原样送 Agent（空槽=空候选组，保留位置信息）。
 *
 * 置信度映射（2026-09-23 定稿）：Agent 的 needsConfirmation 布尔直接映射
 * 边界可靠性——true → UNCERTAIN（待核对+震动），false → RELIABLE；CV 侧
 * 拒识仅触发重打提示（needs_repeat），不震动、不是待核实标志。
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

    /** 服务端拒识（TOO_SHORT 等）→ VM 转发管线 reportNeedsRepeat()（重打提示，不震动） */
    private val _needsRepeat = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
    val needsRepeat: SharedFlow<Unit> = _needsRepeat

    @Volatile private var tokens = RecognitionTokens("", "")
    @Volatile private var windowUs = (DEFAULT_WINDOW_SECONDS * 1_000_000).toLong()

    private var epoch = 0L
    private var sentenceSeq = 0
    private var composeRevision = 0
    private var sessionId = ""
    private var currentSegmentId: String? = null
    /** 句内槽位（时间序）；feed 回调/消费者协程/finishSentence 三处访问，锁保护 */
    private class Slot(val startPtsUs: Long, val endPtsUs: Long) {
        var pending = true
        var empty = false
        var result: CvResult? = null

        /** 所属句子已提交/清空：在途识别回填只落对象，不碰新句账本 */
        var detached = false
    }

    private val slots = mutableListOf<Slot>()
    private var pendingCount = 0

    @Volatile private var running = false

    private data class PendingClip(val file: File, val slot: Slot)

    private var clipQueue: Channel<PendingClip>? = null
    private var consumerJob: Job? = null

    /** 草稿文本保序通道（单消费者转发为 RecognitionUpdate） */
    private var draftQueue: Channel<String> = Channel(Channel.UNLIMITED)
    private var draftJob: Job? = null

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
        synchronized(slots) {
            slots.clear()
            pendingCount = 0
        }
        sessionId = UUID.randomUUID().toString()
        outputDir.mkdirs()
        outputDir.listFiles()?.forEach { it.delete() }
        // §2.4.7：相机在线时进程默认网络无公网，切片上传必须走蜂窝；
        // 释放归管线 stop()（与 LLM 共用同一持有者，幂等）
        acquireCellular()
        val queue = Channel<PendingClip>(CLIP_BACKLOG_CAPACITY)
        clipQueue = queue
        consumerJob = scope.launch {
            for (clip in queue) {
                onClip(clip.file, clip.slot)
            }
        }
        draftQueue = Channel(Channel.UNLIMITED)
        draftJob = scope.launch {
            for (text in draftQueue) {
                val segId = currentSegmentId ?: continue
                _updates.emit(
                    RecognitionUpdate(sequenceEpoch = epoch, segmentId = segId, draftText = text),
                )
            }
        }
        _statusText.value = feed.attach(outputDir, windowUs, scope) { file, startPtsUs, endPtsUs ->
            onWindow(file, startPtsUs, endPtsUs)
        }
    }

    override fun stop() {
        running = false
        feed.detach()
        consumerJob?.cancel()
        consumerJob = null
        draftQueue.close()
        draftJob?.cancel()
        draftJob = null
        // 清空积压切片文件（隐私：帧数据不滞留磁盘）
        val q = clipQueue
        clipQueue = null
        if (q != null) {
            q.close()
            while (true) {
                val clip = q.tryReceive().getOrNull() ?: break
                clip.file.delete()
            }
        }
        synchronized(slots) {
            slots.clear()
            pendingCount = 0
        }
        currentSegmentId = null
        _statusText.value = null
    }

    // ---------------------------------------------------------------- 槽位生命周期

    /** 一个窗口封段 = 开一个槽；入队失败（积压满）= 该槽就地空出 */
    private fun onWindow(file: File, startPtsUs: Long, endPtsUs: Long) {
        val slot = Slot(startPtsUs, endPtsUs)
        synchronized(slots) {
            if (currentSegmentId == null) currentSegmentId = "clip-$epoch-${++sentenceSeq}"
            slots += slot
        }
        emitDraft()
        val q = clipQueue
        if (q == null || q.trySend(PendingClip(file, slot)).isFailure) {
            file.delete()
            synchronized(slots) {
                slot.pending = false
                slot.empty = true
            }
            emitDraft()
        } else {
            synchronized(slots) { pendingCount++ }
        }
    }

    private suspend fun onClip(file: File, slot: Slot) {
        val bytes = runCatching { file.readBytes() }.getOrNull()
        file.delete()
        if (!running || bytes == null) {
            fillEmpty(slot)
            return
        }
        retainForDebug(bytes)
        if (!running) {
            fillEmpty(slot)
            return
        }
        _statusText.value = "正在识别第 ${slotIndex(slot) + 1} 个槽…"
        val result = try {
            transport.recognize(bytes, tokens.cvToken)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            fillEmpty(slot)
            _statusText.value = error.message ?: "识别请求失败"
            return
        }
        if (!running) {
            fillEmpty(slot)
            return
        }
        if (result.status == "OK" && result.candidates.isNotEmpty()) {
            synchronized(slots) {
                slot.pending = false
                slot.result = result
                if (!slot.detached && pendingCount > 0) pendingCount--
            }
            _statusText.value = "已填 ${filledCount()} 槽；打完点「完成本句」"
        } else {
            fillEmpty(slot)
            // 服务端拒识 = 该槽空出 + 重打提示（非待核实、不震动）
            _needsRepeat.emit(Unit)
            _statusText.value = "第 ${slotIndex(slot) + 1} 槽空出：${result.status}" +
                "（${result.frames} 帧、手部 ${(result.anyHandFraction * 100).toInt()}%），该词请重打"
        }
        emitDraft()
    }

    private fun fillEmpty(slot: Slot) {
        synchronized(slots) {
            if (slot.pending) {
                slot.pending = false
                if (!slot.detached && pendingCount > 0) pendingCount--
            }
            slot.empty = true
        }
        emitDraft()
    }

    /**
     * 用户「完成本句」：**点击即把当前已填槽快照送 Agent**（用户定稿：不等在途
     * 识别、不被识别中的请求阻塞）；在途槽位随句子提交脱离，回填不落新句。
     */
    fun finishSentence() {
        if (!running) return
        scope.launch {
            val snapshot = synchronized(slots) { slots.toList() }
            if (snapshot.isEmpty()) {
                _statusText.value = "本句还没有槽位"
                return@launch
            }
            // 契约：gestures 每项 candidates 须 1–3 条——空槽（拒识/失败/
                // 丢弃）不上线，仅 App 侧时间轴保留位置；发 FILLED 槽的时间序
                val filled = snapshot.filter { it.result != null }
                if (filled.isEmpty()) {
                    _statusText.value = "本句全为空槽，无候选可组句"
                    return@launch
                }
                val capped = if (filled.size > MAX_GESTURES) {
                    _statusText.value = "候选槽超过 $MAX_GESTURES，仅取前 $MAX_GESTURES 个组句"
                    filled.take(MAX_GESTURES)
                } else {
                    filled
                }
            val segId = currentSegmentId ?: return@launch
            val revision = ++composeRevision
            _statusText.value = "正在补全句子…"
            val gestures = capped.map { it.result!! }
            val result = try {
                transport.compose(sessionId, segId, revision, gestures, tokens.agentToken)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _statusText.value = error.message ?: "补全请求失败"
                return@launch
            }
            if (!running) return@launch
            if (result.segmentId != segId || result.revision != revision) {
                _statusText.value = "Agent 返回的段 ID 或修订号不匹配"
                return@launch
            }
            val sentence = result.sentence
            if (sentence == null) {
                // 槽位保留：可继续补槽或重试组句
                _statusText.value = "未能确定句子：${result.status}；可继续补槽或重试"
                return@launch
            }
            _updates.emit(
                RecognitionUpdate(
                    sequenceEpoch = epoch,
                    segmentId = segId,
                    draftText = sentence,
                    tokenSpans = listOf(
                        TokenSpan(sentence, capped.first().startPtsUs, capped.last().endPtsUs, stable = true),
                    ),
                    confidence = null,   // Agent 无数值置信度，不伪造
                    boundary = BoundarySignal(
                        cutoffPtsUs = capped.last().endPtsUs,
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
            synchronized(slots) {
                slots.forEach { it.detached = true }   // 在途回填不落新句
                slots.clear()
                pendingCount = 0
            }
            currentSegmentId = null
            _statusText.value = if (result.needsConfirmation) "组句待核对" else null
        }
    }

    // ---------------------------------------------------------------- 草稿与工具

    /** 槽序草稿：FILLED=top1 候选，EMPTY=＿，PENDING=…（重复不去重，用户定稿）。
     经单消费者通道保序送达管线（多线程触发点：feed 回调/消费者/finish） */
    private fun emitDraft() {
        val text = synchronized(slots) {
            slots.joinToString(" · ") { slot ->
                when {
                    slot.result != null -> slot.result!!.candidates.first().label
                    slot.empty -> EMPTY_MARK
                    else -> PENDING_MARK
                }
            }
        }
        if (text.isNotEmpty()) draftQueue.trySend(text)
    }

    private fun slotIndex(slot: Slot): Int = synchronized(slots) { slots.indexOf(slot) }

    private fun filledCount(): Int = synchronized(slots) { slots.count { it.result != null } }

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

        /** 待识别积压上限（另有一段在识别中）；溢出槽就地空出，不作废整句 */
        const val CLIP_BACKLOG_CAPACITY = 2

        /** 单句槽位上限（Agent 契约 gestures ≤12） */
        const val MAX_GESTURES = 12

        private const val EMPTY_MARK = "＿"
        private const val PENDING_MARK = "…"
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
