package com.repovoyage.sign.recognition

import android.content.ContentResolver
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

/** One isolated first-person sign per MP4. This is a manual integration test, not the P6 stream. */
class LocalVideoCvClient(private val client: OkHttpClient = OkHttpClient()) {
    suspend fun recognize(resolver: ContentResolver, uri: Uri, token: String): CvResult =
        withContext(Dispatchers.IO) {
            require(token.isNotBlank()) { "请填写 CV 服务令牌" }
            val bytes = readBoundedVideo(resolver, uri)
            val request = Request.Builder()
                .url(RECOGNIZE_URL)
                .header("Authorization", "Bearer ${token.trim()}")
                .post(bytes.toRequestBody("video/mp4".toMediaType()))
                .build()
            client.newBuilder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .writeTimeout(90, TimeUnit.SECONDS)
                .readTimeout(90, TimeUnit.SECONDS)
                .callTimeout(90, TimeUnit.SECONDS)
                .build()
                .newCall(request).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        val error = runCatching { JSONObject(body).optString("error") }.getOrNull()
                        throw IOException("CV HTTP ${response.code}${error?.takeIf { it.isNotBlank() }?.let { ": $it" } ?: ""}")
                    }
                    parseCvResponse(body)
                }
        }

    private fun readBoundedVideo(resolver: ContentResolver, uri: Uri): ByteArray {
        val input = resolver.openInputStream(uri) ?: throw IOException("无法打开所选视频")
        return input.use { stream ->
            val output = ByteArrayOutputStream()
            val chunk = ByteArray(64 * 1024)
            while (true) {
                val count = stream.read(chunk)
                if (count < 0) break
                if (output.size().toLong() + count > MAX_BYTES) throw IOException("视频超过 32 MiB")
                output.write(chunk, 0, count)
            }
            if (output.size() == 0) throw IOException("视频文件为空")
            output.toByteArray()
        }
    }

    private companion object {
        const val RECOGNIZE_URL = "https://101.37.234.129/v1/recognize"
        const val MAX_BYTES = 32L * 1024 * 1024
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
