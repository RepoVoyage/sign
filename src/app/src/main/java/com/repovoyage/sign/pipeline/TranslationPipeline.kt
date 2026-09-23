package com.repovoyage.sign.pipeline

import com.repovoyage.sign.alert.ConfirmationAlerter
import com.repovoyage.sign.history.SentenceCache
import com.repovoyage.sign.history.toLanguageResultRecord
import com.repovoyage.sign.history.toSentenceRecord
import com.repovoyage.sign.language.CloudPolishException
import com.repovoyage.sign.language.DEFAULT_LLM_CLIENT
import com.repovoyage.sign.language.DirectLlmPolisher
import com.repovoyage.sign.language.LanguageProcessor
import com.repovoyage.sign.language.LanguageResult
import com.repovoyage.sign.language.LlmPolisher
import com.repovoyage.sign.language.OutputSource
import com.repovoyage.sign.language.OutputStatus
import com.repovoyage.sign.language.PolishInput
import com.repovoyage.sign.language.PolishOutput
import com.repovoyage.sign.net.CloudNetworkManager
import com.repovoyage.sign.recognition.RecognitionSource
import com.repovoyage.sign.sentence.BoundaryReliability
import com.repovoyage.sign.sentence.ConfirmReason
import com.repovoyage.sign.sentence.ConfirmedSentence
import com.repovoyage.sign.sentence.LangCode
import com.repovoyage.sign.sentence.RecognitionUpdate
import com.repovoyage.sign.sentence.SentenceEvent
import com.repovoyage.sign.sentence.SentenceManager
import com.repovoyage.sign.settings.AppSettings
import com.repovoyage.sign.settings.LlmCredentials
import com.repovoyage.sign.tts.SpeakRequest
import com.repovoyage.sign.tts.TtsEvent
import com.repovoyage.sign.tts.TtsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.UUID

/** 管线阶段（§6 错误矩阵：模型未就绪 → 禁止开始连续翻译，保留状态界面） */
enum class PipelinePhase { IDLE, RUNNING, SOURCE_UNAVAILABLE }

/** 正在识别的草稿（界面显示"正在识别"，不当作选定语言译文——§2.4.3） */
data class DraftLine(val segmentId: String, val revision: Int, val text: String)

data class SubtitleResult(
    val text: String?,
    val status: OutputStatus,
    val source: OutputSource,
    /** 用户已核对（含纠错）——展示层撤下"待核对"标记 */
    val userConfirmed: Boolean = false,
)

data class PendingConfirmLine(val segmentId: String, val draftText: String, val reason: ConfirmReason)

/** 已确认句字幕行：原文 + 各语言结果 + 未播报标记（§2.5.2 转字幕保留可重播） */
data class SubtitleLine(
    val segmentId: String,
    val rawChinese: String,
    val results: Map<LangCode, SubtitleResult> = emptyMap(),
    val unspokenLanguages: Set<LangCode> = emptySet(),
)

data class SubtitleState(
    val phase: PipelinePhase = PipelinePhase.IDLE,
    val draft: DraftLine? = null,
    val lines: List<SubtitleLine> = emptyList(),               // 旧 → 新
    val pendingConfirm: List<PendingConfirmLine> = emptyList(),
)

/**
 * 运行时凭据引擎（§6.3：凭据走 App 运行时设置，自持、发布包不内置）：
 * 每次 polish 按当前凭据快照构建 DirectLlmPolisher（HTTP 客户端由
 * [clientProvider] 复用，见 §2.4.7）；未配置凭据抛 CloudPolishException →
 * LanguageProcessor 映射全语言 UNAVAILABLE，不伪装译文。
 */
class CredentialLlmPolisher(
    private val credentials: suspend () -> LlmCredentials,
    private val clientProvider: () -> OkHttpClient = { DEFAULT_LLM_CLIENT },
) : LlmPolisher {
    override suspend fun polish(input: PolishInput, deadlineMonoMs: Long): PolishOutput {
        val c = credentials()
        if (!c.isConfigured) {
            throw CloudPolishException(CloudPolishException.MODEL_UNAVAILABLE, false, "LLM 凭据未配置")
        }
        return DirectLlmPolisher(c.baseUrl, c.apiKey, c.model, clientProvider = clientProvider)
            .polish(input, deadlineMonoMs)
    }
}

/**
 * P7 翻译管线编排（ARCHITECTURE §1.2 数据流的 App 侧接线）：
 *
 * 识别源 → [SentenceManager]（段状态机）→ FINAL → [LanguageProcessor]（设置
 * 快照/期限/去重内建）→ 字幕状态 + 缓存写入（开关门控）+ TTS 入队。
 *
 * 行为规则出处：
 * - Finalizing 后等 [finalizeDelayMs]【初始值 2s】再冻结（P6 契约：收尾时限
 *   由上层驱动）
 * - 每句提交时取设置快照；settingsRevision 变更 → 清空尚未开始的旧语音任务，
 *   正在播的这句播完（§2.4.4）；语音总开关关闭 → 立即停止（§2.4.4）
 * - 缓存写入以 FINAL 时刻墙钟捕获时间；写入失败不影响实时字幕（§6 错误矩阵）
 * - READY 且属于语音语言且开关开 → 入队播报（orderKey=源媒体时间，§2.5.2）；
 *   超期未播 → MarkedUnspoken → 字幕标注"未播报"，用户可显式重播
 * - [RecognitionSource.isAvailable]=false → SOURCE_UNAVAILABLE，不启动
 *
 * 初始值简化（真实识别源接线时细化）：单草稿槽（并发段最新者胜）；
 * streamGeneration 固定 0（相机代次随 P6 适配器接入）；pts 账本取
 * tokenSpan/boundary 观测值。
 */
class TranslationPipeline(
    private val source: RecognitionSource,
    private val settings: AppSettings,
    private val processor: LanguageProcessor,
    private val tts: TtsManager,
    private val cache: SentenceCache,
    private val scope: CoroutineScope,
    private val wallMs: () -> Long = System::currentTimeMillis,
    private val finalizeDelayMs: Long = 2_000L,
    /** §2.4.7 蜂窝网络：管线启动且凭据已配置时 acquire，停止时 release */
    private val cloudNetwork: CloudNetworkManager? = null,
    /** §2.7 震动（2026-09-23 用户决定）：仅 LLM 低置信（NEEDS_CONFIRMATION）触发 */
    private val alerter: ConfirmationAlerter? = null,
) {

    private val _state = MutableStateFlow(SubtitleState())
    val state: StateFlow<SubtitleState> = _state.asStateFlow()

    /** 重打提示计数器（needs_repeat，P6 EventMapper 调用）：UI 自最后一次起展示 6s；
     非待核实标志、不震动（2026-09-23 置信度流程定稿） */
    private val _repeatPromptCount = MutableStateFlow(0)
    val repeatPromptCount: StateFlow<Int> = _repeatPromptCount.asStateFlow()

    fun reportNeedsRepeat() {
        _repeatPromptCount.value += 1
    }

    private var runJob: Job? = null
    private var manager: SentenceManager? = null
    private var sessionId: String = ""
    private var lastSettingsRevision = -1L

    /** 段 pts 账本：start=首个 tokenSpan 起点，end=最新 boundary cutoff */
    private val segmentPts = mutableMapOf<String, Pair<Long, Long>>()

    /** 最近语言结果账本（纠错时取原记录整行覆盖）；随会话清空，容量兜底 */
    private val recentResults = LinkedHashMap<Pair<String, LangCode>, LanguageResult>()

    fun start(sessionId: String = UUID.randomUUID().toString()) {
        if (!source.isAvailable) {
            _state.value = SubtitleState(phase = PipelinePhase.SOURCE_UNAVAILABLE)
            return
        }
        stop()
        this.sessionId = sessionId
        val sm = SentenceManager(sessionId, streamGeneration = 0)
        manager = sm
        segmentPts.clear()
        recentResults.clear()
        _state.value = SubtitleState(phase = PipelinePhase.RUNNING)
        runJob = scope.launch {
            launch {
                source.updates.collect { update ->
                    trackPts(update)
                    submit(update, sm)
                }
            }
            launch {
                // §2.4.7：启用云端（凭据已配置）即请求蜂窝网络，等回调不轮询
                if (settings.llmCredentials.first().isConfigured) cloudNetwork?.acquire()
            }
            launch {
                settings.preferences.collect { prefs ->
                    if (lastSettingsRevision >= 0 && prefs.revision != lastSettingsRevision) {
                        tts.clearPendingKeepCurrent()
                        // 凭据中途配置/清除：跟随启停蜂窝请求（幂等）
                        if (settings.llmCredentials.first().isConfigured) cloudNetwork?.acquire()
                        else cloudNetwork?.release()
                    }
                    lastSettingsRevision = prefs.revision
                }
            }
            launch {
                settings.ttsEnabled.collect { enabled -> if (!enabled) tts.stopCurrentAndClearQueue() }
            }
            launch {
                tts.events.collect { event ->
                    if (event is TtsEvent.MarkedUnspoken) markUnspoken(event.segmentId, event.language)
                }
            }
        }
        source.start()
    }

    fun stop() {
        runJob?.cancel()
        runJob = null
        source.stop()
        manager = null
        lastSettingsRevision = -1
        cloudNetwork?.release()   // §2.4.7 第 6 条：结束会话注销蜂窝请求
        _state.value = SubtitleState(phase = PipelinePhase.IDLE)
    }

    /** 用户放弃待核对段（§2.7 核对入口的最小路径；确认/纠错流随识别器完整化） */
    fun discardPending(segmentId: String) {
        val sm = manager ?: return
        handle(sm.discard(segmentId), sm)
    }

    /**
     * 用户核对 LLM 低置信结果（§2.7 疑义核对/纠错入口，2026-09-23 用户决定：
     * 震动提示后由用户修改）：
     * - 文本有改动 → 落库覆盖为 source=USER、status=READY（API.md §6.1）
     * - 原样保存 → 视为人工确认：status=READY，source 保留模型来源
     * - 展示层撤下"待核对"；不自动播报（§2.4.2 规则 12，需要声音由用户显式重播）
     */
    fun submitCorrection(segmentId: String, language: LangCode, text: String) {
        val original = recentResults[segmentId to language] ?: return
        val edited = text != (original.text ?: "")
        val confirmed = original.copy(
            text = text,
            status = OutputStatus.READY,
            source = if (edited) OutputSource.USER else original.source,
        )
        recentResults[segmentId to language] = confirmed
        _state.update { st ->
            st.copy(
                lines = st.lines.map {
                    if (it.segmentId == segmentId) {
                        it.copy(
                            results = it.results + (language to SubtitleResult(
                                text = text,
                                status = OutputStatus.READY,
                                source = confirmed.source,
                                userConfirmed = true,
                            )),
                        )
                    } else {
                        it
                    }
                },
            )
        }
        scope.launch {
            if (settings.cacheEnabled.first()) {
                runCatching { cache.mergeLanguageResult(confirmed.toLanguageResultRecord(wallMs())) }
            }
        }
    }

    /** 用户对"未播报"句显式重播（§2.5.2：不自动补读，重播是用户动作） */
    fun replay(segmentId: String, language: LangCode) {
        tts.replay(sessionId, segmentId, language)
        _state.update { st ->
            st.copy(
                lines = st.lines.map {
                    if (it.segmentId == segmentId) it.copy(unspokenLanguages = it.unspokenLanguages - language)
                    else it
                },
            )
        }
    }

    // ---------------------------------------------------------------- 内部

    private fun trackPts(update: RecognitionUpdate) {
        val id = update.segmentId ?: return
        val spanStart = update.tokenSpans?.firstOrNull()?.startPtsUs
        val spanEnd = update.tokenSpans?.lastOrNull()?.endPtsUs
        val cutoff = update.boundary?.cutoffPtsUs
        val (oldStart, oldEnd) = segmentPts[id] ?: (0L to 0L)
        segmentPts[id] = (spanStart ?: oldStart) to (cutoff ?: spanEnd ?: oldEnd)
    }

    /**
     * 识别更新入口：句子置信度（组合 LLM 给出，[RecognitionUpdate.confidence]）
     * 低于 [LOW_CONFIDENCE_THRESHOLD] 时，边界可靠性强制降为 UNCERTAIN →
     * 段状态机转待核对（唯一待核实标志来源之一，2026-09-23 用户流程）。
     * CV 侧候选分散等不确定由组合 LLM 消化，不直接打扰用户。
     */
    private fun submit(update: RecognitionUpdate, sm: SentenceManager) {
        val effective = if (
            update.confidence != null &&
            update.confidence < LOW_CONFIDENCE_THRESHOLD &&
            update.boundary != null
        ) {
            update.copy(
                boundary = update.boundary.copy(reliability = BoundaryReliability.UNCERTAIN),
            )
        } else {
            update
        }
        handle(sm.submit(effective), sm)
    }

    private fun handle(events: List<SentenceEvent>, sm: SentenceManager) {
        for (event in events) when (event) {
            is SentenceEvent.DraftUpdated ->
                _state.update { it.copy(draft = DraftLine(event.segmentId, event.revision, event.draftText)) }

            is SentenceEvent.Finalizing -> {
                val (startPts, endPts) = segmentPts[event.segmentId] ?: (0L to 0L)
                scope.launch {
                    delay(finalizeDelayMs)
                    if (manager !== sm) return@launch   // 会话已换代/停止：旧段迟到收尾丢弃
                    handle(sm.finalize(event.segmentId, startPts, endPts), sm)
                }
            }

            is SentenceEvent.Final -> onFinal(event.sentence, sm)

            is SentenceEvent.NeedsConfirmation -> {
                // 待核实标志的唯一识别侧来源 = 组合 LLM 句子置信度过低（submit 降级）；
                // 与 guard 保真失败同桶：标志 + 震动
                alerter?.onNeedsConfirmation()
                _state.update { st ->
                    val text = st.draft?.takeIf { it.segmentId == event.segmentId }?.text ?: ""
                    st.copy(
                        pendingConfirm = st.pendingConfirm.filterNot { it.segmentId == event.segmentId } +
                            PendingConfirmLine(event.segmentId, text, event.reason),
                    )
                }
            }

            is SentenceEvent.Interrupted -> _state.update { st ->
                st.copy(
                    draft = st.draft?.takeIf { it.segmentId != event.segmentId },
                    pendingConfirm = st.pendingConfirm.filterNot { it.segmentId == event.segmentId },
                )
            }

            is SentenceEvent.Discarded -> _state.update { st ->
                st.copy(
                    draft = st.draft?.takeIf { it.segmentId != event.segmentId },
                    pendingConfirm = st.pendingConfirm.filterNot { it.segmentId == event.segmentId },
                )
            }
        }
    }

    private fun onFinal(sentence: ConfirmedSentence, sm: SentenceManager) {
        _state.update { st ->
            st.copy(
                draft = st.draft?.takeIf { it.segmentId != sentence.segmentId },
                pendingConfirm = st.pendingConfirm.filterNot { it.segmentId == sentence.segmentId },
                lines = (st.lines + SubtitleLine(sentence.segmentId, sentence.rawChinese)).takeLast(MAX_LINES),
            )
        }
        scope.launch {
            // 缓存写入（§2.6）：FINAL 时刻捕获墙钟；失败不影响实时字幕（§6）
            if (settings.cacheEnabled.first()) {
                runCatching { cache.upsertSentence(sentence.toSentenceRecord(wallMs(), wallMs())) }
            }
            val prefs = settings.preferences.first()    // §2.4.4：每句提交时取设置快照
            processor.process(sentence, prefs).collect { result ->
                if (manager === sm) onResult(sentence, result)
            }
        }
    }

    private suspend fun onResult(sentence: ConfirmedSentence, result: LanguageResult) {
        recentResults[result.segmentId to result.language] = result
        if (recentResults.size > MAX_TRACKED_RESULTS) {
            recentResults.remove(recentResults.keys.first())
        }
        _state.update { st ->
            st.copy(
                lines = st.lines.map {
                    if (it.segmentId == result.segmentId) {
                        it.copy(results = it.results + (result.language to SubtitleResult(result.text, result.status, result.source)))
                    } else {
                        it
                    }
                },
            )
        }
        if (settings.cacheEnabled.first()) {
            runCatching { cache.mergeLanguageResult(result.toLanguageResultRecord(wallMs())) }
        }
        if (result.status == OutputStatus.NEEDS_CONFIRMATION) {
            alerter?.onNeedsConfirmation()   // 限频/勿扰门禁内置于 alerter
        }
        if (result.status == OutputStatus.READY && result.text != null &&
            result.language in settings.spokenLanguages.first() && settings.ttsEnabled.first()
        ) {
            tts.enqueue(
                SpeakRequest(
                    sessionId = sentence.sessionId,
                    segmentId = sentence.segmentId,
                    language = result.language,
                    text = result.text,
                    orderKey = sentence.startPtsUs,   // 源媒体时间序（§2.5.2）
                ),
            )
        }
    }

    private fun markUnspoken(segmentId: String, language: LangCode) {
        _state.update { st ->
            st.copy(
                lines = st.lines.map {
                    if (it.segmentId == segmentId) it.copy(unspokenLanguages = it.unspokenLanguages + language)
                    else it
                },
            )
        }
    }

    private companion object {
        const val MAX_LINES = 50
        const val MAX_TRACKED_RESULTS = 200

        /** 组合 LLM 句子置信度阈值【初始值 0.7，2026-09-23 用户定】；
         低于即待核实+震动；后续随校准集/ModelSpec confidenceCalibration 调整 */
        const val LOW_CONFIDENCE_THRESHOLD = 0.7f
    }
}
