package com.repovoyage.sign.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.repovoyage.sign.R
import com.repovoyage.sign.sentence.LangCode

/** 设置内容：字幕/语音语言、识别模型、文本缓存、云端 LLM 凭据 */
@Composable
fun SettingsScreen(vm: SettingsViewModel) {
    val selected by vm.selectedLanguages.collectAsStateWithLifecycle()
    val spoken by vm.spokenLanguages.collectAsStateWithLifecycle()
    val ttsEnabled by vm.ttsEnabled.collectAsStateWithLifecycle()
    val cacheEnabled by vm.cacheEnabled.collectAsStateWithLifecycle()
    val selectedModelId by vm.selectedModelId.collectAsStateWithLifecycle()
    val credentials by vm.llmCredentials.collectAsStateWithLifecycle()
    val urlDraft by vm.urlDraft.collectAsState()
    val keyDraft by vm.keyDraft.collectAsState()
    val modelDraft by vm.modelDraft.collectAsState()
    val cvTokenDraft by vm.cvTokenDraft.collectAsState()
    val agentTokenDraft by vm.agentTokenDraft.collectAsState()
    val clipWindowDraft by vm.clipWindowDraft.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // ------------------------------------------------ 字幕语言
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

        // ------------------------------------------------ 语音语言
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

        // ------------------------------------------------ 识别模型
        SettingsSection(
            title = stringResource(R.string.settings_model_title),
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

        // ------------------------------------------------ 模型 B 识别服务（P6 联调）
        SettingsSection(
            title = stringResource(R.string.settings_recognition_title),
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

        // ------------------------------------------------ 文本缓存
        SettingsSection(
            title = stringResource(R.string.settings_cache_title),
            hint = stringResource(R.string.settings_cache_desc),
        ) {
            SwitchRow(
                label = stringResource(R.string.settings_cache_switch),
                checked = cacheEnabled,
                onCheckedChange = vm::setCacheEnabled,
            )
        }

        // ------------------------------------------------ LLM 凭据
        SettingsSection(
            title = stringResource(R.string.settings_llm_title),
            hint = stringResource(R.string.settings_llm_hint),
        ) {
            Text(
                stringResource(R.string.settings_llm_cost_notice),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            OutlinedTextField(
                value = urlDraft,
                onValueChange = { vm.urlDraft.value = it },
                label = { Text(stringResource(R.string.llm_base_url_label)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = keyDraft,
                onValueChange = { vm.keyDraft.value = it },
                label = { Text(stringResource(R.string.llm_api_key_label)) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = modelDraft,
                onValueChange = { vm.modelDraft.value = it },
                label = { Text(stringResource(R.string.llm_model_label)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = vm::saveCredentials) { Text(stringResource(R.string.llm_save)) }
                Text(
                    stringResource(
                        if (credentials.isConfigured) R.string.llm_configured else R.string.llm_not_configured,
                    ),
                    modifier = Modifier.padding(start = 12.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (credentials.isConfigured) MaterialTheme.colorScheme.secondary
                    else MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

/** 平面分区 + 发丝线（frontend-design 重构：去卡片套件） */
@Composable
private fun SettingsSection(
    title: String,
    hint: String,
    content: @Composable () -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Text(title, style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Column(
            Modifier.padding(vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(hint, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            content()
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
