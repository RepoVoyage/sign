package com.repovoyage.sign.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.repovoyage.sign.R

/**
 * 设置（apple-design master-detail，2026-09-24 用户决定）：根 = iOS 分组行列表
 *（行尾带当前值摘要 + ›），点行进子页；详情态由 MainActivity 持有（顶栏返回）。
 * section = 分区标题字符串资源 id，null = 根列表。
 */
@Composable
fun SettingsScreen(vm: SettingsViewModel, section: Int?, onOpenSection: (Int) -> Unit) {
    val saveNotice by vm.saveNotice.collectAsStateWithLifecycle()

    // 保存成功弹窗（2026-09-23 用户决定：配置保存后明确告知）
    saveNotice?.let { messageRes ->
        AlertDialog(
            onDismissRequest = vm::dismissSaveNotice,
            title = { Text(stringResource(R.string.save_success_title)) },
            text = { Text(stringResource(messageRes)) },
            confirmButton = {
                TextButton(onClick = vm::dismissSaveNotice) {
                    Text(stringResource(R.string.action_ok))
                }
            },
        )
    }

    if (section == null) SettingsRoot(vm, onOpenSection) else SettingsDetail(vm, section)
}

// ---------------------------------------------------------------- 根：行列表

@Composable
private fun SettingsRoot(vm: SettingsViewModel, onOpenSection: (Int) -> Unit) {
    val selected by vm.selectedLanguages.collectAsStateWithLifecycle()
    val selectedModelId by vm.selectedModelId.collectAsStateWithLifecycle()
    val tokens by vm.recognitionTokens.collectAsStateWithLifecycle()
    val cacheEnabled by vm.cacheEnabled.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        AppleGroupCard {
            AppleRow(
                title = stringResource(R.string.settings_section_language),
                value = selected.map { languageDisplayName(it) }.joinToString("、"),
            ) { onOpenSection(R.string.settings_section_language) }
            AppleRowDivider()
            AppleRow(
                title = stringResource(R.string.settings_model_title),
                value = vm.modelEntries.find { it.id == selectedModelId }?.displayName
                    ?: stringResource(R.string.model_unselected),
            ) { onOpenSection(R.string.settings_model_title) }
            AppleRowDivider()
            AppleRow(
                title = stringResource(R.string.settings_recognition_title),
                value = stringResource(
                    if (tokens.isConfigured) R.string.llm_configured else R.string.llm_not_configured,
                ),
            ) { onOpenSection(R.string.settings_recognition_title) }
            AppleRowDivider()
            AppleRow(
                title = stringResource(R.string.settings_cache_title),
                value = stringResource(if (cacheEnabled) R.string.value_on else R.string.value_off),
            ) { onOpenSection(R.string.settings_cache_title) }
        }
    }
}

// ---------------------------------------------------------------- 子页

@Composable
private fun SettingsDetail(vm: SettingsViewModel, section: Int) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        when (section) {
            R.string.settings_section_language -> LanguageDetail(vm)
            R.string.settings_model_title -> ModelDetail(vm)
            R.string.settings_recognition_title -> RecognitionDetail(vm)
            R.string.settings_cache_title -> CacheDetail(vm)
        }
    }
}

@Composable
private fun LanguageDetail(vm: SettingsViewModel) {
    val selected by vm.selectedLanguages.collectAsStateWithLifecycle()
    val spoken by vm.spokenLanguages.collectAsStateWithLifecycle()
    val ttsEnabled by vm.ttsEnabled.collectAsStateWithLifecycle()

    SettingsSection(
        title = stringResource(R.string.settings_languages_title),
        hint = stringResource(R.string.settings_languages_hint),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            vm.supportedLanguages.forEach { language ->
                val index = selected.indexOf(language)
                FilterChip(
                    selected = index >= 0,
                    onClick = { vm.toggleSelected(language) },
                    label = {
                        Text(
                            if (index >= 0) "${index + 1}. ${languageDisplayName(language)}"
                            else languageDisplayName(language),
                        )
                    },
                )
            }
        }
    }

    SettingsSection(
        title = stringResource(R.string.settings_spoken_title),
        hint = stringResource(R.string.settings_spoken_hint),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            vm.supportedLanguages.forEach { language ->
                FilterChip(
                    selected = language in spoken,
                    enabled = language in selected,
                    onClick = { vm.toggleSpoken(language) },
                    label = { Text(languageDisplayName(language)) },
                )
            }
        }
        SwitchRow(
            label = stringResource(R.string.settings_tts_switch),
            checked = ttsEnabled,
            onCheckedChange = vm::setTtsEnabled,
        )
        val unready by vm.voiceUnreadySpoken.collectAsStateWithLifecycle()
        if (unready.isNotEmpty()) {
            val names = StringBuilder()
            for (lang in unready) {
                if (names.isNotEmpty()) names.append("、")
                names.append(languageDisplayName(lang))
            }
            Text(
                stringResource(R.string.voice_not_ready_list, names.toString()),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ModelDetail(vm: SettingsViewModel) {
    val selectedModelId by vm.selectedModelId.collectAsStateWithLifecycle()
    SettingsSection(
        title = null,
        hint = stringResource(R.string.settings_model_hint),
    ) {
        vm.modelEntries.forEach { entry ->
            Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                androidx.compose.material3.RadioButton(
                    selected = selectedModelId == entry.id,
                    onClick = { vm.selectModel(entry.id) },
                )
                Column(Modifier.weight(1f)) {
                    Text(entry.displayName, style = MaterialTheme.typography.bodyLarge)
                    Text(entry.viewpointHint, style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun RecognitionDetail(vm: SettingsViewModel) {
    val cvTokenDraft by vm.cvTokenDraft.collectAsState()
    val agentTokenDraft by vm.agentTokenDraft.collectAsState()
    val clipWindowDraft by vm.clipWindowDraft.collectAsState()
    SettingsSection(
        title = null,
        hint = stringResource(R.string.settings_recognition_hint),
    ) {
        OutlinedTextField(
            value = cvTokenDraft,
            onValueChange = { vm.cvTokenDraft.value = it },
            label = { Text(stringResource(R.string.cv_token_label)) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = agentTokenDraft,
            onValueChange = { vm.agentTokenDraft.value = it },
            label = { Text(stringResource(R.string.agent_token_label)) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
        val clipWindow by vm.clipWindowSeconds.collectAsStateWithLifecycle()
        OutlinedTextField(
            value = clipWindowDraft,
            onValueChange = { vm.clipWindowDraft.value = it },
            label = { Text(stringResource(R.string.clip_window_label)) },
            supportingText = { Text(stringResource(R.string.clip_window_current, clipWindow)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = vm::saveRecognitionConfig) {
                Text(stringResource(R.string.recognition_save))
            }
            val tokens by vm.recognitionTokens.collectAsStateWithLifecycle()
            Text(
                stringResource(
                    if (tokens.isConfigured) R.string.llm_configured else R.string.llm_not_configured,
                ),
                modifier = Modifier.padding(start = 12.dp),
                style = MaterialTheme.typography.labelMedium,
                color = if (tokens.isConfigured) MaterialTheme.colorScheme.secondary
                else MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun CacheDetail(vm: SettingsViewModel) {
    val cacheEnabled by vm.cacheEnabled.collectAsStateWithLifecycle()
    SettingsSection(
        title = null,
        hint = stringResource(R.string.settings_cache_desc),
    ) {
        SwitchRow(
            label = stringResource(R.string.settings_cache_switch),
            checked = cacheEnabled,
            onCheckedChange = vm::setCacheEnabled,
        )
    }
}

// ---------------------------------------------------------------- 共用

/**
 * iOS 分组列表分区（apple-design 重构）：标题在卡片上方、说明在下方
 *（均 Footnote 灰）；卡片无描边无投影，靠灰底白卡分层。title=null 时
 * 省略标题（子页场景，顶栏已有标题）。
 */
@Composable
private fun SettingsSection(
    title: String?,
    hint: String,
    content: @Composable () -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        if (title != null) {
            Text(
                title,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, bottom = 6.dp),
            )
        }
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        ) {
            Column(
                Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                content()
            }
        }
        Text(
            hint,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 16.dp, top = 6.dp, end = 16.dp),
        )
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Switch(checked = checked, onCheckedChange = onCheckedChange, colors = appleSwitchColors())
    }
}
