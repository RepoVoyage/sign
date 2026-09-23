package com.repovoyage.sign.history

import org.json.JSONArray
import org.json.JSONObject

/**
 * 缓存导出格式化（API.md §8：用户主动触发，系统分享机制；导出文件的
 * 写入与分享 Intent 由 UI 阶段接线）。CSV 单元格做 RFC 4180 转义并对
 * 公式前缀（=、+、@ 及行首 -）加 ' 防护，避免表格软件执行注入。
 */

fun exportJson(entries: List<SentenceWithResults>): String {
    val sentences = JSONArray()
    for (e in entries) {
        val s = JSONObject()
            .put("sessionId", e.sentence.sessionId)
            .put("segmentId", e.sentence.segmentId)
            .put("streamGeneration", e.sentence.streamGeneration)
            .put("sequenceEpoch", e.sentence.sequenceEpoch)
            .put("revision", e.sentence.revision)
            .put("startPtsUs", e.sentence.startPtsUs)
            .put("endPtsUs", e.sentence.endPtsUs)
            .put("wallTimeStart", e.sentence.wallTimeStart)
            .put("wallTimeEnd", e.sentence.wallTimeEnd)
            .put("rawChinese", e.sentence.rawChinese)
        e.sentence.confidence?.let { s.put("confidence", it.toDouble()) }
        s.put("userConfirmed", e.sentence.userConfirmed)
        val results = JSONArray()
        for (r in e.results) {
            results.put(
                JSONObject()
                    .put("language", r.language.tag)
                    .put("sentenceRevision", r.sentenceRevision)
                    .put("settingsRevision", r.settingsRevision)
                    .put("text", r.text ?: JSONObject.NULL)
                    .put("status", r.status)
                    .put("source", r.source)
                    .put("processedAtWallMs", r.processedAtWallMs),
            )
        }
        s.put("languageResults", results)
        sentences.put(s)
    }
    return JSONObject().put("sentences", sentences).toString()
}

fun exportCsv(header: List<String>, rows: List<List<String>>): String {
    val sb = StringBuilder()
    sb.append(header.joinToString(",") { csvCell(it) }).append('\n')
    for (row in rows) {
        sb.append(row.joinToString(",") { csvCell(it) }).append('\n')
    }
    return sb.toString()
}

private fun csvCell(raw: String): String {
    // 公式前缀防护：= + @ 开头（及 - 开头——行首负号有公式语义）加单引号
    val guarded = when (raw.firstOrNull()) {
        '=', '+', '@', '-' -> "'$raw"
        else -> raw
    }
    val needsQuote = guarded.any { it == ',' || it == '"' || it == '\n' || it == '\r' }
    return if (needsQuote) "\"${guarded.replace("\"", "\"\"")}\"" else guarded
}
