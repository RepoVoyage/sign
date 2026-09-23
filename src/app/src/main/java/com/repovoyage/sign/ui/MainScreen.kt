package com.repovoyage.sign.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.repovoyage.sign.R
import com.repovoyage.sign.camera.SessionState
import com.repovoyage.sign.camera.sessionStateText
import com.repovoyage.sign.language.OutputStatus
import com.repovoyage.sign.pipeline.PipelinePhase
import com.repovoyage.sign.pipeline.SubtitleLine
import com.repovoyage.sign.sentence.LangCode
import java.util.Locale

/**
 * 主界面内容（§2.7）：会话状态卡 → 翻译控制 → 字幕流（主体）→ 训练采集入口。
 * Scaffold/导航由 MainActivity 统一提供。
 */
@Composable
fun MainScreen(
    vm: MainViewModel,
    hasPermissions: Boolean,
    onRequestPermissions: () -> Unit,
) {
    val pipeline by vm.pipelineState.collectAsStateWithLifecycle()
    val sessionState by vm.sessionState.collectAsStateWithLifecycle()
    val sessionEvent by vm.sessionEvent.collectAsStateWithLifecycle()
    val statsText by vm.statsText.collectAsStateWithLifecycle()
    val scanStatus by vm.scanStatus.collectAsStateWithLifecycle()
    val devices by vm.devices.collectAsStateWithLifecycle()
    val selectedModelId by vm.selectedModelId.collectAsStateWithLifecycle()
    val localVideoTest by vm.localVideoTest.collectAsStateWithLifecycle()
    var cvToken by remember { mutableStateOf("") }
    val videoPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.recognizeLocalVideo(uri, cvToken)
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // ------------------------------------------------ 会话状态卡
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            ),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        sessionStateText(sessionState),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    if (sessionState is SessionState.Streaming) {
                        Text(
                            stringResource(R.string.state_live_badge),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.secondary,
                        )
                    }
                }
                sessionEvent?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (statsText.isNotEmpty()) {
                    Text(statsText, style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { if (hasPermissions) vm.startScan() else onRequestPermissions() },
                    ) { Text(stringResource(R.string.scan_button)) }
                    if (sessionState !is SessionState.Idle) {
                        FilledTonalButton(onClick = vm::stopSession) {
                            Text(stringResource(R.string.stop_button))
                        }
                    }
                }
                if (scanStatus.isNotEmpty()) {
                    Text(scanStatus, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (devices.isNotEmpty()) {
                    // 竖排全宽：长设备名不挤压换行（横排 Row 会把芯片挤成竖条）
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        devices.forEachIndexed { index, device ->
                            FilterChip(
                                selected = false,
                                onClick = { vm.connect(device) },
                                modifier = Modifier.fillMaxWidth(),
                                label = {
                                    Text(
                                        vm.deviceLabel(device, index),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                },
                            )
                        }
                    }
                }
            }
        }

        // ------------------------------------------------ 翻译控制
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (pipeline.phase == PipelinePhase.RUNNING) {
                FilledTonalButton(onClick = vm::stopTranslation) {
                    Text(stringResource(R.string.translation_stop))
                }
            } else {
                Button(
                    onClick = vm::startTranslation,
                    enabled = vm.recognitionAvailable,
                ) { Text(stringResource(R.string.translation_start)) }
            }
            if (pipeline.phase == PipelinePhase.SOURCE_UNAVAILABLE) {
                Text(
                    stringResource(R.string.translation_source_unavailable),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        if (selectedModelId == "model-b") {
            Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("第一人称 · 本地视频测试", style = MaterialTheme.typography.titleMedium)
                    Text("选择一个完整手语词的 MP4；仅显示候选，不进入字幕或自动播报。",
                        style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(
                        value = cvToken,
                        onValueChange = { cvToken = it },
                        label = { Text("CV 服务令牌") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(
                        onClick = { videoPicker.launch(arrayOf("video/mp4")) },
                        enabled = cvToken.isNotBlank() && !localVideoTest.loading && sessionState is SessionState.Idle,
                    ) { Text("选择 MP4 并识别") }
                    if (sessionState !is SessionState.Idle) {
                        Text("请先断开相机，再测试手机里的 MP4。", style = MaterialTheme.typography.bodySmall)
                    }
                    if (localVideoTest.message.isNotEmpty()) Text(localVideoTest.message)
                    localVideoTest.result?.let { result ->
                        Text("状态：${result.status} · ${result.frames} 帧 · 检测到手：${String.format(Locale.ROOT, "%.1f", result.anyHandFraction * 100)}%")
                        result.candidates.forEachIndexed { index, candidate ->
                            Text("${index + 1}. ${candidate.label}  ${String.format(Locale.ROOT, "%.4f", candidate.score)}")
                        }
                        if (result.candidates.isNotEmpty()) {
                            Text("分数未校准，识别结果需要人工确认。", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }

        // 训练版采集入口（production 无入口，P8 验收）
        if (vm.captureEntry.isAvailable) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = { vm.captureEntry.start(vm.getApplication()) }) {
                    Text(stringResource(R.string.capture_start_button))
                }
                FilledTonalButton(onClick = { vm.captureEntry.stop(vm.getApplication()) }) {
                    Text(stringResource(R.string.capture_stop_button))
                }
            }
        }

        // ------------------------------------------------ 字幕流（主体）
        SubtitleArea(
            draft = pipeline.draft,
            lines = pipeline.lines,
            pendingConfirm = pipeline.pendingConfirm,
            onDiscard = vm::discardPending,
            onReplay = vm::replay,
            onCorrect = vm::submitCorrection,
            modifier = Modifier.weight(1f).fillMaxWidth(),
        )
    }
}

@Composable
private fun SubtitleArea(
    draft: com.repovoyage.sign.pipeline.DraftLine?,
    lines: List<SubtitleLine>,
    pendingConfirm: List<com.repovoyage.sign.pipeline.PendingConfirmLine>,
    onDiscard: (String) -> Unit,
    onReplay: (String, LangCode) -> Unit,
    onCorrect: (String, LangCode, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    // 越晚的字幕越在上面（2026-09-23 用户决定）：管线状态保持旧→新，展示层倒序
    LazyColumn(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (draft != null) {
            item(key = "draft") {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    ),
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            stringResource(R.string.draft_prefix),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                        Text(draft.text, style = YuqiaoType.subtitleDraft,
                            color = MaterialTheme.colorScheme.onSecondaryContainer)
                    }
                }
            }
        }
        items(pendingConfirm, key = { "pending-${it.segmentId}" }) { pending ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                ),
            ) {
                Row(
                    Modifier.padding(16.dp).fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            stringResource(R.string.pending_confirm_title),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                        )
                        Text(pending.draftText, style = YuqiaoType.subtitleDraft,
                            color = MaterialTheme.colorScheme.onTertiaryContainer)
                        Text(
                            stringResource(R.string.pending_confirm_reason, pending.reason.name),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                        )
                    }
                    TextButton(onClick = { onDiscard(pending.segmentId) }) {
                        Text(stringResource(R.string.action_discard))
                    }
                }
            }
        }
        items(lines.asReversed(), key = { it.segmentId }) { line ->
            SubtitleLineCard(line, onReplay, onCorrect)
        }
    }
}

@Composable
private fun SubtitleLineCard(
    line: SubtitleLine,
    onReplay: (String, LangCode) -> Unit,
    onCorrect: (String, LangCode, String) -> Unit,
) {
    // 当前打开核对对话框的语言与编辑中的文本
    var correcting by remember(line.segmentId) { mutableStateOf<Pair<LangCode, String>?>(null) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(line.rawChinese, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            if (line.results.isEmpty()) {
                Text(
                    stringResource(R.string.draft_prefix),
                    style = MaterialTheme.typography.bodySmall,
                    fontStyle = FontStyle.Italic,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            line.results.forEach { (language, result) ->
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        languageDisplayName(language),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                    when {
                        result.status == OutputStatus.UNAVAILABLE ->
                            Text(
                                stringResource(R.string.result_unavailable),
                                style = YuqiaoType.subtitle,
                                fontStyle = FontStyle.Italic,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        result.status == OutputStatus.NEEDS_CONFIRMATION && !result.userConfirmed ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "${result.text ?: ""}（${stringResource(R.string.result_needs_confirmation)}）",
                                    style = YuqiaoType.subtitle,
                                    modifier = Modifier.weight(1f, fill = false),
                                )
                                TextButton(onClick = {
                                    correcting = language to (result.text ?: "")
                                }) { Text(stringResource(R.string.action_review)) }
                            }
                        else ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    result.text ?: "",
                                    style = YuqiaoType.subtitle,
                                    modifier = Modifier.weight(1f, fill = false),
                                )
                                if (language in line.unspokenLanguages) {
                                    Text(
                                        stringResource(R.string.unspoken_marker),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.padding(horizontal = 4.dp),
                                    )
                                    TextButton(onClick = { onReplay(line.segmentId, language) }) {
                                        Text(stringResource(R.string.action_replay))
                                    }
                                }
                            }
                    }
                }
            }
        }
    }

    correcting?.let { (language, draftText) ->
        CorrectionDialog(
            rawChinese = line.rawChinese,
            language = language,
            draftText = draftText,
            onDismiss = { correcting = null },
            onSave = { text ->
                onCorrect(line.segmentId, language, text)
                correcting = null
            },
        )
    }
}

/**
 * 核对/纠错对话框（§2.7，2026-09-23 用户决定）：原文只读（§2.4.5 保真基准
 * 不可改），候选文本可编辑；保存即人工确认——有改动落库 source=USER。
 */
@Composable
private fun CorrectionDialog(
    rawChinese: String,
    language: LangCode,
    draftText: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var text by remember(draftText) { mutableStateOf(draftText) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.correction_title, languageDisplayName(language))) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(R.string.correction_original_label),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(rawChinese, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
                Text(
                    stringResource(R.string.correction_candidate_label),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(text) }) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@Composable
fun languageDisplayName(language: LangCode): String = when (language.tag) {
    "zh-CN" -> stringResource(R.string.lang_zh_cn)
    "en-US" -> stringResource(R.string.lang_en_us)
    "ja-JP" -> stringResource(R.string.lang_ja_jp)
    else -> language.tag
}
