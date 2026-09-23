package com.repovoyage.sign.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.repovoyage.sign.R
import com.repovoyage.sign.camera.SessionState
import com.repovoyage.sign.language.OutputStatus
import com.repovoyage.sign.pipeline.PipelinePhase
import com.repovoyage.sign.pipeline.SubtitleLine
import com.repovoyage.sign.sentence.LangCode
import kotlinx.coroutines.delay

/**
 * 主界面 = 纯字幕页（§2.7，apple-design 2026-09-24）：字幕流占满；配对状态
 * 精简为一行三档语义（未连接/连接中/已连接 + 重连与失败可行动提示，中间
 * 技术态不披露）；扫描设备收进顶栏右上角下拉（MainActivity）。
 */
@Composable
fun MainScreen(vm: MainViewModel) {
    val pipeline by vm.pipelineState.collectAsStateWithLifecycle()
    val sessionState by vm.sessionState.collectAsStateWithLifecycle()
    val sessionEvent by vm.sessionEvent.collectAsStateWithLifecycle()
    val voiceUnready by vm.voiceUnreadySpoken.collectAsStateWithLifecycle()
    val repeatCount by vm.repeatPromptCount.collectAsStateWithLifecycle()
    val recognitionAvailable by vm.recognitionAvailable.collectAsStateWithLifecycle()
    val clipMode by vm.clipMode.collectAsStateWithLifecycle()
    val clipStatus by vm.clipStatus.collectAsStateWithLifecycle()

    // 重打提示：自最后一次触发展示 6s（needs_repeat，非待核实标志）
    var showRepeat by remember { mutableStateOf(false) }
    LaunchedEffect(repeatCount) {
        if (repeatCount > 0) {
            showRepeat = true
            delay(6_000)
            showRepeat = false
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // ------------------------------------------------ 配对状态行（三档语义）
        SessionStatusRow(sessionState, sessionEvent, clipStatus)

        // ------------------------------------------------ 翻译控制行
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (pipeline.phase == PipelinePhase.RUNNING) {
                FilledTonalButton(onClick = vm::stopTranslation) {
                    Text(stringResource(R.string.translation_stop))
                }
                // 切片识别（模型 B）：词边界=固定窗口，句边界=用户显式动作
                if (clipMode) {
                    FilledTonalButton(onClick = vm::finishSentence) {
                        Text(stringResource(R.string.finish_sentence))
                    }
                }
            } else {
                Button(
                    onClick = vm::startTranslation,
                    enabled = recognitionAvailable,
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
        if (showRepeat) {
            Row(
                Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(8.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(MaterialTheme.colorScheme.tertiary),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(R.string.repeat_prompt),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
        }

        // ------------------------------------------------ 字幕流（hero）
        SubtitleArea(
            draft = pipeline.draft,
            lines = pipeline.lines,
            pendingConfirm = pipeline.pendingConfirm,
            voiceUnready = voiceUnready,
            emptyHint = stringResource(
                when {
                    sessionState !is SessionState.Streaming -> R.string.subtitle_empty_disconnected
                    pipeline.phase == PipelinePhase.RUNNING -> R.string.subtitle_empty_running
                    else -> R.string.subtitle_empty_idle
                },
            ),
            onDiscard = vm::discardPending,
            onReplay = vm::replay,
            onCorrect = vm::submitCorrection,
            modifier = Modifier.weight(1f).fillMaxWidth(),
        )

        // ------------------------------------------------ 训练采集入口
        if (vm.captureEntry.isAvailable) {
            Row(
                Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilledTonalButton(onClick = { vm.captureEntry.start(vm.getApplication()) }) {
                    Text(stringResource(R.string.capture_start_button))
                }
                FilledTonalButton(onClick = { vm.captureEntry.stop(vm.getApplication()) }) {
                    Text(stringResource(R.string.capture_stop_button))
                }
            }
        }
    }
}

/**
 * 配对状态一行化（2026-09-24 用户决定）：技术中间态（BLE/Wi-Fi/授权/激活…）
 * 不披露，只给三档可行动语义；重连单独显示（用户需知道是否要手动干预）；
 * 会话事件（低电/过热/断连）保留为第二行小字。
 */
@Composable
private fun SessionStatusRow(
    sessionState: SessionState,
    sessionEvent: String?,
    clipStatus: String?,
) {
    val connectedGreen = if (isSystemInDarkTheme()) Color(0xFF30D158) else Color(0xFF34C759)
    val (dotColor, statusText) = when (sessionState) {
        is SessionState.Streaming ->
            connectedGreen to stringResource(R.string.status_connected)
        is SessionState.Reconnecting ->
            MaterialTheme.colorScheme.tertiary to
                stringResource(R.string.status_reconnecting, sessionState.attempt)
        is SessionState.Error ->
            MaterialTheme.colorScheme.error to stringResource(R.string.status_failed)
        SessionState.PausedHot ->
            MaterialTheme.colorScheme.tertiary to stringResource(R.string.status_paused_hot)
        SessionState.Idle ->
            MaterialTheme.colorScheme.onSurfaceVariant to stringResource(R.string.status_disconnected)
        else ->
            MaterialTheme.colorScheme.primary to stringResource(R.string.status_connecting)
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(dotColor),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                statusText,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
            )
            clipStatus?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 10.dp).weight(1f, fill = false),
                )
            }
        }
        if (sessionEvent != null) {
            Text(
                sessionEvent,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 16.dp, top = 2.dp),
            )
        }
    }
}

@Composable
private fun SubtitleArea(
    draft: com.repovoyage.sign.pipeline.DraftLine?,
    lines: List<SubtitleLine>,
    pendingConfirm: List<com.repovoyage.sign.pipeline.PendingConfirmLine>,
    voiceUnready: Set<LangCode>,
    emptyHint: String,
    onDiscard: (String) -> Unit,
    onReplay: (String, LangCode) -> Unit,
    onCorrect: (String, LangCode, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    // 越晚的字幕越在上面（2026-09-23 用户决定）：管线状态保持旧→新，展示层倒序
    LazyColumn(modifier = modifier) {
        if (draft == null && lines.isEmpty() && pendingConfirm.isEmpty()) {
            item(key = "empty") {
                Text(
                    emptyHint,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 28.dp),
                )
            }
        }
        if (draft != null) {
            item(key = "draft") {
                TimelineRow(barColor = MaterialTheme.colorScheme.secondary) {
                    Text(
                        stringResource(R.string.draft_prefix),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                    Text(draft.text, style = YuqiaoType.subtitleDraft)
                }
            }
        }
        items(pendingConfirm, key = { "pending-${it.segmentId}" }) { pending ->
            TimelineRow(barColor = MaterialTheme.colorScheme.tertiary) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            stringResource(R.string.pending_confirm_title),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                        Text(pending.draftText, style = YuqiaoType.subtitleDraft)
                        Text(
                            stringResource(R.string.pending_confirm_reason, pending.reason.name),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = { onDiscard(pending.segmentId) }) {
                        Text(stringResource(R.string.action_discard))
                    }
                }
            }
        }
        items(lines.asReversed(), key = { it.segmentId }) { line ->
            SubtitleLineRow(line, voiceUnready, onReplay, onCorrect)
        }
    }
}

/** 时间轨行：3dp 状态色条（结构信息：序列+状态）+ 内容列 */
@Composable
private fun TimelineRow(
    barColor: Color,
    content: @Composable () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(horizontal = 16.dp),
    ) {
        Box(
            Modifier.width(3.dp).fillMaxHeight()
                .background(barColor, RoundedCornerShape(2.dp)),
        )
        Spacer(Modifier.width(13.dp))
        Column(
            Modifier.weight(1f).padding(vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            content()
        }
    }
}

@Composable
private fun SubtitleLineRow(
    line: SubtitleLine,
    voiceUnready: Set<LangCode>,
    onReplay: (String, LangCode) -> Unit,
    onCorrect: (String, LangCode, String) -> Unit,
) {
    var correcting by remember(line.segmentId) { mutableStateOf<Pair<LangCode, String>?>(null) }
    val needsReview = line.results.values.any {
        it.status == OutputStatus.NEEDS_CONFIRMATION && !it.userConfirmed
    }
    TimelineRow(
        barColor = if (needsReview) MaterialTheme.colorScheme.tertiary
        else MaterialTheme.colorScheme.outlineVariant,
    ) {
        Text(line.rawChinese, style = YuqiaoType.subtitle, fontWeight = FontWeight.SemiBold)
        // 无语言结果是常态（LLM 模块已砍）：不再显示"正在识别…"占位
        line.results.forEach { (language, result) ->
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        languageDisplayName(language),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                    // §2.5.1：语音未就绪的语言仅显示字幕并明确标记
                    if (language in voiceUnready) {
                        Text(
                            stringResource(R.string.voice_unavailable_marker),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 6.dp),
                        )
                    }
                }
                when {
                    result.status == OutputStatus.UNAVAILABLE ->
                        Text(
                            stringResource(R.string.result_unavailable),
                            style = MaterialTheme.typography.bodyLarge,
                            fontStyle = FontStyle.Italic,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    result.status == OutputStatus.NEEDS_CONFIRMATION && !result.userConfirmed ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "${result.text ?: ""}（${stringResource(R.string.result_needs_confirmation)}）",
                                style = MaterialTheme.typography.bodyLarge,
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
                                style = MaterialTheme.typography.bodyLarge,
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
                Text(rawChinese, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
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
