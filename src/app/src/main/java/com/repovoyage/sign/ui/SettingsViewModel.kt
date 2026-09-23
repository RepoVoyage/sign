package com.repovoyage.sign.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.repovoyage.sign.SignApp
import com.repovoyage.sign.recognition.ModelCatalog
import com.repovoyage.sign.sentence.LangCode
import com.repovoyage.sign.settings.AppSettings
import com.repovoyage.sign.settings.LlmCredentials
import com.repovoyage.sign.settings.RecognitionTokens
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 设置界面 VM（§2.4.4/§2.6/§6.3）：语言选择顺序 = 处理优先级；语音语言 ⊆
 * 字幕语言由 AppSettings 约束层保证；识别模型选择在推理接入（P6）后生效。
 */
class SettingsViewModel(app: Application) : AndroidViewModel(app) {

    private val signApp: SignApp = app as SignApp
    private val settings: AppSettings = signApp.settings

    val supportedLanguages = AppSettings.SUPPORTED_LANGUAGES

    val selectedLanguages: StateFlow<List<LangCode>> =
        settings.selectedLanguages.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings.DEFAULT_LANGUAGES)

    val spokenLanguages: StateFlow<List<LangCode>> =
        settings.spokenLanguages.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings.DEFAULT_LANGUAGES)

    val ttsEnabled: StateFlow<Boolean> =
        settings.ttsEnabled.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    val cacheEnabled: StateFlow<Boolean> =
        settings.cacheEnabled.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    val selectedModelId: StateFlow<String?> =
        settings.selectedModelId.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val llmCredentials: StateFlow<LlmCredentials> =
        settings.llmCredentials.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LlmCredentials("", "", ""))

    val recognitionTokens: StateFlow<RecognitionTokens> =
        settings.recognitionTokens.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RecognitionTokens("", ""))

    val clipWindowSeconds: StateFlow<Double> =
        settings.clipWindowSeconds.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 2.0)

    /** 模型目录（两模型均云端部署，按拍摄视角选择） */
    val modelEntries = ModelCatalog.ENTRIES

    /** 语音未就绪 ∩ 语音语言（§2.5.1 标记用）；3s 轮询引擎就绪态 */
    val voiceUnreadySpoken: StateFlow<Set<LangCode>> = combine(
        settings.spokenLanguages,
        settings.ttsEnabled,
        flow {
            while (true) {
                emit(Unit)
                kotlinx.coroutines.delay(3_000)
            }
        },
    ) { spoken, enabled, _ ->
        if (!enabled) emptySet()
        else spoken.filterNot { signApp.ttsSpeaker.isLanguageReady(it) }.toSet()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    // 凭据编辑草稿（保存才写入）
    val urlDraft = MutableStateFlow("")
    val keyDraft = MutableStateFlow("")
    val modelDraft = MutableStateFlow("")
    val cvTokenDraft = MutableStateFlow("")
    val agentTokenDraft = MutableStateFlow("")
    val clipWindowDraft = MutableStateFlow("")
    private var draftsLoaded = false

    init {
        viewModelScope.launch {
            if (!draftsLoaded) {
                draftsLoaded = true
                val c = settings.llmCredentials.first()
                urlDraft.value = c.baseUrl
                keyDraft.value = c.apiKey
                modelDraft.value = c.model
                val t = settings.recognitionTokens.first()
                cvTokenDraft.value = t.cvToken
                agentTokenDraft.value = t.agentToken
                clipWindowDraft.value = settings.clipWindowSeconds.first().toString()
            }
        }
    }

    /** 选定语言：已选则移除（保底一种），未选则按点击顺序追加（顺序 = 优先级） */
    fun toggleSelected(language: LangCode) {
        viewModelScope.launch {
            val current = settings.selectedLanguages.first()
            val next = if (language in current) current - language else current + language
            if (next.isNotEmpty()) settings.setSelectedLanguages(next)
        }
    }

    fun toggleSpoken(language: LangCode) {
        viewModelScope.launch {
            val current = settings.spokenLanguages.first()
            val next = if (language in current) current - language else current + language
            settings.setSpokenLanguages(next)   // 非选定项由 AppSettings 自动过滤
        }
    }

    fun setTtsEnabled(enabled: Boolean) = viewModelScope.launch { settings.setTtsEnabled(enabled) }

    fun setCacheEnabled(enabled: Boolean) = viewModelScope.launch { settings.setCacheEnabled(enabled) }

    fun selectModel(modelId: String) = viewModelScope.launch { settings.setSelectedModelId(modelId) }

    fun saveCredentials() = viewModelScope.launch {
        settings.setLlmCredentials(LlmCredentials(urlDraft.value, keyDraft.value, modelDraft.value))
    }

    /** 保存模型 B 识别服务配置（令牌 + 切片窗口；窗口非法输入保持原值） */
    fun saveRecognitionConfig() = viewModelScope.launch {
        settings.setRecognitionTokens(cvTokenDraft.value, agentTokenDraft.value)
        clipWindowDraft.value.trim().toDoubleOrNull()?.let { settings.setClipWindowSeconds(it) }
    }
}
