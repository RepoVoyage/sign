package com.repovoyage.sign.recognition

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 组句 Agent 客户端：按序的成功词级候选组 → 一句话（当前为五句演示语料，
 * 可能返回 null）。[clientProvider] 注入 §2.4.7 蜂窝绑定客户端。
 * 改造自 codex/app-local-video-test 分支（协议不变）。
 */
class LocalVideoComposeClient(private val clientProvider: () -> OkHttpClient = { OkHttpClient() }) {

    suspend fun compose(
        sessionId: String,
        segmentId: String,
        revision: Int,
        gestures: List<CvResult>,
        token: String,
    ): ComposeResult = withContext(Dispatchers.IO) {
        require(token.isNotBlank()) { "请填写 Agent 服务令牌" }
        // 契约：gestures 1–12 项，每项 candidates 1–3 条（空槽不上线，调用方过滤）
        require(gestures.isNotEmpty() && gestures.size <= 12)
        require(gestures.all { it.candidates.isNotEmpty() && it.candidates.size <= 3 })
        val request = Request.Builder()
            .url(COMPOSE_URL)
            .header("Authorization", "Bearer ${token.trim()}")
            .post(buildComposeBody(sessionId, segmentId, revision, gestures)
                .toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        clientProvider().newBuilder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
            .build().newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    val error = runCatching {
                        JSONObject(body).optJSONObject("error")?.let {
                            "${it.optString("code")}: ${it.optString("message")}".trim()
                        }
                    }.getOrNull()
                    throw IOException("Agent HTTP ${response.code}${error?.takeIf { it.isNotBlank() }?.let { ": $it" } ?: ""}")
                }
                parseComposeResponse(body)
            }
    }

    private companion object {
        const val COMPOSE_URL = "https://101.37.234.129/v1/compose-signs"
    }
}

data class ComposeResult(
    val sentence: String?,
    val alternatives: List<String>,
    val status: String,
    val needsConfirmation: Boolean,
    val segmentId: String,
    val revision: Int,
)

internal fun buildComposeBody(
    sessionId: String,
    segmentId: String,
    revision: Int,
    gestures: List<CvResult>,
): String = JSONObject()
    .put("sessionId", sessionId)
    .put("segmentId", segmentId)
    .put("revision", revision)
    .put("gestures", JSONArray().apply {
        gestures.forEach { gesture ->
            put(JSONObject().put("candidates", JSONArray().apply {
                gesture.candidates.forEach { candidate ->
                    put(JSONObject().put("label", candidate.label).put("score", candidate.score))
                }
            }))
        }
    })
    .toString()

internal fun parseComposeResponse(body: String): ComposeResult {
    val json = JSONObject(body)
    val alternatives = json.getJSONArray("alternatives")
    return ComposeResult(
        sentence = if (json.isNull("sentence")) null else json.getString("sentence"),
        alternatives = (0 until alternatives.length()).map(alternatives::getString),
        status = json.getString("status"),
        needsConfirmation = json.getBoolean("needsConfirmation"),
        segmentId = json.getString("segmentId"),
        revision = json.getInt("revision"),
    )
}
