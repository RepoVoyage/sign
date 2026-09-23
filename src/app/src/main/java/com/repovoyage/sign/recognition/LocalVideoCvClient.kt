package com.repovoyage.sign.recognition

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 词级 CV 服务客户端（模型 B，队友云端部署，端点/错误语义见
 * Docs/第一人称本地视频联调.md）。一段完整 MP4（≤32MiB，一个手语词）→
 * 最多三个候选 + 状态。
 *
 * [clientProvider] 注入 §2.4.7 蜂窝绑定客户端（相机在线时进程默认网络无公网）；
 * 未就绪时回落进程默认网络。429（服务端限流，真机联调实测会触发）按
 * Retry-After/线性退避重试。改造自 codex/app-local-video-test 分支
 * （原 Uri 文件读取入口随其手动测试界面留在该分支）。
 */
class LocalVideoCvClient(private val clientProvider: () -> OkHttpClient = { OkHttpClient() }) {

    private sealed interface Attempt {
        data class Success(val result: CvResult) : Attempt
        data class RateLimited(val retryAfterMs: Long?) : Attempt
    }

    suspend fun recognizeBytes(bytes: ByteArray, token: String): CvResult {
        require(token.isNotBlank()) { "请填写 CV 服务令牌" }
        if (bytes.isEmpty()) throw IOException("视频片段为空")
        if (bytes.size > MAX_BYTES) throw IOException("视频超过 32 MiB")
        var attempt = 0
        while (true) {
            when (val outcome = attemptOnce(bytes, token)) {
                is Attempt.Success -> return outcome.result
                is Attempt.RateLimited -> {
                    if (attempt >= RATE_LIMIT_RETRIES) {
                        throw IOException("CV HTTP 429：服务端限流，退避重试 $attempt 次后仍被拒")
                    }
                    attempt++
                    val backoff = minOf(outcome.retryAfterMs ?: RATE_LIMIT_BACKOFF_MS * attempt, RATE_LIMIT_MAX_BACKOFF_MS)
                    // 仅状态码与退避时长（无帧内容/凭据，§2.6）：区分 429 风暴与推理慢
                    android.util.Log.i(TAG, "CV 429 限流，退避 ${backoff}ms 后第 $attempt 次重试")
                    delay(backoff)
                }
            }
        }
    }

    private suspend fun attemptOnce(bytes: ByteArray, token: String): Attempt =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(RECOGNIZE_URL)
                .header("Authorization", "Bearer ${token.trim()}")
                .post(bytes.toRequestBody("video/mp4".toMediaType()))
                .build()
            clientProvider().newBuilder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .writeTimeout(90, TimeUnit.SECONDS)
                .readTimeout(90, TimeUnit.SECONDS)
                .callTimeout(90, TimeUnit.SECONDS)
                .build()
                .newCall(request).execute().use { response ->
                    if (response.code == 429) {
                        // Retry-After 秒数（服务端未文档化该头，防御性解析）
                        return@use Attempt.RateLimited(
                            response.header("Retry-After")?.toLongOrNull()?.times(1000),
                        )
                    }
                    val body = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        // 服务端错误详情：error/detail 字段（FastAPI 校验错误为 detail
                        // 数组），无 JSON 结构则截取原始响应——422 等语义拒绝必须
                        // 把服务端理由带上状态行，否则无法定位
                        val detail = runCatching {
                            val json = JSONObject(body)
                            (json.opt("error") ?: json.opt("detail"))?.toString()
                                ?.takeIf { it.isNotBlank() && it != "null" }
                        }.getOrNull() ?: body.take(160).takeIf { it.isNotBlank() }
                        throw IOException("CV HTTP ${response.code}${detail?.let { "：$it" } ?: ""}")
                    }
                    Attempt.Success(parseCvResponse(body))
                }
        }

    private companion object {
        const val TAG = "ClipCv"
        const val RECOGNIZE_URL = "https://101.37.234.129/v1/recognize"
        const val MAX_BYTES = 32L * 1024 * 1024
        const val RATE_LIMIT_RETRIES = 2
        const val RATE_LIMIT_BACKOFF_MS = 1_500L
        const val RATE_LIMIT_MAX_BACKOFF_MS = 8_000L
    }
}

data class CvCandidate(val label: String, val score: Double)

data class CvResult(
    val status: String,
    val frames: Int,
    val anyHandFraction: Double,
    val candidates: List<CvCandidate>,
    val needsConfirmation: Boolean,
)

internal fun parseCvResponse(body: String): CvResult {
    val json = JSONObject(body)
    val candidates = json.getJSONArray("candidates")
    return CvResult(
        status = json.getString("status"),
        frames = json.getInt("frames"),
        anyHandFraction = json.getDouble("any_hand_fraction"),
        candidates = (0 until candidates.length()).map { index ->
            candidates.getJSONObject(index).let { CvCandidate(it.getString("label"), it.getDouble("score")) }
        },
        needsConfirmation = json.getBoolean("needsConfirmation"),
    )
}
