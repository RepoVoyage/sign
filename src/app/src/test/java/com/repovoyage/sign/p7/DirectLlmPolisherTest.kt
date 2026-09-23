package com.repovoyage.sign.p7

import com.repovoyage.sign.language.CloudPolishException
import com.repovoyage.sign.language.DirectLlmPolisher
import com.repovoyage.sign.language.FidelityIssueCode
import com.repovoyage.sign.language.OutputSource
import com.repovoyage.sign.language.PolishInput
import com.repovoyage.sign.sentence.ConfirmedSentence
import com.repovoyage.sign.sentence.LangCode
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * P7 直连 LLM 的 LlmPolisher（agent/app/service.py 移植）× Chat Completions
 * 契约联调（真实 loopback HTTP）。覆盖：请求组装、正常映射、guard 保真启发式
 * （数字变化 / 中文冒充外语）、上游错误映射、content 非法、期限耗尽。
 */
class DirectLlmPolisherTest {

    private lateinit var server: AgentServer

    @Before
    fun setUp() {
        server = AgentServer()
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun polisher() = DirectLlmPolisher(
        baseUrl = "http://127.0.0.1:${server.port}/v1",
        apiKey = "llm-key",
        model = "test-model",
        monoMs = { 1_000L },
    )

    private fun contextSentence(seg: String, text: String) = ConfirmedSentence(
        sessionId = "s-1", streamGeneration = 1, sequenceEpoch = 1,
        segmentId = seg, revision = 1, startPtsUs = 0, endPtsUs = 9_000,
        rawChinese = text, confidence = null, userConfirmed = false,
    )

    private val input = PolishInput(
        sessionId = "s-1", segmentId = "seg-1", revision = 3,
        rawChinese = "我 需要 3 个 帮助",
        targetLanguages = listOf(LangCode("zh-CN"), LangCode("en-US")),
        contextSentences = listOf(contextSentence("seg-0", "你好")),
    )

    /** Chat Completions 200 响应，content = ModelOutput JSON */
    private fun completion(modelOutput: String): AgentServer.ScriptedResponse {
        val body = JSONObject().put(
            "choices",
            JSONArray().put(JSONObject().put("message", JSONObject().put("content", modelOutput))),
        ).toString()
        return AgentServer.ScriptedResponse(200, body)
    }

    private fun modelOutput(
        polished: String?,
        translations: Map<String, String> = emptyMap(),
        issues: List<JSONObject> = emptyList(),
    ): String {
        val root = JSONObject()
        if (polished == null) root.put("polishedChinese", JSONObject.NULL) else root.put("polishedChinese", polished)
        root.put("translations", JSONObject(translations))
        root.put("issues", JSONArray(issues))
        return root.toString()
    }

    @Test
    fun `请求组装与正常映射`() = runBlocking {
        server.start(listOf(completion(modelOutput("我需要 3 个帮助。", mapOf("en-US" to "I need 3 help.")))))
        val out = polisher().polish(input, deadlineMonoMs = 11_000)

        assertEquals("我需要 3 个帮助。", out.polishedChinese)
        assertEquals(mapOf(LangCode("en-US") to "I need 3 help."), out.translations)
        assertTrue(out.issues.isEmpty())
        assertEquals(OutputSource.CLOUD, out.source)

        val req = synchronized(server.requests) { server.requests.single() }
        assertEquals("/v1/chat/completions", req.path)
        assertEquals("Bearer llm-key", req.headers["authorization"])
        JSONObject(req.body).let { body ->
            assertEquals("test-model", body.getString("model"))
            assertEquals(false, body.getBoolean("stream"))
            val messages = body.getJSONArray("messages")
            assertEquals(2, messages.length())
            assertEquals("system", messages.getJSONObject(0).getString("role"))
            assertTrue(messages.getJSONObject(0).getString("content").contains("手语"))
            assertEquals("user", messages.getJSONObject(1).getString("role"))
            val user = JSONObject(messages.getJSONObject(1).getString("content"))
            assertEquals("我 需要 3 个 帮助", user.getString("rawChinese"))
            assertEquals(listOf("zh-CN", "en-US"), user.getJSONArray("targetLanguages").let {
                (0 until it.length()).map { i -> it.getString(i) }
            })
            assertEquals("你好", user.getJSONArray("context").getJSONObject(0).getString("rawChinese"))
        }
    }

    @Test
    fun `guard 数字或否定变化标记 FIDELITY_CHECK_FAILED`() = runBlocking {
        // 译文数字 3→5：外语数字计数不符
        server.start(listOf(completion(modelOutput("我需要 3 个帮助。", mapOf("en-US" to "I need 5 help.")))))
        val out = polisher().polish(input, deadlineMonoMs = 11_000)
        val en = out.issues.first { it.language == LangCode("en-US") }
        assertEquals(FidelityIssueCode.FIDELITY_CHECK_FAILED, en.code)
        // 候选文本保留供人工核对（UNAVAILABLE 才剔除）
        assertEquals("I need 5 help.", out.translations[LangCode("en-US")])

        // 中文数字丢失：raw「3 个」→ polished 无 3
        server2Case("我需要帮助。")
    }

    private suspend fun server2Case(polishedChinese: String) {
        val server2 = AgentServer()
        server2.start(listOf(completion(modelOutput(polishedChinese, mapOf("en-US" to "I need 3 help.")))))
        try {
            val out = DirectLlmPolisher(
                "http://127.0.0.1:${server2.port}/v1", "llm-key", "test-model", monoMs = { 1_000L },
            ).polish(input, deadlineMonoMs = 11_000)
            val zh = out.issues.first { it.language == LangCode("zh-CN") }
            assertEquals(FidelityIssueCode.FIDELITY_CHECK_FAILED, zh.code)
        } finally {
            server2.close()
        }
    }

    @Test
    fun `guard 中文冒充外语时剔除译文并标 UNAVAILABLE`() = runBlocking {
        // en-US 译文 = 原中文原样复制 → 剔除 + UNAVAILABLE
        server.start(listOf(completion(modelOutput("我需要 3 个帮助。", mapOf("en-US" to "我需要 3 个帮助。")))))
        val out = polisher().polish(input, deadlineMonoMs = 11_000)
        assertNull(out.translations[LangCode("en-US")])
        val en = out.issues.first { it.language == LangCode("en-US") }
        assertEquals(FidelityIssueCode.UNAVAILABLE, en.code)
    }

    @Test
    fun `guard 未请求语言视为无效响应`() = runBlocking {
        server.start(listOf(completion(modelOutput("我需要 3 个帮助。", mapOf("ja-JP" to "助けて")))))
        val e = try {
            polisher().polish(input, deadlineMonoMs = 11_000)
            error("应抛出 CloudPolishException")
        } catch (e: CloudPolishException) { e }
        assertEquals(CloudPolishException.INVALID_RESPONSE, e.code)
        assertTrue(e.retryable)
    }

    @Test
    fun `上游 401 与 429 错误映射`() = runBlocking {
        // 上游错误体非 agent 契约格式：按 HTTP 状态映射
        server.start(listOf(
            AgentServer.ScriptedResponse(401, "{}"),
            AgentServer.ScriptedResponse(429, "{}"),
        ))
        val e1 = try {
            polisher().polish(input, deadlineMonoMs = 11_000); error("应抛出")
        } catch (e: CloudPolishException) { e }
        assertEquals("MODEL_AUTH_FAILED", e1.code)
        assertTrue(!e1.retryable)
        val e2 = try {
            polisher().polish(input, deadlineMonoMs = 11_000); error("应抛出")
        } catch (e: CloudPolishException) { e }
        assertEquals("MODEL_RATE_LIMITED", e2.code)
        assertTrue(e2.retryable)
    }

    @Test
    fun `content 非法 JSON 视为无效响应`() = runBlocking {
        server.start(listOf(completion("这不是 JSON")))
        val e = try {
            polisher().polish(input, deadlineMonoMs = 11_000); error("应抛出")
        } catch (e: CloudPolishException) { e }
        assertEquals(CloudPolishException.INVALID_RESPONSE, e.code)
    }

    @Test
    fun `期限已耗尽不发起请求`() = runBlocking {
        server.start(listOf(completion(modelOutput("我需要 3 个帮助。"))))
        val e = try {
            polisher().polish(input, deadlineMonoMs = 1_000); error("应抛出")
        } catch (e: CloudPolishException) { e }
        assertEquals(CloudPolishException.DEADLINE_EXCEEDED, e.code)
        assertTrue(synchronized(server.requests) { server.requests.isEmpty() })
    }
}
