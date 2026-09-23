package com.repovoyage.sign.p6

import com.repovoyage.sign.recognition.buildComposeBody
import com.repovoyage.sign.recognition.parseComposeResponse
import com.repovoyage.sign.recognition.parseCvResponse
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 词级 CV / 组句 Agent 协议解析验收（端点契约见 Docs/第一人称本地视频联调.md；
 * 改造自 codex/app-local-video-test 分支测试）。
 */
class LocalVideoClientsTest {

    @Test
    fun `CV 响应解析——OK 带候选`() {
        val result = parseCvResponse(
            """{"status":"OK","frames":42,"any_hand_fraction":0.91,
               "candidates":[{"label":"我","score":0.93},{"label":"你","score":0.41}],
               "needsConfirmation":false}""".trimIndent(),
        )
        assertEquals("OK", result.status)
        assertEquals(42, result.frames)
        assertEquals(0.91, result.anyHandFraction, 1e-9)
        assertEquals(listOf("我", "你"), result.candidates.map { it.label })
        assertEquals(0.93, result.candidates.first().score, 1e-9)
        assertFalse(result.needsConfirmation)
    }

    @Test
    fun `CV 响应解析——拒绝状态候选为空`() {
        val result = parseCvResponse(
            """{"status":"INSUFFICIENT_HAND_DETECTION","frames":30,"any_hand_fraction":0.03,
               "candidates":[],"needsConfirmation":true}""".trimIndent(),
        )
        assertEquals("INSUFFICIENT_HAND_DETECTION", result.status)
        assertTrue(result.candidates.isEmpty())
        assertTrue(result.needsConfirmation)
    }

    @Test
    fun `组句请求体——按序携带候选组`() {
        val gestures = listOf(
            parseCvResponse("""{"status":"OK","frames":10,"any_hand_fraction":0.9,"candidates":[{"label":"我","score":0.9}],"needsConfirmation":false}"""),
            parseCvResponse("""{"status":"OK","frames":12,"any_hand_fraction":0.8,"candidates":[{"label":"回","score":0.7},{"label":"去","score":0.2}],"needsConfirmation":false}"""),
        )
        val body = JSONObject(buildComposeBody("sess-1", "seg-9", 3, gestures))
        assertEquals("sess-1", body.getString("sessionId"))
        assertEquals("seg-9", body.getString("segmentId"))
        assertEquals(3, body.getInt("revision"))
        val array = body.getJSONArray("gestures")
        assertEquals(2, array.length())
        assertEquals("我", array.getJSONObject(0).getJSONArray("candidates").getJSONObject(0).getString("label"))
        assertEquals(2, array.getJSONObject(1).getJSONArray("candidates").length())
    }

    @Test
    fun `组句响应解析——句子与回显`() {
        val result = parseComposeResponse(
            """{"sentence":"我想回家","alternatives":["我要回家"],"status":"OK",
               "needsConfirmation":true,"segmentId":"seg-9","revision":3}""".trimIndent(),
        )
        assertEquals("我想回家", result.sentence)
        assertEquals(listOf("我要回家"), result.alternatives)
        assertTrue(result.needsConfirmation)
        assertEquals("seg-9", result.segmentId)
        assertEquals(3, result.revision)
    }

    @Test
    fun `组句响应解析——五句语料外返回 null 句子`() {
        val result = parseComposeResponse(
            """{"sentence":null,"alternatives":[],"status":"NO_MATCH",
               "needsConfirmation":false,"segmentId":"seg-9","revision":1}""".trimIndent(),
        )
        assertNull(result.sentence)
        assertEquals("NO_MATCH", result.status)
    }
}
