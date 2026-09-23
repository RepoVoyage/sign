package com.repovoyage.sign.tts

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.repovoyage.sign.sentence.LangCode
import java.util.Locale

/**
 * [TtsSpeaker] 的 Android TextToSpeech 实现（§2.5：App 管理播报）。
 *
 * - 初始化异步；未完成前 [isLanguageReady] 一律 false（宁可标记语音不可用，
 *   不假装就绪——§2.5.1）
 * - 就绪判定 = 该 locale 存在**不需要网络连接**的系统 Voice（离线优先，
 *   不隐式依赖联网语音）；缺 Voice 的语言由管线标记"语音不可用"仅显示字幕
 * - 终结回调经 [UtteranceProgressListener] 三态映射，幂等由 TtsManager 保证
 * - 生命周期归持有方：容器销毁时调 [release]
 */
class AndroidTtsSpeaker(context: Context) : TtsSpeaker {

    @Volatile
    private var ready = false

    private var listener: ((utteranceId: String, outcome: SpeakerOutcome) -> Unit)? = null

    // onInit 异步回调晚于构造完成，回调内安全引用
    private val tts: TextToSpeech?

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.setOnUtteranceProgressListener(progressListener)
                ready = true
            }
        }
    }

    override fun isLanguageReady(language: LangCode): Boolean {
        val engine = tts
        if (!ready || engine == null) return false
        val locale = Locale.forLanguageTag(language.tag)
        // 离线 Voice 存在即就绪；引擎未下载该语言数据时不触发隐式下载
        return engine.voices?.any { voice ->
            voice.locale == locale && !voice.isNetworkConnectionRequired
        } == true
    }

    override fun speak(utteranceId: String, text: String, language: LangCode): Boolean {
        val engine = tts
        if (!ready || engine == null) return false
        val locale = Locale.forLanguageTag(language.tag)
        val setLangResult = engine.setLanguage(locale)
        if (setLangResult == TextToSpeech.LANG_MISSING_DATA ||
            setLangResult == TextToSpeech.LANG_NOT_SUPPORTED
        ) {
            return false
        }
        return engine.speak(text, TextToSpeech.QUEUE_ADD, null, utteranceId) == TextToSpeech.SUCCESS
    }

    override fun stop() {
        if (ready) tts?.stop()
    }

    override fun setTerminalListener(listener: (utteranceId: String, outcome: SpeakerOutcome) -> Unit) {
        this.listener = listener
    }

    fun release() {
        ready = false
        tts?.stop()
        tts?.shutdown()
    }

    private val progressListener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) = Unit

        override fun onDone(utteranceId: String?) {
            utteranceId?.let { listener?.invoke(it, SpeakerOutcome.DONE) }
        }

        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String?) {
            utteranceId?.let { listener?.invoke(it, SpeakerOutcome.ERROR) }
        }

        override fun onError(utteranceId: String?, errorCode: Int) {
            utteranceId?.let { listener?.invoke(it, SpeakerOutcome.ERROR) }
        }

        override fun onStop(utteranceId: String?, interrupted: Boolean) {
            utteranceId?.let { listener?.invoke(it, SpeakerOutcome.STOP) }
        }
    }
}
