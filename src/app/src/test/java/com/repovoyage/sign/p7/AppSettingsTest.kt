package com.repovoyage.sign.p7

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.repovoyage.sign.sentence.LangCode
import com.repovoyage.sign.settings.AppSettings
import com.repovoyage.sign.settings.LlmCredentials
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * AppSettings 约束验收（ARCHITECTURE §2.4.4）：默认值、语音语言 ⊆ 选定语言
 * 自动剪枝、settingsRevision 递增语义、凭据 trim 与齐备判定。
 */
class AppSettingsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var settings: AppSettings

    @Before
    fun setUp() {
        val store = PreferenceDataStoreFactory.create(
            produceFile = { tmp.newFile("app_settings.preferences_pb") },
        )
        settings = AppSettings(store)
    }

    @Test
    fun `默认值符合契约`() = runBlocking {
        assertTrue(settings.cacheEnabled.first())
        assertFalse(settings.cacheNoticeAcknowledged.first())
        assertEquals(listOf(LangCode("zh-CN")), settings.selectedLanguages.first())
        assertEquals(listOf(LangCode("zh-CN")), settings.spokenLanguages.first())
        assertTrue(settings.ttsEnabled.first())
        assertEquals(0L, settings.settingsRevision.first())
        assertFalse(settings.llmCredentials.first().isConfigured)
    }

    @Test
    fun `选定语言变更剪枝语音语言并递增版本号`() = runBlocking {
        settings.setSelectedLanguages(listOf(LangCode("en-US"), LangCode("ja-JP")))
        // 顺序 = 处理优先级
        assertEquals(listOf(LangCode("en-US"), LangCode("ja-JP")), settings.selectedLanguages.first())
        // 原 spoken=[zh-CN] 不再属于选定语言 → 剪枝后回退首选
        assertEquals(listOf(LangCode("en-US")), settings.spokenLanguages.first())
        assertEquals(1L, settings.settingsRevision.first())
    }

    @Test
    fun `语音语言忽略非选定项`() = runBlocking {
        settings.setSelectedLanguages(listOf(LangCode("zh-CN"), LangCode("en-US")))
        settings.setSpokenLanguages(listOf(LangCode("en-US"), LangCode("ja-JP")))
        assertEquals(listOf(LangCode("en-US")), settings.spokenLanguages.first())
        assertEquals(2L, settings.settingsRevision.first())
    }

    @Test
    fun `设置快照组合语言与版本号且后端为云端`() = runBlocking {
        settings.setSelectedLanguages(listOf(LangCode("zh-CN"), LangCode("en-US")))
        settings.setSpokenLanguages(listOf(LangCode("en-US")))
        val prefs = settings.preferences.first()
        assertEquals(listOf(LangCode("zh-CN"), LangCode("en-US")), prefs.selectedLanguages)
        assertEquals(listOf(LangCode("en-US")), prefs.spokenLanguages)
        assertEquals(2L, prefs.revision)
    }

    @Test
    fun `凭据去空白且三项齐备才算已配置`() = runBlocking {
        settings.setLlmCredentials(LlmCredentials(" https://api.example.com/v1 ", " sk-key ", " model-x "))
        val c = settings.llmCredentials.first()
        assertEquals("https://api.example.com/v1", c.baseUrl)
        assertEquals("sk-key", c.apiKey)
        assertEquals("model-x", c.model)
        assertTrue(c.isConfigured)
        settings.setLlmCredentials(LlmCredentials("https://api.example.com/v1", "", "model-x"))
        assertFalse(settings.llmCredentials.first().isConfigured)
    }

    @Test
    fun `语音开关与缓存开关不递增版本号`() = runBlocking {
        settings.setTtsEnabled(false)
        settings.setCacheEnabled(false)
        assertEquals(0L, settings.settingsRevision.first())
        assertFalse(settings.ttsEnabled.first())
        assertFalse(settings.cacheEnabled.first())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `选定语言不得为空`() = runBlocking {
        settings.setSelectedLanguages(emptyList())
    }

    @Test
    fun `首次告知标志单向确认`() = runBlocking {
        settings.acknowledgeCacheNotice()
        assertTrue(settings.cacheNoticeAcknowledged.first())
    }

    @Test
    fun `对话重命名持久化且空名删除`() = runBlocking {
        assertTrue(settings.conversationNames.first().isEmpty())
        settings.renameConversation("k1", " 晨间对话 ")
        settings.renameConversation("k2", "下午")
        val names = settings.conversationNames.first()
        assertEquals("晨间对话", names["k1"])     // trim 生效
        assertEquals("下午", names["k2"])
        settings.renameConversation("k1", "   ")
        assertEquals(null, settings.conversationNames.first()["k1"])   // 空名 = 移除
        assertEquals("下午", settings.conversationNames.first()["k2"])
    }

    @Test
    fun `识别模型选择持久化且不影响语言处理版本号`() = runBlocking {
        assertEquals(null, settings.selectedModelId.first())
        settings.setSelectedModelId("model-b")
        assertEquals("model-b", settings.selectedModelId.first())
        assertEquals(0L, settings.settingsRevision.first())   // 识别层选择不作废语言任务
        settings.setSelectedModelId(null)
        assertEquals(null, settings.selectedModelId.first())
    }
}
