package com.repovoyage.sign.ui

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.repovoyage.sign.R
import com.repovoyage.sign.SignApp
import com.repovoyage.sign.history.CacheExporter
import com.repovoyage.sign.history.SentenceWithResults
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import java.text.DateFormat
import java.util.Date

/** 历史对话组（按会话分组；key=sessionId，亦为重命名持久化键） */
data class ConversationGroup(
    val key: String,
    val startMs: Long,
    val sessionId: String,
    val entries: List<SentenceWithResults>,   // 组内新→旧
)

/**
 * 历史界面 VM（§2.6）：观察缓存全量历史，按会话分组；对话可重命名
 * （默认名=起始时间，2026-09-23 用户需求；按时间归并选项同日经用户
 * 决定移除，仅保留按会话）；三级删除 + JSON/CSV 导出分享。
 */
class HistoryViewModel(app: Application) : AndroidViewModel(app) {

    private val signApp = app as SignApp
    private val exporter = CacheExporter(app, signApp.sentenceCache)

    val history: StateFlow<List<SentenceWithResults>> =
        signApp.sentenceCache.observeHistory()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 分组结果（显示名经 [displayName] 解析：重命名优先，默认起始时间） */
    val groups: StateFlow<List<ConversationGroup>> = history
        .map { entries ->
            entries.groupBy { it.sentence.sessionId }.map { (sessionId, list) ->
                ConversationGroup(
                    key = sessionId,
                    startMs = list.last().sentence.wallTimeStart,
                    sessionId = sessionId,
                    entries = list,
                )
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val conversationNames: StateFlow<Map<String, String>> =
        signApp.settings.conversationNames
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /** 对话显示名：重命名优先，默认起始时间 */
    fun displayName(group: ConversationGroup, names: Map<String, String>): String =
        names[group.key] ?: DateFormat.getDateTimeInstance().format(Date(group.startMs))

    fun rename(key: String, name: String) = viewModelScope.launch {
        signApp.settings.renameConversation(key, name)
    }

    fun deleteSegment(sessionId: String, segmentId: String) = viewModelScope.launch {
        signApp.sentenceCache.deleteBySegment(sessionId, segmentId)
    }

    fun deleteGroup(group: ConversationGroup) = viewModelScope.launch {
        signApp.sentenceCache.deleteBySession(group.sessionId)
    }

    fun deleteAll() = viewModelScope.launch {
        signApp.sentenceCache.deleteAll()
    }

    suspend fun exportJsonFile(): File = exporter.exportJsonFile()

    suspend fun exportCsvFile(): File = exporter.exportCsvFile()

    fun shareChooser(file: File): Intent {
        val mime = if (file.name.endsWith(".csv")) CacheExporter.CSV_MIME else CacheExporter.JSON_MIME
        return Intent.createChooser(
            exporter.shareIntent(file, mime),
            getApplication<Application>().getString(R.string.export_chooser_title),
        )
    }
}
