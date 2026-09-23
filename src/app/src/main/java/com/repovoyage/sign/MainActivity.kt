package com.repovoyage.sign

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.repovoyage.sign.camera.SessionState
import com.repovoyage.sign.settings.AppSettings
import com.repovoyage.sign.ui.HistoryScreen
import com.repovoyage.sign.ui.HistoryViewModel
import com.repovoyage.sign.ui.MainScreen
import com.repovoyage.sign.ui.MainViewModel
import com.repovoyage.sign.ui.SettingsScreen
import com.repovoyage.sign.ui.SettingsViewModel
import com.repovoyage.sign.ui.SignTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 单 Activity 宿主（ARCHITECTURE §3.2：Compose + MVVM/StateFlow）。
 * 共享 Scaffold：顶部品牌栏 + 底部三页导航（翻译/历史/设置，§导航可预期）。
 * 2026-09-24 apple-design 重构：设置/历史为 master-detail（行标题→子页，
 * 详情态由本层持有，顶栏变返回箭头）；扫描设备收入主屏顶栏右上角下拉。
 */
class MainActivity : ComponentActivity() {

    private val mainVm: MainViewModel by viewModels()
    private val settingsVm: SettingsViewModel by viewModels()
    private val historyVm: HistoryViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            SignTheme {
                AppNav(mainVm, settingsVm, historyVm)
            }
        }
    }
}

private enum class Tab { MAIN, HISTORY, SETTINGS }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppNav(
    mainVm: MainViewModel,
    settingsVm: SettingsViewModel,
    historyVm: HistoryViewModel,
) {
    var tab by rememberSaveable { mutableStateOf(Tab.MAIN) }
    // master-detail 详情态：null = 各 tab 根列表（设置项为分区标题字符串资源 id）
    var settingsSection by rememberSaveable { mutableStateOf<Int?>(null) }
    var historyGroupKey by rememberSaveable { mutableStateOf<String?>(null) }
    BackHandler(enabled = settingsSection != null || historyGroupKey != null || tab != Tab.MAIN) {
        when {
            settingsSection != null -> settingsSection = null
            historyGroupKey != null -> historyGroupKey = null
            else -> tab = Tab.MAIN
        }
    }

    val context = LocalContext.current
    var hasPermissions by remember { mutableStateOf(hasRuntimePermissions(context)) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        hasPermissions = grants.values.all { it }
    }

    // §2.6 首次使用告知：缓存内容、保留期限和删除方法
    val settings = remember(context) { (context.applicationContext as SignApp).settings }
    val scope = rememberCoroutineScope()
    var showCacheNotice by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        showCacheNotice = !settings.cacheNoticeAcknowledged.first()
    }

    // 历史页顶栏动作（导出/全部删除在根列表右上角）；弹窗在 HistoryScreen 渲染
    val historyGroups by historyVm.groups.collectAsStateWithLifecycle()
    val historyNames by historyVm.conversationNames.collectAsStateWithLifecycle()
    val hasHistory = historyGroups.any { it.entries.isNotEmpty() }
    val openGroup = historyGroups.find { it.key == historyGroupKey }
    // 详情中的会话被删除 → 自动退回根列表
    LaunchedEffect(historyGroups) {
        if (historyGroupKey != null && openGroup == null) historyGroupKey = null
    }

    // 主屏右上角扫描下拉（2026-09-24 用户决定：扫描设备简约化，不占内容区）
    val sessionState by mainVm.sessionState.collectAsStateWithLifecycle()
    val devices by mainVm.devices.collectAsStateWithLifecycle()
    val scanStatus by mainVm.scanStatus.collectAsStateWithLifecycle()
    var scanMenuOpen by remember { mutableStateOf(false) }
    LaunchedEffect(tab) { scanMenuOpen = false }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    val detailTitle = when {
                        settingsSection != null -> stringResource(settingsSection!!)
                        openGroup != null -> historyVm.displayName(openGroup!!, historyNames)
                        else -> null
                    }
                    if (detailTitle != null) {
                        Text(detailTitle, maxLines = 1)
                    } else {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Image(
                                painter = painterResource(R.drawable.ic_launcher_foreground),
                                contentDescription = null,   // 装饰性品牌标，旁有可见标题
                                modifier = Modifier.size(30.dp),
                            )
                            Text(
                                when (tab) {
                                    Tab.MAIN -> stringResource(R.string.app_name)
                                    Tab.HISTORY -> stringResource(R.string.history_title)
                                    Tab.SETTINGS -> stringResource(R.string.settings_title)
                                },
                            )
                        }
                    }
                },
                navigationIcon = {
                    if (settingsSection != null || historyGroupKey != null) {
                        IconButton(onClick = {
                            settingsSection = null
                            historyGroupKey = null
                        }) {
                            Icon(
                                painterResource(R.drawable.ic_arrow_back),
                                contentDescription = stringResource(R.string.nav_back),
                            )
                        }
                    }
                },
                actions = {
                    if (tab == Tab.MAIN) {
                        Box {
                            IconButton(onClick = {
                                if (sessionState is SessionState.Idle || sessionState is SessionState.Error) {
                                    if (hasPermissions) {
                                        mainVm.startScan()
                                        scanMenuOpen = true
                                    } else {
                                        permissionLauncher.launch(requiredPermissions())
                                    }
                                } else {
                                    scanMenuOpen = true
                                }
                            }) {
                                Icon(
                                    painterResource(R.drawable.ic_scan),
                                    contentDescription = stringResource(R.string.scan_action),
                                )
                            }
                            DropdownMenu(
                                expanded = scanMenuOpen,
                                onDismissRequest = { scanMenuOpen = false },
                            ) {
                                if (sessionState !is SessionState.Idle && sessionState !is SessionState.Error) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.menu_disconnect)) },
                                        onClick = {
                                            mainVm.stopSession()
                                            scanMenuOpen = false
                                        },
                                    )
                                } else {
                                    devices.forEachIndexed { index, device ->
                                        DropdownMenuItem(
                                            text = { Text(mainVm.deviceLabel(device, index)) },
                                            onClick = {
                                                mainVm.connect(device)
                                                scanMenuOpen = false
                                            },
                                        )
                                    }
                                    if (scanStatus.isNotEmpty()) {
                                        DropdownMenuItem(
                                            text = {
                                                Text(
                                                    scanStatus,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                            },
                                            enabled = false,
                                            onClick = {},
                                        )
                                    } else if (devices.isEmpty()) {
                                        DropdownMenuItem(
                                            text = {
                                                Text(
                                                    stringResource(R.string.scan_menu_empty),
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                            },
                                            enabled = false,
                                            onClick = {},
                                        )
                                    }
                                }
                            }
                        }
                    }
                    if (tab == Tab.HISTORY && historyGroupKey == null) {
                        IconButton(onClick = historyVm::showExportChooser, enabled = hasHistory) {
                            Icon(
                                painterResource(R.drawable.ic_export),
                                contentDescription = stringResource(R.string.history_export_title),
                            )
                        }
                        IconButton(onClick = historyVm::showDeleteAll, enabled = hasHistory) {
                            Icon(
                                painterResource(R.drawable.ic_delete),
                                contentDescription = stringResource(R.string.history_delete_all),
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    // iOS 导航栏与内容融为一体（apple-design：chrome 退后）
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        bottomBar = {
            // iOS 标签栏：白/#1C1C1E 底、无投影；压低高度（56dp 内容 + 系统手势区）
            val density = LocalDensity.current
            val bottomInset = with(density) { WindowInsets.navigationBars.getBottom(density).toDp() }
            NavigationBar(
                // 纯图标标签栏（2026-09-24 用户决定）：无文字，图标垂直居中
                modifier = Modifier.height(48.dp + bottomInset),
                containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
                tonalElevation = 0.dp,
            ) {
                NavigationBarItem(
                    selected = tab == Tab.MAIN,
                    onClick = { tab = Tab.MAIN },
                    icon = { Icon(painterResource(R.drawable.ic_tab_main), stringResource(R.string.tab_main)) },
                )
                NavigationBarItem(
                    selected = tab == Tab.HISTORY,
                    onClick = { tab = Tab.HISTORY },
                    icon = { Icon(painterResource(R.drawable.ic_tab_history), stringResource(R.string.history_title)) },
                )
                NavigationBarItem(
                    selected = tab == Tab.SETTINGS,
                    onClick = { tab = Tab.SETTINGS },
                    icon = { Icon(painterResource(R.drawable.ic_tab_settings), stringResource(R.string.settings_title)) },
                )
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding)) {
            when (tab) {
                Tab.MAIN -> MainScreen(mainVm)
                Tab.HISTORY -> HistoryScreen(
                    vm = historyVm,
                    openGroupKey = historyGroupKey,
                    onOpenGroup = { historyGroupKey = it },
                )
                Tab.SETTINGS -> SettingsScreen(
                    vm = settingsVm,
                    section = settingsSection,
                    onOpenSection = { settingsSection = it },
                )
            }
        }
    }

    if (showCacheNotice) {
        AlertDialog(
            onDismissRequest = { showCacheNotice = false },
            title = { Text(stringResource(R.string.cache_notice_title)) },
            text = { Text(stringResource(R.string.cache_notice_body)) },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch { settings.acknowledgeCacheNotice() }
                    showCacheNotice = false
                }) { Text(stringResource(R.string.cache_notice_confirm)) }
            },
        )
    }
}

// ---------------------------------------------------------------- 权限（自 P2 面板沿用）

private fun requiredPermissions(): Array<String> {
    val perms = mutableListOf<String>()
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        // manifest 中 BLUETOOTH_SCAN 为 neverForLocation，无需位置权限
        perms += Manifest.permission.BLUETOOTH_SCAN
        perms += Manifest.permission.BLUETOOTH_CONNECT
    } else {
        perms += Manifest.permission.ACCESS_FINE_LOCATION
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        perms += Manifest.permission.POST_NOTIFICATIONS
    }
    return perms.toTypedArray()
}

private fun hasRuntimePermissions(context: Context): Boolean = requiredPermissions().all {
    ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
}
