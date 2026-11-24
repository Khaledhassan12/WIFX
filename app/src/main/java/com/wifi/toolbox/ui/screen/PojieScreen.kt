package com.wifi.toolbox.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ManageSearch
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.wifi.toolbox.ui.screen.pojie.*
import com.wifi.toolbox.ToolboxApp
import com.wifi.toolbox.structs.*
import com.wifi.toolbox.ui.items.*
import com.wifi.toolbox.ui.items.pojie.ResourceSelectSheet
import com.wifi.toolbox.ui.items.pojie.RunListView
import com.wifi.toolbox.ui.items.pojie.ScanResult
import com.wifi.toolbox.ui.items.pojie.ScreenState
import com.wifi.toolbox.offline.StrategyAdvisor
import com.wifi.toolbox.utils.*
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import com.wifi.toolbox.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PojieScreen(onMenuClick: () -> Unit) {
    val context = LocalContext.current
    val app = remember { context.applicationContext as? ToolboxApp }
    var selectedPageIndex by rememberSaveable { mutableIntStateOf(2) }
    var pojieSettings by rememberPojieSettings(context)

    val controller = if (app != null) {
        rememberPojieWifiController(
            context = context, app = app,
            settings = pojieSettings,
            onChangePage = { selectedPageIndex = it },
        )
    } else {
        remember {
            object : PojieWifiController {
                override val uiState = ScreenState.Success(true)
                override val isScanning = false
                override val trigger = 0
                override val refreshErrorKey = 0L
                override val runningTasks = emptyList<PojieRunInfo>()
                override val finishedInfo = SnapshotStateMap<String, String>()
                override fun reload() {}
                override fun fetchResults() = ScanResult(ScanResult.CODE_SUCCESS, null, emptyList())
                override fun enableWifi() {}
                override fun applyLocation() {}
                override fun enableLocation() {}
                override fun disconnectWifi() {}
                override fun gotoSettings() {}
                override fun gotoAppSettings() {}
            }
        }
    }

    PojieScreenContent(
        onMenuClick = onMenuClick,
        app = app,
        pojieSettings = pojieSettings,
        pojieWifiController = controller,
        onSettingsUpdate = { pojieSettings = it },
        selectedPageIndex = selectedPageIndex,
        onPageIndexChange = { selectedPageIndex = it },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PojieScreenContent(
    onMenuClick: () -> Unit,
    app: ToolboxApp?,
    pojieSettings: PojieSettings,
    pojieWifiController: PojieWifiController,
    onSettingsUpdate: (PojieSettings) -> Unit,
    selectedPageIndex: Int,
    onPageIndexChange: (Int) -> Unit,
) {
    val context = LocalContext.current
    var currentTargetSsid by rememberSaveable { mutableStateOf("") }
    var showResourceSheet by rememberSaveable { mutableStateOf(false) }

    var showResourcesFabDialog by rememberSaveable { mutableStateOf(false) }
    var showHistoryConfirmDialog by rememberSaveable { mutableStateOf(false) }
    var showSavedConfirmDialog by rememberSaveable { mutableStateOf(false) }
    var pendingWifiInfo by remember { mutableStateOf<WifiInfo?>(null) }
    val offlineManager = remember { OfflineAuditManager(context) }

    val historyItem =
        remember(currentTargetSsid, app?.pojieHistory?.historyFlow?.collectAsState()?.value) {
            app?.pojieHistory?.historyFlow?.value?.find { it.ssid == currentTargetSsid }
        }

    val pages = remember(pojieSettings, pojieWifiController, showResourcesFabDialog) {
        listOf(
            object : NavPage {
                override val name = context.getString(R.string.resource)
                override val selectedIcon = Icons.Filled.Inbox
                override val unselectedIcon = Icons.Outlined.Inbox
                override val content = @Composable {
                    ResourcesPage(showFabDialog = showResourcesFabDialog) {
                        showResourcesFabDialog = it
                    }
                }
            },
            object : NavPage {
                override val name = context.getString(R.string.history)
                override val selectedIcon = Icons.Filled.History
                override val unselectedIcon = Icons.Outlined.History
                override val content = @Composable { HistoryPage() }
            },
            object : NavPage {
                override val name = context.getString(R.string.run)
                override val selectedIcon = Icons.Filled.Home
                override val unselectedIcon = Icons.Outlined.Home
                override val content = @Composable {
                    HomePage(
                        runListView = {
                            RunListView(
                                controller = pojieWifiController,
                                onStartClick = { wifiInfo ->
                                    currentTargetSsid = wifiInfo.ssid
                                    pendingWifiInfo = wifiInfo
                                    app?.let { applyStrategy(context, it, wifiInfo) }
                                    val history = wifiInfo.pojieHistoryItem
                                    val hasSaved = wifiInfo.savedInfo != null
                                    val hasUnfinishedHistory =
                                        history != null && history.progress < history.passwords.size

                                    when {
                                        hasSaved -> showSavedConfirmDialog = true
                                        hasUnfinishedHistory -> showHistoryConfirmDialog = true
                                        else -> showResourceSheet = true
                                    }
                                },
                                onStopClick = { app?.pojieTask?.stop(it) }
                            )
                        }
                    )
                }
            },
            object : NavPage {
                override val name = context.getString(R.string.settings)
                override val selectedIcon = Icons.Filled.Settings
                override val unselectedIcon = Icons.Outlined.Settings
                override val content = @Composable {
                    SettingsPage(pojieSettings) { onSettingsUpdate(it) }
                }
            },
            object : NavPage {
                override val name = context.getString(R.string.offline_title)
                override val selectedIcon = Icons.Filled.CloudOff
                override val unselectedIcon = Icons.Outlined.CloudOff
                override val content = @Composable { OfflinePage(offlineManager) }
            },
            object : NavPage {
                override val name = context.getString(R.string.help)
                override val selectedIcon = Icons.Filled.Info
                override val unselectedIcon = Icons.Outlined.Info
                override val content = @Composable { HelpPage() }
            }
        )
    }

    Box(Modifier.fillMaxSize()) {
        NavContainer(
            pages = pages,
            selectedIndex = selectedPageIndex,
            onIndexChange = onPageIndexChange,
            subtitle = stringResource(R.string.wifi_pojie_name),
            defaultIndex = 2,
            onMenuClick = onMenuClick
        )
        ResourceSelectSheet(
            show = showResourceSheet,
            onDismiss = { showResourceSheet = false },
            wifiInfo = pendingWifiInfo ?: WifiInfo(
                ssid = currentTargetSsid,
                bssid = "",
                level = 0,
                capabilities = ""
            ),
            onConfirm = { allPasswords ->
                pendingWifiInfo?.let { info -> app?.let { applyStrategy(context, it, info) } }
                app?.pojieTask?.start(
                    PojieRunInfo(
                        ssid = currentTargetSsid,
                        tryList = allPasswords,
                        lastTryTime = System.currentTimeMillis()
                    )
                )
                showResourceSheet = false
            }
        )
    }

    val pendingWifi = pendingWifiInfo
    if (showSavedConfirmDialog && pendingWifi != null) {
        SavedWifiConfirmDialog(
            wifiInfo = pendingWifi,
            onDismiss = { showSavedConfirmDialog = false },
            onDirectTry = {
                showSavedConfirmDialog = false
                pendingWifi.savedInfo?.let { saved ->
                    val psk = saved.preSharedKey.trim('"')
                    if (psk.length >= 8) {
                        app?.pojieTask?.start(
                            PojieRunInfo(
                                ssid = currentTargetSsid,
                                tryList = listOf(psk),
                                lastTryTime = System.currentTimeMillis()
                            )
                        )
                    } else app?.pojieTask?.start(
                        PojieRunInfo(
                            ssid = currentTargetSsid,
                            tryList = listOf(""),
                            lastTryTime = System.currentTimeMillis()
                        )
                    )
                }
            },
            onReRun = {
                showSavedConfirmDialog = false
                val history = pendingWifi.pojieHistoryItem
                if (history != null && history.progress < history.passwords.size) {
                    showHistoryConfirmDialog = true
                } else {
                    showResourceSheet = true
                }
            }
        )
    }

    if (showHistoryConfirmDialog && historyItem != null) {
        HistoryRecoveryDialog(
            item = historyItem,
            onDismiss = { showHistoryConfirmDialog = false },
            onContinue = {
                app?.pojieTask?.start(
                    PojieRunInfo(
                        ssid = historyItem.ssid,
                        tryList = historyItem.passwords,
                        tryIndex = historyItem.progress,
                        lastTryTime = System.currentTimeMillis()
                    )
                )
                showHistoryConfirmDialog = false
            },
            onReSelect = {
                showHistoryConfirmDialog = false
                showResourceSheet = true
            }
        )
    }
}

@Composable
fun SavedWifiConfirmDialog(
    wifiInfo: WifiInfo,
    onDismiss: () -> Unit,
    onDirectTry: () -> Unit,
    onReRun: () -> Unit
) {
    val saved = wifiInfo.savedInfo ?: return
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                Icons.AutoMirrored.Outlined.ManageSearch,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
        },
        title = { Text(stringResource(R.string.found_saved_password)) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = stringResource(R.string.found_saved_password_tip),
                    style = MaterialTheme.typography.bodyMedium
                )
                Surface(
                    modifier = Modifier.padding(top = 16.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    shape = MaterialTheme.shapes.medium
                ) {
                    Column(
                        modifier = Modifier
                            .padding(12.dp)
                            .fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.ssid_string, wifiInfo.ssid), style = MaterialTheme.typography.labelLarge)
                        Text(
                            stringResource(R.string.password_string, saved.preSharedKey.trim('"')),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onReRun,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    border = ButtonDefaults.outlinedButtonBorder(enabled = true).copy(
                        brush = SolidColor(MaterialTheme.colorScheme.error)
                    )
                ) {
                    Text(stringResource(R.string.re_run))
                }
                Button(
                    onClick = onDirectTry,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(stringResource(R.string.direct_try))
                }
            }
        }
    )
}

@Composable
fun HistoryRecoveryDialog(
    item: PojieHistoryItem,
    onDismiss: () -> Unit,
    onContinue: () -> Unit,
    onReSelect: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                Icons.Outlined.History,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
        },
        title = { Text(stringResource(R.string.found_history)) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = stringResource(R.string.found_history_tip),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                )
                Surface(
                    modifier = Modifier.padding(top = 16.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    shape = MaterialTheme.shapes.medium
                ) {
                    Column(
                        modifier = Modifier
                            .padding(12.dp)
                            .fillMaxWidth()
                    ) {
                        Text(
                            stringResource(R.string.ssid_string, item.ssid),
                            style = MaterialTheme.typography.labelLarge
                        )
                        Text(
                            stringResource(
                                R.string.wifi_pojie_history_progress_number,
                                item.progress,
                                item.passwords.size
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onReSelect,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    border = ButtonDefaults.outlinedButtonBorder(enabled = true).copy(
                        brush = SolidColor(MaterialTheme.colorScheme.error)
                    )
                ) {
                    Text(stringResource(R.string.re_select_password))
                }

                Button(
                    onClick = onContinue,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(stringResource(R.string.use_latest_password))
                }
            }
        },
        dismissButton = null
    )
}

/**
 * Tunes the attempt settings for the security type of [wifiInfo] and writes the
 * reasoning to the run log, so the user can see why a profile was chosen.
 */
private fun applyStrategy(
    context: android.content.Context,
    app: ToolboxApp,
    wifiInfo: WifiInfo
) {
    val advice = StrategyAdvisor.advise(
        profile = StrategyAdvisor.profileFromCapabilities(wifiInfo.capabilities, wifiInfo.level),
        currentMaxTryTime = app.pojieConfig.maxTryTime,
        currentTimeout = app.pojieConfig.timeout,
        currentFailureFlag = app.pojieConfig.failureFlag
    )
    val updated = app.pojieConfig.copy(
        maxTryTime = advice.maxTryTime,
        timeout = advice.timeout,
        failureFlag = advice.failureFlag
    )
    if (updated != app.pojieConfig) app.pojieTask.update(updated)

    app.logState.addLog(context.getString(R.string.strategy_applied, wifiInfo.ssid))
    advice.notes.forEach { note ->
        app.logState.addLog(context.getString(note.stringRes()))
    }
}

private fun StrategyAdvisor.Note.stringRes(): Int = when (this) {
    StrategyAdvisor.Note.OPEN_NETWORK -> R.string.strategy_open
    StrategyAdvisor.Note.WEP_LEGACY -> R.string.strategy_wep
    StrategyAdvisor.Note.WPA3_SAE_SLOW -> R.string.strategy_wpa3_unsupported
    StrategyAdvisor.Note.WPA3_TRANSITION -> R.string.strategy_wpa3_transition
    StrategyAdvisor.Note.ENTERPRISE_UNSUPPORTED -> R.string.strategy_enterprise
    StrategyAdvisor.Note.WEAK_SIGNAL -> R.string.strategy_weak_signal
    StrategyAdvisor.Note.OFFLINE_RECOMMENDED -> R.string.strategy_offline
    StrategyAdvisor.Note.LONG_PASSWORDS_LIKELY -> R.string.strategy_long
    StrategyAdvisor.Note.FAST_PROFILE -> R.string.strategy_fast
}
