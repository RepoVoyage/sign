package com.repovoyage.sign.language

import android.os.SystemClock
import com.repovoyage.sign.sentence.ConfirmedSentence
import com.repovoyage.sign.sentence.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/**
 * API.md §6.1 — 领域接口（与 ARCHITECTURE.md §2.4.3 一致）。
 */
interface LanguageProcessor {
    fun process(sentence: ConfirmedSentence, preferences: OutputPreferences): Flow<LanguageResult>
}

/** 设置快照：变更（revision 递增）取消旧任务并拒绝旧结果 */
data class OutputPreferences(
    val selectedLanguages: List<LangCode>,    // 默认 [zh-CN]
    val spokenLanguages: List<LangCode>,      // 必须是 selectedLanguages 子集
    val revision: Long,
)

/**
 * P7 语言处理编排（API.md §6 / ARCHITECTURE.md §2.4.6）：
 *
 * - **期限从 FINAL 起算含排队**（[deadlineMs]，云端初始 10s）：出队时已超期
 *   则不调引擎，全部语言 UNAVAILABLE；多语言共享期限，已返回语言不回滚
 * - **1 执行 + 2 待处理**：溢出时最老待处理转显式未处理（UNAVAILABLE），
 *   保留最新表达
 * - **代次失效**：新任务 epoch/设置 revision 更先进 → 旧任务取消（执行中
 *   的迟到结果丢弃、待处理的直接移除，无产出）
 * - **逐语言映射**（单语言优先级 UNAVAILABLE > NEEDS_CONFIRMATION > READY）：
 *   zh-CN 取 polishedChinese，其他取 translations[language]；缺文本或
 *   UNAVAILABLE issue → UNAVAILABLE（text=null）；其他 issue →
 *   NEEDS_CONFIRMATION（候选文本保留供人工核对）
 * - **去重**：经 [LanguageResultGate]（(sessionId, segmentId, sentenceRevision,
 *   language) 仅一个有效结果；settingsRevision 落后拒绝）
 * - 引擎失败（异常/超时）→ 该句全部语言 UNAVAILABLE，source=CLOUD
 *   （仅云端，无本地降级——2026-09-23 用户决定本地引擎砍掉）
 *
 * 上下文衔接：已处理句按序保留最近 [MAX_CONTEXT] 条，作为后续请求的
 * contextSentences（已确认句子，§6.2）。
 */
class LanguageProcessorImpl(
    private val engine: LlmPolisher,
    private val scope: CoroutineScope,
    private val monoMs: () -> Long = SystemClock::elapsedRealtime,
    private val deadlineMs: Long = CLOUD_DEADLINE_MS,
    private val gate: LanguageResultGate = LanguageResultGate(),
) : LanguageProcessor {

    private class Job(
        val sentence: ConfirmedSentence,
        val preferences: OutputPreferences,
        val enqueuedAtMonoMs: Long,
        @Volatile var cancelled: Boolean = false,
        val results: Channel<LanguageResult> = Channel(Channel.UNLIMITED),
    )

    /** 待处理（执行中的单独持有），队首为最老 */
    private val queue = ArrayDeque<Job>()
    private var executing: Job? = null
    private val history = ArrayDeque<ConfirmedSentence>()

    override fun process(sentence: ConfirmedSentence, preferences: OutputPreferences): Flow<LanguageResult> {
        require(preferences.selectedLanguages.isNotEmpty())
        require(preferences.spokenLanguages.all { it in preferences.selectedLanguages })
        val job = Job(sentence, preferences, monoMs())
        synchronized(this) {
            cancelSuperseded(sentence.sequenceEpoch, preferences.revision)
            while (queue.size >= MAX_PENDING) failOldest("溢出：1 执行 + 2 待处理")
            queue.addLast(job)
            if (executing == null) pump()
        }
        return job.results.receiveAsFlow()
    }

    // ---------------------------------------------------------------- 执行

    private fun pump() {
        val next = queue.removeFirstOrNull() ?: return
        executing = next
        scope.launch { execute(next) }
    }

    private suspend fun execute(job: Job) {
        val deadline = job.enqueuedAtMonoMs + deadlineMs
        val output = try {
            // 出队即超期：不调引擎（期限从 FINAL 起算含排队）
            if (monoMs() >= deadline) throw CloudPolishException(
                CloudPolishException.DEADLINE_EXCEEDED, false, "排队超期",
            )
            engine.polish(toInput(job), deadline)
        } catch (_: Exception) {
            null    // 引擎失败：全部语言 UNAVAILABLE
        }
        val elapsedMs = monoMs() - job.enqueuedAtMonoMs
        if (!job.cancelled) emit(job, output, elapsedMs)
        job.results.close()
        synchronized(this) {
            if (executing === job) executing = null
            pump()
        }
    }

    private fun emit(job: Job, output: PolishOutput?, elapsedMs: Long) {
        val s = job.sentence
        for (language in job.preferences.selectedLanguages) {
            val (text, status) = mapLanguage(language, output)
            val result = LanguageResult(
                sessionId = s.sessionId,
                streamGeneration = s.streamGeneration,
                sequenceEpoch = s.sequenceEpoch,
                segmentId = s.segmentId,
                sentenceRevision = s.revision,
                settingsRevision = job.preferences.revision,
                language = language,
                text = text,
                status = status,
                source = OutputSource.CLOUD,
                elapsedMs = elapsedMs,
            )
            if (gate.accept(result)) job.results.trySend(result)
        }
    }

    /** 单语言优先级 UNAVAILABLE > NEEDS_CONFIRMATION > READY */
    private fun mapLanguage(language: LangCode, output: PolishOutput?): Pair<String?, OutputStatus> {
        if (output == null) return null to OutputStatus.UNAVAILABLE
        val text = if (language.tag == "zh-CN") output.polishedChinese
        else output.translations[language]
        val issues = output.issues.filter { it.language == language }
        return when {
            text == null || issues.any { it.code == FidelityIssueCode.UNAVAILABLE } ->
                null to OutputStatus.UNAVAILABLE
            issues.isNotEmpty() -> text to OutputStatus.NEEDS_CONFIRMATION
            else -> text to OutputStatus.READY
        }
    }

    private fun toInput(job: Job): PolishInput {
        val context = history.takeLast(MAX_CONTEXT)
        val s = job.sentence
        history.addLast(s)
        if (history.size > MAX_CONTEXT) history.removeFirst()
        return PolishInput(
            sessionId = s.sessionId,
            segmentId = s.segmentId,
            revision = s.revision,
            rawChinese = s.rawChinese,
            targetLanguages = job.preferences.selectedLanguages,
            contextSentences = context,
        )
    }

    // ---------------------------------------------------------------- 失效

    /** 新任务更先进（epoch / 设置 revision）→ 旧任务取消，迟到结果丢弃 */
    private fun cancelSuperseded(epoch: Long, revision: Long) {
        val superseded = queue.filter {
            it.sentence.sequenceEpoch < epoch || it.preferences.revision < revision
        }
        queue.removeAll(superseded.toSet())
        superseded.forEach {
            it.cancelled = true
            it.results.close()
        }
        executing?.let {
            if (it.sentence.sequenceEpoch < epoch || it.preferences.revision < revision) {
                it.cancelled = true    // 执行中：结果到达时丢弃
            }
        }
    }

    /** 最老待处理转显式未处理（UNAVAILABLE）并出队 */
    private fun failOldest(reason: String) {
        val oldest = queue.removeFirstOrNull() ?: return
        val elapsedMs = monoMs() - oldest.enqueuedAtMonoMs
        emit(oldest, null, elapsedMs)   // 全部语言 UNAVAILABLE
        oldest.results.close()
    }

    private companion object {
        const val CLOUD_DEADLINE_MS = 10_000L   // §6.2 云端每句期限【初始值】
        const val MAX_PENDING = 2               // 1 执行 + 2 待处理
        const val MAX_CONTEXT = 5               // §6.2 衔接上下文上限
    }
}
