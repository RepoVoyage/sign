package com.repovoyage.sign.tts

import android.os.SystemClock
import com.repovoyage.sign.sentence.LangCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID

/** API.md §7 — TTS 管理器 */
interface TtsManager {
    fun enqueue(request: SpeakRequest): EnqueueResult
    fun stopCurrentAndClearQueue()               // 仅用户停止/关声音/显式重播
    fun clearPendingKeepCurrent()                // 设置变更：清空尚未开始的旧任务，正在播的这句播完（§2.4.4）
    fun replay(sessionId: String, segmentId: String, language: LangCode)  // 用户显式重播，新 utteranceId
    val events: SharedFlow<TtsEvent>
}

sealed interface TtsEvent {
    data class Started(val utteranceId: String) : TtsEvent
    data class Finished(val utteranceId: String) : TtsEvent
    data class Failed(val utteranceId: String, val reason: TtsError) : TtsEvent
    data class MarkedUnspoken(val segmentId: String, val language: LangCode) : TtsEvent  // 超期转字幕"未播报"
}

enum class TtsError { ENGINE_ERROR, WATCHDOG_TIMEOUT, INTERRUPTED }

/** 引擎终结回调三态（onDone/onError/onStop 走同一幂等终结） */
enum class SpeakerOutcome { DONE, ERROR, STOP }

/**
 * TTS 引擎抽象（Android TextToSpeech 的薄封装接口，可测）。
 * 实现须保证：speak 同步 ERROR 后不再回调；stop 后回调 onStop（若有当前）。
 */
interface TtsSpeaker {
    /** 该语言是否已有已验证的离线 Voice */
    fun isLanguageReady(language: LangCode): Boolean

    /** 同步提交一条；false = 同步 ERROR */
    fun speak(utteranceId: String, text: String, language: LangCode): Boolean

    /** 停止当前播报并使后续回调失效 */
    fun stop()

    /** 注册终结回调（引擎线程调用，TtsManager 保证幂等） */
    fun setTerminalListener(listener: (utteranceId: String, outcome: SpeakerOutcome) -> Unit)
}

/**
 * P7 TtsManager（API.md §7 初始值）：串行一次一条（经 [AutoSpeakQueue]），
 * 待播 3 句 / 会话内终身去重；speak 同步 ERROR → [TtsEvent.Failed] 并推进；
 * 终结回调只处理匹配当前 utteranceId 的事件，迟到回调不推进新任务；
 * 引擎看门狗 60s；待播超 5s 未开始 → [TtsEvent.MarkedUnspoken]；正常新句
 * 不用 QUEUE_FLUSH。replay = 停止当前 + 清空 + 绕过重播（新 utteranceId）。
 *
 * 巡检（看门狗/超期）默认由内部 ticker 驱动；测试注入极大 [tickMs] 并
 * 手动调 [checkTimers]。时钟由 [monoMs] 注入。
 */
class TtsManagerImpl(
    private val speaker: TtsSpeaker,
    private val scope: CoroutineScope,
    private val monoMs: () -> Long = SystemClock::elapsedRealtime,
    private val tickMs: Long = 250,
    private val queue: AutoSpeakQueue = AutoSpeakQueue(),
) : TtsManager {

    private val _events = MutableSharedFlow<TtsEvent>(extraBufferCapacity = 128)
    override val events: SharedFlow<TtsEvent> = _events.asSharedFlow()

    private var current: SpeakRequest? = null
    private var currentUtteranceId: String? = null
    private var currentStartedAtMs = 0L

    /** 幂等终结闸：无当前播报（或已终结）时为 true */
    @Volatile
    private var terminalHandled = true

    /** replay 文本账本（replay 入参不带 text） */
    private val lastRequests = LinkedHashMap<String, SpeakRequest>()

    private var tickerJob: Job? = null

    init {
        speaker.setTerminalListener { id, outcome ->
            scope.launch { handleTerminal(id, outcome) }
        }
    }

    override fun enqueue(request: SpeakRequest): EnqueueResult {
        if (!speaker.isLanguageReady(request.language)) {
            return EnqueueResult.Rejected(RejectReason.VOICE_NOT_READY)
        }
        val result = queue.enqueue(request, nowMonoMs = monoMs())
        if (result == EnqueueResult.Queued) {
            remember(request)
            ensureTicker()
            pump()
        }
        return result
    }

    override fun stopCurrentAndClearQueue() {
        queue.clear()
        speaker.stop()   // 当前播报经 onStop 终结（幂等吸收），队列已空不再推进
        maybeStopTicker()
    }

    override fun clearPendingKeepCurrent() {
        queue.clear()
        maybeStopTicker()   // 有当前播报时 ticker 保留（看门狗仍需巡检）
    }

    override fun replay(sessionId: String, segmentId: String, language: LangCode) {
        val request = lastRequests[key(sessionId, segmentId, language)] ?: return
        queue.clear()
        speaker.stop()
        if (queue.enqueue(request, nowMonoMs = monoMs(), bypassDedup = true) == EnqueueResult.Queued) {
            ensureTicker()
            pump()
        }
    }

    /**
     * 巡检：引擎看门狗（60s 无终结 → Failed 并 stop 引擎）与待播超期
     * （5s 未开始 → MarkedUnspoken 并移出待播）。
     */
    fun checkTimers(nowMonoMs: Long = monoMs()) {
        if (current != null && !terminalHandled && nowMonoMs - currentStartedAtMs >= WATCHDOG_MS) {
            val id = currentUtteranceId
            finishTerminal(id, TtsError.WATCHDOG_TIMEOUT)
            speaker.stop()   // 唤醒引擎侧；迟到回调由幂等闸吸收
        }
        queue.checkOverdue(nowMonoMs).forEach {
            _events.tryEmit(TtsEvent.MarkedUnspoken(it.segmentId, it.language))
        }
    }

    // ---------------------------------------------------------------- 内部

    private fun pump() {
        if (current != null) return
        val next = queue.pollNext() ?: run {
            maybeStopTicker()
            return
        }
        val utteranceId = UUID.randomUUID().toString()
        current = next
        currentUtteranceId = utteranceId
        currentStartedAtMs = monoMs()
        terminalHandled = false
        if (speaker.speak(utteranceId, next.text, next.language)) {
            _events.tryEmit(TtsEvent.Started(utteranceId))
        } else {
            finishTerminal(utteranceId, TtsError.ENGINE_ERROR)   // 同步 ERROR：引擎不再回调
        }
    }

    /** 终结回调（引擎线程 hop 到 scope）：只认当前 utteranceId，幂等 */
    private suspend fun handleTerminal(utteranceId: String, outcome: SpeakerOutcome) {
        if (utteranceId != currentUtteranceId || terminalHandled) return
        finishTerminal(
            utteranceId,
            when (outcome) {
                SpeakerOutcome.DONE -> null
                SpeakerOutcome.ERROR -> TtsError.ENGINE_ERROR
                SpeakerOutcome.STOP -> TtsError.INTERRUPTED
            },
        )
    }

    private fun finishTerminal(utteranceId: String?, error: TtsError?) {
        terminalHandled = true
        current = null
        currentUtteranceId = null
        if (utteranceId != null) {
            if (error == null) _events.tryEmit(TtsEvent.Finished(utteranceId))
            else _events.tryEmit(TtsEvent.Failed(utteranceId, error))
        }
        queue.onPlaybackFinished()
        pump()
    }

    private fun remember(request: SpeakRequest) {
        lastRequests[key(request.sessionId, request.segmentId, request.language)] = request
        if (lastRequests.size > MAX_HISTORY) {
            val eldest = lastRequests.keys.first()
            lastRequests.remove(eldest)
        }
    }

    private fun key(sessionId: String, segmentId: String, language: LangCode) =
        "$sessionId|$segmentId|${language.tag}"

    private fun ensureTicker() {
        if (tickerJob?.isActive == true) return
        tickerJob = scope.launch {
            while (isActive && tickMs < Long.MAX_VALUE) {
                delay(tickMs)
                checkTimers()
            }
        }
    }

    private fun maybeStopTicker() {
        if (current == null && !queue.hasBacklog) tickerJob?.cancel()
    }

    private companion object {
        const val WATCHDOG_MS = 60_000L
        const val MAX_HISTORY = 64
    }
}
