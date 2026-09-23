package com.repovoyage.sign.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.repovoyage.sign.language.OutputPreferences
import com.repovoyage.sign.sentence.LangCode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

private val Context.appSettingsStore: DataStore<Preferences> by preferencesDataStore("app_settings")

/**
 * 应用设置（ARCHITECTURE §2.4.4/§2.6/§6.3）：DataStore 持久化 + Flow 可观察，
 * 不做一次性注入快照。约束：
 * - [OutputPreferences.selectedLanguages] 非空，默认 [zh-CN]；列表顺序 = 处理优先级
 * - [OutputPreferences.spokenLanguages] ⊆ selectedLanguages（选定语言变更时自动剪枝）
 * - 影响语言处理输出的每次变更使 settingsRevision 递增——LanguageProcessor
 *   据此取消旧任务、拒绝旧结果（§2.4.4）
 * - LLM 凭据自持于本机（allowBackup=false，§6.3），发布包不内置密钥
 *
 * 语言处理仅云端（本地引擎按 2026-09-23 用户决定砍掉，无 backend 开关）；
 * 语音总开关 [ttsEnabled] 独立于 spokenLanguages：关闭时管线立即停播（§2.4.4）。
 */
class AppSettings(private val store: DataStore<Preferences>) {

    constructor(context: Context) : this(context.appSettingsStore)

    // ---------------------------------------------------------------- 缓存（§2.6）

    /** 文本缓存开关：默认开启；关闭后持有方停止写入新记录，已存记录由用户处置 */
    val cacheEnabled: Flow<Boolean> = store.data.map { it[KEY_CACHE_ENABLED] ?: true }

    suspend fun setCacheEnabled(enabled: Boolean) {
        store.edit { it[KEY_CACHE_ENABLED] = enabled }
    }

    /** 首次使用告知（缓存内容/保留期限/删除方法）是否已展示并确认 */
    val cacheNoticeAcknowledged: Flow<Boolean> =
        store.data.map { it[KEY_CACHE_NOTICE_ACK] ?: false }

    suspend fun acknowledgeCacheNotice() {
        store.edit { it[KEY_CACHE_NOTICE_ACK] = true }
    }

    // ---------------------------------------------------------------- 语言与语音（§2.4.4）

    val selectedLanguages: Flow<List<LangCode>> =
        store.data.map { parseTags(it[KEY_SELECTED_LANGUAGES]) ?: DEFAULT_LANGUAGES }

    val spokenLanguages: Flow<List<LangCode>> =
        store.data.map { parseTags(it[KEY_SPOKEN_LANGUAGES]) ?: DEFAULT_LANGUAGES }

    /** 语音总开关；关闭时管线立即停止当前播报并清空待播（§2.4.4） */
    val ttsEnabled: Flow<Boolean> = store.data.map { it[KEY_TTS_ENABLED] ?: true }

    /** 设置版本号：语言/凭据每次变更递增，用于作废旧任务与旧结果 */
    val settingsRevision: Flow<Long> = store.data.map { it[KEY_SETTINGS_REVISION] ?: 0L }

    /** 语言处理设置快照（句子提交时取用） */
    val preferences: Flow<OutputPreferences> =
        combine(selectedLanguages, spokenLanguages, settingsRevision) { selected, spoken, revision ->
            OutputPreferences(
                selectedLanguages = selected,
                spokenLanguages = spoken,
                revision = revision,
            )
        }

    suspend fun setSelectedLanguages(languages: List<LangCode>) {
        require(languages.isNotEmpty()) { "selectedLanguages 不得为空" }
        store.edit { prefs ->
            val selected = languages.distinct()
            prefs[KEY_SELECTED_LANGUAGES] = joinTags(selected)
            // 语音语言必须属于选定字幕语言：自动剪枝
            val spoken = parseTags(prefs[KEY_SPOKEN_LANGUAGES]) ?: DEFAULT_LANGUAGES
            prefs[KEY_SPOKEN_LANGUAGES] = joinTags(spoken.filter { it in selected }.ifEmpty { listOf(selected.first()) })
            prefs[KEY_SETTINGS_REVISION] = (prefs[KEY_SETTINGS_REVISION] ?: 0L) + 1
        }
    }

    suspend fun setSpokenLanguages(languages: List<LangCode>) {
        store.edit { prefs ->
            val selected = parseTags(prefs[KEY_SELECTED_LANGUAGES]) ?: DEFAULT_LANGUAGES
            prefs[KEY_SPOKEN_LANGUAGES] = joinTags(languages.distinct().filter { it in selected })
            prefs[KEY_SETTINGS_REVISION] = (prefs[KEY_SETTINGS_REVISION] ?: 0L) + 1
        }
    }

    suspend fun setTtsEnabled(enabled: Boolean) {
        store.edit { it[KEY_TTS_ENABLED] = enabled }
    }

    // ---------------------------------------------------------------- 识别模型选择（P5 产物）

    /** 当前选定的识别模型 id（ModelCatalog）；null = 未选择。推理接入（P6）后即生效 */
    val selectedModelId: Flow<String?> = store.data.map { it[KEY_SELECTED_MODEL_ID] }

    suspend fun setSelectedModelId(modelId: String?) {
        store.edit { prefs ->
            if (modelId == null) prefs.remove(KEY_SELECTED_MODEL_ID)
            else prefs[KEY_SELECTED_MODEL_ID] = modelId
        }
    }

    // ---------------------------------------------------------------- 识别服务凭据与切片窗口（P6 联调，模型 B）

    /**
     * 词级 CV 与组句 Agent 服务令牌（部署方提供，仅存本机，§6.3 同 LLM 密钥
     * 卫生标准）。两项齐备才启用切片识别源；变更不递增 settingsRevision
     * （不影响语言输出任务，避免误清 TTS 队列）。
     */
    val cvServiceToken: Flow<String> = store.data.map { it[KEY_CV_SERVICE_TOKEN] ?: "" }
    val agentServiceToken: Flow<String> = store.data.map { it[KEY_AGENT_SERVICE_TOKEN] ?: "" }

    val recognitionTokens: Flow<RecognitionTokens> =
        combine(cvServiceToken, agentServiceToken) { cv, agent -> RecognitionTokens(cv, agent) }

    suspend fun setRecognitionTokens(cvToken: String, agentToken: String) {
        store.edit {
            it[KEY_CV_SERVICE_TOKEN] = cvToken.trim()
            it[KEY_AGENT_SERVICE_TOKEN] = agentToken.trim()
        }
    }

    /**
     * 固定窗口切片时长（秒）——视频切分定义权在用户（2026-09-23 决定：
     * 固定时长窗口）。开始翻译时读取，变更在下次开始后生效。
     */
    val clipWindowSeconds: Flow<Double> = store.data.map { it[KEY_CLIP_WINDOW_SECONDS] ?: 2.0 }

    suspend fun setClipWindowSeconds(seconds: Double) {
        store.edit { it[KEY_CLIP_WINDOW_SECONDS] = seconds.coerceIn(0.5, 10.0) }
    }

    // ---------------------------------------------------------------- 历史对话重命名

    /**
     * 对话重命名（2026-09-23 用户需求）：key = 分组键（sessionId 或组起始
     * 墙钟毫秒字符串），value = 用户自定义名；未命名的对话默认显示起始时间。
     * 以 JSON 对象存于单个 preference（org.json 运行时可用）。
     */
    val conversationNames: Flow<Map<String, String>> = store.data.map { prefs ->
        val raw = prefs[KEY_CONVERSATION_NAMES] ?: return@map emptyMap()
        runCatching {
            val obj = org.json.JSONObject(raw)
            buildMap {
                obj.keys().forEach { k -> put(k, obj.getString(k)) }
            }
        }.getOrDefault(emptyMap())
    }

    suspend fun renameConversation(key: String, name: String) {
        store.edit { prefs ->
            val obj = runCatching {
                org.json.JSONObject(prefs[KEY_CONVERSATION_NAMES] ?: "{}")
            }.getOrDefault(org.json.JSONObject())
            if (name.isBlank()) obj.remove(key) else obj.put(key, name.trim())
            prefs[KEY_CONVERSATION_NAMES] = obj.toString()
        }
    }

    // ---------------------------------------------------------------- LLM 凭据（§6.3）

    val llmBaseUrl: Flow<String> = store.data.map { it[KEY_LLM_BASE_URL] ?: "" }
    val llmApiKey: Flow<String> = store.data.map { it[KEY_LLM_API_KEY] ?: "" }
    val llmModel: Flow<String> = store.data.map { it[KEY_LLM_MODEL] ?: "" }

    val llmCredentials: Flow<LlmCredentials> =
        combine(llmBaseUrl, llmApiKey, llmModel) { url, key, model -> LlmCredentials(url, key, model) }

    suspend fun setLlmCredentials(credentials: LlmCredentials) {
        store.edit { prefs ->
            prefs[KEY_LLM_BASE_URL] = credentials.baseUrl.trim()
            prefs[KEY_LLM_API_KEY] = credentials.apiKey.trim()
            prefs[KEY_LLM_MODEL] = credentials.model.trim()
            prefs[KEY_SETTINGS_REVISION] = (prefs[KEY_SETTINGS_REVISION] ?: 0L) + 1
        }
    }

    companion object {
        /** UI 可选语言【初始值】：与保真样本集/离线语音准备范围一致，扩充前先过 §2.5.1 */
        val SUPPORTED_LANGUAGES = listOf(LangCode("zh-CN"), LangCode("en-US"), LangCode("ja-JP"))
        val DEFAULT_LANGUAGES = listOf(LangCode("zh-CN"))

        private val KEY_CACHE_ENABLED = booleanPreferencesKey("cache_enabled")
        private val KEY_CACHE_NOTICE_ACK = booleanPreferencesKey("cache_notice_acknowledged")
        private val KEY_SELECTED_LANGUAGES = stringPreferencesKey("selected_languages")
        private val KEY_SPOKEN_LANGUAGES = stringPreferencesKey("spoken_languages")
        private val KEY_TTS_ENABLED = booleanPreferencesKey("tts_enabled")
        private val KEY_SETTINGS_REVISION = longPreferencesKey("settings_revision")
        private val KEY_SELECTED_MODEL_ID = stringPreferencesKey("selected_model_id")
        private val KEY_CV_SERVICE_TOKEN = stringPreferencesKey("cv_service_token")
        private val KEY_AGENT_SERVICE_TOKEN = stringPreferencesKey("agent_service_token")
        private val KEY_CLIP_WINDOW_SECONDS = doublePreferencesKey("clip_window_seconds")
        private val KEY_CONVERSATION_NAMES = stringPreferencesKey("conversation_names")
        private val KEY_LLM_BASE_URL = stringPreferencesKey("llm_base_url")
        private val KEY_LLM_API_KEY = stringPreferencesKey("llm_api_key")
        private val KEY_LLM_MODEL = stringPreferencesKey("llm_model")

        private fun joinTags(languages: List<LangCode>) = languages.joinToString(",") { it.tag }
        private fun parseTags(raw: String?): List<LangCode>? =
            raw?.split(',')?.filter { it.isNotBlank() }?.map { LangCode(it) }?.ifEmpty { null }
    }
}

/** 云端 LLM 直连凭据（§6.3）；三项齐备才视为已配置 */
data class LlmCredentials(val baseUrl: String, val apiKey: String, val model: String) {
    val isConfigured: Boolean
        get() = baseUrl.isNotBlank() && apiKey.isNotBlank() && model.isNotBlank()
}

/** 模型 B 识别服务令牌（词级 CV + 组句 Agent，两个独立令牌）；齐备才启用切片识别 */
data class RecognitionTokens(val cvToken: String, val agentToken: String) {
    val isConfigured: Boolean
        get() = cvToken.isNotBlank() && agentToken.isNotBlank()
}
