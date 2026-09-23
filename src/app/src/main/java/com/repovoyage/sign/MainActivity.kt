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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
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
 * 共享 Scaffold：顶部品牌栏 + 底部三页导航（翻译/历史/设置，§导航可预期）；
 * 各屏为纯内容 Composable，VM 由 SignApp 容器供给。
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
    BackHandler(tab != Tab.MAIN) { tab = Tab.MAIN }

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

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
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
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = tab == Tab.MAIN,
                    onClick = { tab = Tab.MAIN },
                    icon = { Icon(painterResource(R.drawable.ic_tab_main), stringResource(R.string.tab_main)) },
                    label = { Text(stringResource(R.string.tab_main)) },
                )
                NavigationBarItem(
                    selected = tab == Tab.HISTORY,
                    onClick = { tab = Tab.HISTORY },
                    icon = { Icon(painterResource(R.drawable.ic_tab_history), stringResource(R.string.history_title)) },
                    label = { Text(stringResource(R.string.history_title)) },
                )
                NavigationBarItem(
                    selected = tab == Tab.SETTINGS,
                    onClick = { tab = Tab.SETTINGS },
                    icon = { Icon(painterResource(R.drawable.ic_tab_settings), stringResource(R.string.settings_title)) },
                    label = { Text(stringResource(R.string.settings_title)) },
                )
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding)) {
            when (tab) {
                Tab.MAIN -> MainScreen(
                    vm = mainVm,
                    hasPermissions = hasPermissions,
                    onRequestPermissions = { permissionLauncher.launch(requiredPermissions()) },
                )
                Tab.HISTORY -> HistoryScreen(historyVm)
                Tab.SETTINGS -> SettingsScreen(settingsVm)
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
