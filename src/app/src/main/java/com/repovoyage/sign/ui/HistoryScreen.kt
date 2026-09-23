package com.repovoyage.sign.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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

/** 待重命名的对话（对话框内编辑草稿） */
private data class PendingRename(val group: ConversationGroup, val current: String)

/**
 * 历史（apple-design master-detail，2026-09-24 用户决定）：根 = 每会话一行
 *（名称 + 句数 + ›），点行进详情（句卡片流 + 重命名/删除该会话）；详情态由
 * MainActivity 持有（顶栏返回）。导出/全部删除为根列表顶栏右上角小图标，
 * 弹窗状态经 [HistoryViewModel] 传递到此处渲染。
 */
@Composable
fun HistoryScreen(
    vm: HistoryViewModel,
    openGroupKey: String?,
    onOpenGroup: (String?) -> Unit,
) {
    val groups by vm.groups.collectAsStateWithLifecycle()
    val names by vm.conversationNames.collectAsStateWithLifecycle()
    val exportChooserVisible by vm.exportChooserVisible.collectAsStateWithLifecycle()
    val deleteAllVisible by vm.deleteAllVisible.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var pendingDeleteGroup by remember { mutableStateOf<ConversationGroup?>(null) }
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

    // 导出格式选择（点击右上角导出图标后弹出）
    if (exportChooserVisible) {
        AlertDialog(
            onDismissRequest = vm::hideExportChooser,
            title = { Text(stringResource(R.string.history_export_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        stringResource(R.string.export_receiver_notice),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = { vm.hideExportChooser(); export(json = true) }) {
                        Text(stringResource(R.string.history_export_json))
                    }
                    TextButton(onClick = { vm.hideExportChooser(); export(json = false) }) {
                        Text(stringResource(R.string.history_export_csv))
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = vm::hideExportChooser) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    // 全部删除确认（点击右上角删除图标后弹出）
    if (deleteAllVisible) {
        AlertDialog(
            onDismissRequest = vm::hideDeleteAll,
            title = { Text(stringResource(R.string.history_delete_all)) },
            text = { Text(stringResource(R.string.history_delete_all_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteAll()
                    vm.hideDeleteAll()
                }) {
                    Text(
                        stringResource(R.string.action_delete),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = vm::hideDeleteAll) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }

    val openGroup = groups.find { it.key == openGroupKey }
    if (openGroupKey == null || openGroup == null) {
        // ------------------------------------------------ 根：会话行列表
        Column(
            modifier = Modifier.fillMaxSize().padding(16.dp),
        ) {
            if (groups.isEmpty()) {
                Text(
                    stringResource(R.string.history_empty),
                    modifier = Modifier.padding(top = 16.dp).fillMaxWidth(),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyColumn {
                    item(key = "groups") {
                        AppleGroupCard {
                            groups.forEachIndexed { index, group ->
                                AppleRow(
                                    title = vm.displayName(group, names),
                                    subtitle = stringResource(
                                        R.string.history_group_count, group.entries.size,
                                    ),
                                ) { onOpenGroup(group.key) }
                                if (index < groups.lastIndex) AppleRowDivider()
                            }
                        }
                    }
                }
            }
        }
    } else {
        // ------------------------------------------------ 详情：句卡片流
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = {
                    pendingRename = PendingRename(openGroup, vm.displayName(openGroup, names))
                }) { Text(stringResource(R.string.history_rename)) }
                TextButton(onClick = { pendingDeleteGroup = openGroup }) {
                    Text(
                        stringResource(R.string.history_delete_group),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(
                    openGroup.entries,
                    key = { it.sentence.sessionId + "|" + it.sentence.segmentId },
                ) { entry ->
                    HistoryEntryCard(entry, onDelete = {
                        vm.deleteSegment(entry.sentence.sessionId, entry.sentence.segmentId)
                    })
                }
            }
        }
    }

    pendingDeleteGroup?.let { group ->
        AlertDialog(
            onDismissRequest = { pendingDeleteGroup = null },
            title = { Text(stringResource(R.string.history_delete_group)) },
            text = { Text(stringResource(R.string.history_delete_group_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteGroup(group)
                    pendingDeleteGroup = null
                }) {
                    Text(
                        stringResource(R.string.action_delete),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteGroup = null }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
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

/** 单句卡片（apple-design：无描边无投影，删除为破坏性红字） */
@Composable
private fun HistoryEntryCard(entry: SentenceWithResults, onDelete: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(entry.sentence.rawChinese, style = YuqiaoType.subtitleDraft, fontWeight = FontWeight.SemiBold)
                    Text(
                        DateFormat.getDateTimeInstance().format(Date(entry.sentence.wallTimeStart)),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = onDelete) {
                    Text(
                        stringResource(R.string.history_delete_entry),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
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
        }
    }
}

private fun languageTagLabel(tag: String): String = when (tag) {
    "zh-CN" -> "中文"
    "en-US" -> "英文"
    "ja-JP" -> "日文"
    else -> tag
}
