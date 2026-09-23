package com.repovoyage.sign.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.repovoyage.sign.R
import com.repovoyage.sign.history.SentenceWithResults
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/** 待确认的破坏性动作（对话级/全部删除需确认；单句删除即点即删） */
private sealed interface PendingDelete {
    data class Group(val group: ConversationGroup) : PendingDelete
    data object All : PendingDelete
}

/** 待重命名的对话（对话框内编辑草稿） */
private data class PendingRename(val group: ConversationGroup, val current: String)

/**
 * 历史内容（§2.6 + 2026-09-23 用户需求）：分组方式可选 按会话/按时间
 * （30 分钟切分对话）；对话可重命名（默认名=起始时间）；三级删除 + 导出。
 */
@Composable
fun HistoryScreen(vm: HistoryViewModel) {
    val groups by vm.groups.collectAsStateWithLifecycle()
    val names by vm.conversationNames.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var pendingDelete by remember { mutableStateOf<PendingDelete?>(null) }
    var pendingRename by remember { mutableStateOf<PendingRename?>(null) }

    fun export(json: Boolean) {
        scope.launch {
            runCatching {
                val file = if (json) vm.exportJsonFile() else vm.exportCsvFile()
                context.startActivity(vm.shareChooser(file))
            }.onFailure {
                Toast.makeText(context, it.message ?: "export failed", Toast.LENGTH_SHORT).show()
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = { export(json = true) }) {
                Text(stringResource(R.string.history_export_json))
            }
            FilledTonalButton(onClick = { export(json = false) }) {
                Text(stringResource(R.string.history_export_csv))
            }
            FilledTonalButton(
                onClick = { pendingDelete = PendingDelete.All },
                enabled = groups.any { it.entries.isNotEmpty() },
            ) {
                Text(stringResource(R.string.history_delete_all))
            }
        }

        Text(
            stringResource(R.string.export_receiver_notice),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (groups.isEmpty()) {
            Text(
                stringResource(R.string.history_empty),
                modifier = Modifier.padding(top = 32.dp).fillMaxWidth(),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                groups.forEach { group ->
                    item(key = "group-${group.key}") {
                        GroupHeader(
                            name = vm.displayName(group, names),
                            count = group.entries.size,
                            onRename = {
                                pendingRename = PendingRename(group, vm.displayName(group, names))
                            },
                            onDelete = { pendingDelete = PendingDelete.Group(group) },
                        )
                    }
                    items(group.entries, key = { it.sentence.sessionId + "|" + it.sentence.segmentId }) { entry ->
                        HistoryEntryCard(entry, onDelete = {
                            vm.deleteSegment(entry.sentence.sessionId, entry.sentence.segmentId)
                        })
                    }
                }
            }
        }
    }

    when (val pending = pendingDelete) {
        is PendingDelete.Group -> AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.history_delete_group)) },
            text = { Text(stringResource(R.string.history_delete_group_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteGroup(pending.group)
                    pendingDelete = null
                }) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
        PendingDelete.All -> AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.history_delete_all)) },
            text = { Text(stringResource(R.string.history_delete_all_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteAll()
                    pendingDelete = null
                }) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
        null -> Unit
    }

    pendingRename?.let { pending ->
        RenameDialog(
            initial = pending.current,
            onDismiss = { pendingRename = null },
            onSave = { name ->
                vm.rename(pending.group.key, name)
                pendingRename = null
            },
        )
    }
}

@Composable
private fun GroupHeader(
    name: String,
    count: Int,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.tertiary, fontWeight = FontWeight.Bold)
            Text(
                stringResource(R.string.history_group_count, count),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = onRename) { Text(stringResource(R.string.history_rename)) }
        TextButton(onClick = onDelete) { Text(stringResource(R.string.history_delete_group)) }
    }
}

@Composable
private fun RenameDialog(
    initial: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var text by remember(initial) { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.history_rename_title)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onSave(text) }) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

/** 平面行 + 发丝线分隔（frontend-design 重构：去卡片套件） */
@Composable
private fun HistoryEntryCard(entry: SentenceWithResults, onDelete: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(entry.sentence.rawChinese, style = YuqiaoType.subtitleDraft, fontWeight = FontWeight.SemiBold)
                Text(
                    DateFormat.getDateTimeInstance().format(Date(entry.sentence.wallTimeStart)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onDelete) { Text(stringResource(R.string.history_delete_entry)) }
        }
        entry.results.forEach { result ->
            Text(
                buildString {
                    append(languageTagLabel(result.language.tag))
                    append("：")
                    append(
                        when (result.status) {
                            "READY" -> result.text ?: ""
                            "NEEDS_CONFIRMATION" -> "${result.text ?: ""}（待核对）"
                            else -> "不可用"
                        },
                    )
                },
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Spacer(Modifier.height(10.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

private fun languageTagLabel(tag: String): String = when (tag) {
    "zh-CN" -> "中文"
    "en-US" -> "英文"
    "ja-JP" -> "日文"
    else -> tag
}
