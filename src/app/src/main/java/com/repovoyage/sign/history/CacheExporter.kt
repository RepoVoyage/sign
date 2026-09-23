package com.repovoyage.sign.history

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import kotlinx.coroutines.flow.first
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 缓存导出（§2.6）：用户主动触发，写 App 私有 cacheDir/exports 后经
 * FileProvider + 系统分享机制交出；不常驻公共目录，导出后的文件由接收方
 * 管理。CSV 走域层 [exportCsv]（RFC 4180 转义 + 公式前缀防护已覆盖）。
 * authority 用运行时包名派生——training flavor 带 .training 后缀，两包
 * 同机共存不冲突。
 */
class CacheExporter(
    private val context: Context,
    private val cache: SentenceCache,
) {

    suspend fun exportJsonFile(): File =
        writeExport("json") { entries -> exportJson(entries) }

    /** 每语言结果一行；无结果的句子保留一行（语言列为空） */
    suspend fun exportCsvFile(): File = writeExport("csv") { entries ->
        val header = listOf(
            "sessionId", "segmentId", "wallTimeStart", "wallTimeEnd", "rawChinese",
            "language", "text", "status", "source", "processedAtWallMs",
        )
        val rows = entries.flatMap { e ->
            val head = listOf(
                e.sentence.sessionId, e.sentence.segmentId,
                e.sentence.wallTimeStart.toString(), e.sentence.wallTimeEnd.toString(),
                e.sentence.rawChinese,
            )
            if (e.results.isEmpty()) {
                listOf(head + listOf("", "", "", "", ""))
            } else {
                e.results.map { r ->
                    head + listOf(
                        r.language.tag, r.text ?: "", r.status, r.source,
                        r.processedAtWallMs.toString(),
                    )
                }
            }
        }
        exportCsv(header, rows)
    }

    fun shareIntent(file: File, mime: String): Intent = Intent(Intent.ACTION_SEND).apply {
        type = mime
        putExtra(
            Intent.EXTRA_STREAM,
            FileProvider.getUriForFile(context, context.packageName + AUTHORITY_SUFFIX, file),
        )
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    private suspend fun writeExport(
        ext: String,
        render: (List<SentenceWithResults>) -> String,
    ): File {
        val entries = cache.observeHistory().first()
        val dir = File(context.cacheDir, EXPORT_DIR).apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        return File(dir, "sentence-cache-$stamp.$ext").apply { writeText(render(entries)) }
    }

    companion object {
        const val AUTHORITY_SUFFIX = ".fileprovider"
        const val JSON_MIME = "application/json"
        const val CSV_MIME = "text/csv"
        private const val EXPORT_DIR = "exports"
    }
}
