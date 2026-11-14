package com.example.presentation.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.DriveFileMove
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Gavel
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.SwapCalls
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.domain.model.ThemeMode
import com.example.domain.model.TransferItem
import com.example.presentation.components.SectionHeader
import com.example.presentation.components.StatusBadge
import com.example.ui.theme.CyanAccent
import com.example.ui.theme.IndigoAccent
import com.example.ui.theme.TealPrimary
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onNavigateToTrustedDevices: () -> Unit = {},
    onNavigateToStorageManagement: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val settings by viewModel.settings.collectAsState()
    val pairedCount by viewModel.pairedDeviceCount.collectAsState()
    val storageInfo by viewModel.storageInfo.collectAsState()
    val networkSummary by viewModel.networkSummary.collectAsState()

    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        viewModel.refreshDiagnostics()
    }

    var showEditNameDialog by remember { mutableStateOf(false) }
    var showThemeDialog by remember { mutableStateOf(false) }
    var showPortDialog by remember { mutableStateOf(false) }
    var showDiscoveryIntervalDialog by remember { mutableStateOf(false) }
    var showTimeoutDialog by remember { mutableStateOf(false) }
    var showConcurrentDialog by remember { mutableStateOf(false) }
    var showDuplicateDialog by remember { mutableStateOf(false) }
    var showMaxSizeDialog by remember { mutableStateOf(false) }
    var showClearCredentialsDialog by remember { mutableStateOf(false) }
    var showPrivacyNoticeDialog by remember { mutableStateOf(false) }
    var showLicensesDialog by remember { mutableStateOf(false) }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .testTag("settings_screen"),
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 1. GENERAL
            item { SectionHeader(title = "General") }
            item {
                SettingsCard {
                    SettingsRow(
                        icon = Icons.Default.PhoneAndroid,
                        title = "Device Name",
                        subtitle = settings.deviceName,
                        onClick = { showEditNameDialog = true },
                        modifier = Modifier.testTag("setting_device_name")
                    )
                    SettingsDivider()
                    SettingsRow(
                        icon = Icons.Default.Palette,
                        title = "Theme Mode",
                        subtitle = when (settings.themeMode) {
                            ThemeMode.SYSTEM -> "Follow System"
                            ThemeMode.LIGHT -> "Light Theme"
                            ThemeMode.DARK -> "Dark Theme"
                        },
                        onClick = { showThemeDialog = true },
                        modifier = Modifier.testTag("setting_theme")
                    )
                    SettingsDivider()
                    SettingsToggleRow(
                        icon = Icons.Default.Notifications,
                        title = "Transfer Notifications",
                        subtitle = "Incoming requests, progress & completion alerts",
                        checked = settings.showNotifications,
                        onCheckedChange = { viewModel.updateShowNotifications(it) },
                        modifier = Modifier.testTag("switch_notifications")
                    )
                    SettingsDivider()
                    SettingsToggleRow(
                        icon = Icons.Default.Vibration,
                        title = "Haptic Feedback",
                        subtitle = "Subtle tactile response on key actions",
                        checked = settings.hapticsEnabled,
                        onCheckedChange = { viewModel.updateHapticsEnabled(it) },
                        modifier = Modifier.testTag("switch_haptics")
                    )
                }
            }

            // 2. NETWORK
            item { SectionHeader(title = "Network") }
            item {
                SettingsCard {
                    SettingsRow(
                        icon = Icons.Default.Radio,
                        title = "Listening Port",
                        subtitle = "${settings.port} (TCP / mDNS)",
                        onClick = { showPortDialog = true },
                        trailing = { StatusBadge(text = "Port ${settings.port}", color = CyanAccent, showDot = false) },
                        modifier = Modifier.testTag("setting_port")
                    )
                    SettingsDivider()
                    SettingsToggleRow(
                        icon = Icons.Default.Hub,
                        title = "Device Discovery Broadcast",
                        subtitle = "Advertise presence on local Wi-Fi (_lsend._tcp)",
                        checked = settings.broadcastEnabled,
                        onCheckedChange = { viewModel.updateBroadcastEnabled(it) },
                        modifier = Modifier.testTag("switch_broadcast")
                    )
                    SettingsDivider()
                    SettingsRow(
                        icon = Icons.Default.Schedule,
                        title = "Discovery Refresh Interval",
                        subtitle = "Every ${settings.discoveryIntervalSeconds} seconds",
                        onClick = { showDiscoveryIntervalDialog = true },
                        modifier = Modifier.testTag("setting_discovery_interval")
                    )
                    SettingsDivider()
                    SettingsRow(
                        icon = Icons.Default.Speed,
                        title = "Connection Timeout",
                        subtitle = "${settings.connectionTimeoutMs / 1000} seconds",
                        onClick = { showTimeoutDialog = true },
                        modifier = Modifier.testTag("setting_timeout")
                    )
                    SettingsDivider()
                    SettingsRow(
                        icon = Icons.Default.Wifi,
                        title = "Active Network Interfaces",
                        subtitle = networkSummary,
                        onClick = {
                            viewModel.refreshDiagnostics()
                            scope.launch { snackbarHostState.showSnackbar("Network diagnostics refreshed") }
                        },
                        modifier = Modifier.testTag("setting_network_info")
                    )
                }
            }

            // 3. FILE TRANSFERS
            item { SectionHeader(title = "File Transfers") }
            item {
                SettingsCard {
                    SettingsRow(
                        icon = Icons.Default.SwapCalls,
                        title = "Max Concurrent Transfers",
                        subtitle = "${settings.maxConcurrentTransfers} active stream(s)",
                        onClick = { showConcurrentDialog = true },
                        modifier = Modifier.testTag("setting_max_concurrent")
                    )
                    SettingsDivider()
                    SettingsRow(
                        icon = Icons.Default.ContentCopy,
                        title = "Duplicate Filename Handling",
                        subtitle = when (settings.duplicateHandling) {
                            "overwrite" -> "Overwrite existing file"
                            "skip" -> "Skip duplicate file"
                            else -> "Rename automatically (file (1).ext)"
                        },
                        onClick = { showDuplicateDialog = true },
                        modifier = Modifier.testTag("setting_duplicate_handling")
                    )
                    SettingsDivider()
                    SettingsToggleRow(
                        icon = Icons.Default.Badge,
                        title = "Preserve Original Filenames",
                        subtitle = "Keep sender filename after path sanitization",
                        checked = settings.preserveOriginalNames,
                        onCheckedChange = { viewModel.updatePreserveOriginalNames(it) },
                        modifier = Modifier.testTag("switch_preserve_names")
                    )
                    SettingsDivider()
                    SettingsToggleRow(
                        icon = Icons.Default.DriveFileMove,
                        title = "Auto-Open Completed Files",
                        subtitle = "Launch viewer automatically when received",
                        checked = settings.autoOpenCompleted,
                        onCheckedChange = { viewModel.updateAutoOpenCompleted(it) },
                        modifier = Modifier.testTag("switch_auto_open")
                    )
                    SettingsDivider()
                    SettingsRow(
                        icon = Icons.Default.Description,
                        title = "Max Accepted File Size",
                        subtitle = if (settings.maxAcceptedFileSizeMb <= 0L) "Unlimited" else "${settings.maxAcceptedFileSizeMb} MB",
                        onClick = { showMaxSizeDialog = true },
                        modifier = Modifier.testTag("setting_max_file_size")
                    )
                }
            }

            // 4. STORAGE
            item { SectionHeader(title = "Storage") }
            item {
                SettingsCard {
                    SettingsRow(
                        icon = Icons.Default.FolderOpen,
                        title = "Download Location",
                        subtitle = settings.downloadLocation,
                        onClick = onNavigateToStorageManagement,
                        trailing = {
                            StatusBadge(
                                text = if (settings.customDownloadTreeUri != null) "Custom SAF" else "Default",
                                color = MaterialTheme.colorScheme.primary,
                                showDot = false
                            )
                        },
                        modifier = Modifier.testTag("setting_download_location")
                    )
                    SettingsDivider()
                    SettingsRow(
                        icon = Icons.Default.Storage,
                        title = "Storage Manager",
                        subtitle = "${storageInfo.receivedCount} files (${TransferItem.formatBytes(storageInfo.receivedBytes)}) • Free: ${TransferItem.formatBytes(storageInfo.usableBytes)}",
                        onClick = onNavigateToStorageManagement,
                        modifier = Modifier.testTag("setting_storage_manager")
                    )
                    SettingsDivider()
                    SettingsRow(
                        icon = Icons.Default.CleaningServices,
                        title = "Clear Temporary Transfer Files",
                        subtitle = "Cache size: ${TransferItem.formatBytes(storageInfo.tempBytes)}",
                        onClick = {
                            viewModel.clearTempFiles { deleted ->
                                scope.launch {
                                    snackbarHostState.showSnackbar(
                                        if (deleted > 0) "Cleaned $deleted temp file(s)" else "Temporary cache is already clean"
                                    )
                                }
                            }
                        },
                        modifier = Modifier.testTag("setting_clear_temp")
                    )
                }
            }

            // 5. PRIVACY & SECURITY
            item { SectionHeader(title = "Privacy & Security") }
            item {
                SettingsCard {
                    SettingsToggleRow(
                        icon = Icons.Default.VerifiedUser,
                        title = "Auto-Accept from Trusted Devices",
                        subtitle = "Disabled by default for explicit user consent",
                        checked = settings.autoAcceptTrusted,
                        onCheckedChange = { viewModel.updateAutoAcceptTrusted(it) },
                        modifier = Modifier.testTag("switch_auto_accept_trusted")
                    )
                    SettingsDivider()
                    SettingsToggleRow(
                        icon = Icons.Default.Security,
                        title = "Require Approval for Unknown Devices",
                        subtitle = "Prompt confirmation before accepting incoming streams",
                        checked = settings.requireApprovalUnknown,
                        onCheckedChange = { viewModel.updateRequireApprovalUnknown(it) },
                        modifier = Modifier.testTag("switch_require_approval_unknown")
                    )
                    SettingsDivider()
                    SettingsToggleRow(
                        icon = Icons.Default.Lock,
                        title = "Prefer TLS Encryption",
                        subtitle = "Use TLS when peer advertises v2 TLS capability",
                        checked = settings.tlsPreferred,
                        onCheckedChange = { viewModel.updateTlsPreferred(it) },
                        modifier = Modifier.testTag("switch_tls_preferred")
                    )
                    SettingsDivider()
                    SettingsToggleRow(
                        icon = Icons.Default.PowerSettingsNew,
                        title = "Auto-Start Receiving Service",
                        subtitle = "Listen immediately for incoming peer transfers",
                        checked = settings.autoStartReceiving,
                        onCheckedChange = { viewModel.updateAutoStartReceiving(it) },
                        modifier = Modifier.testTag("switch_auto_start")
                    )
                    SettingsDivider()
                    SettingsRow(
                        icon = Icons.Default.VerifiedUser,
                        title = "Manage Trusted Devices",
                        subtitle = "$pairedCount paired device(s) in Keystore",
                        onClick = onNavigateToTrustedDevices,
                        modifier = Modifier.testTag("setting_manage_trusted")
                    )
                    SettingsDivider()
                    SettingsRow(
                        icon = Icons.Default.DeleteSweep,
                        title = "Clear Stored Credentials",
                        subtitle = "Revoke all trusted peers & wipe Keystore keys",
                        onClick = { showClearCredentialsDialog = true },
                        modifier = Modifier.testTag("setting_clear_credentials")
                    )
                }
            }

            // 6. ABOUT
            item { SectionHeader(title = "About") }
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                    ),
                    border = BorderStroke(1.dp, IndigoAccent.copy(alpha = 0.28f))
                ) {
                    Column(modifier = Modifier.padding(vertical = 8.dp)) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = "LocalDrop",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "Version ${settings.appVersion} (Build ${settings.buildNumber}) • Protocol ${settings.protocolVersion}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            StatusBadge(text = "v${settings.appVersion}", color = TealPrimary, showDot = false)
                        }
                        SettingsDivider()
                        SettingsRow(
                            icon = Icons.Default.CloudOff,
                            title = "Privacy & Zero-Cloud Architecture",
                            subtitle = "100% local peer-to-peer Wi-Fi & LAN transfers",
                            onClick = { showPrivacyNoticeDialog = true },
                            modifier = Modifier.testTag("setting_privacy_notice")
                        )
                        SettingsDivider()
                        SettingsRow(
                            icon = Icons.Default.Gavel,
                            title = "Open-Source Licenses",
                            subtitle = "Jetpack Compose, Room, ZXing, Kotlinx Serialization",
                            onClick = { showLicensesDialog = true },
                            modifier = Modifier.testTag("setting_licenses")
                        )
                    }
                }
            }

            item { Spacer(modifier = Modifier.height(16.dp)) }
        }
    }

    if (showEditNameDialog) {
        EditDeviceNameDialog(
            initialName = settings.deviceName,
            onSave = { viewModel.updateDeviceName(it) },
            onDismiss = { showEditNameDialog = false }
        )
    }
    if (showThemeDialog) {
        ThemeSelectionDialog(
            currentMode = settings.themeMode,
            onSelect = { viewModel.updateThemeMode(it) },
            onDismiss = { showThemeDialog = false }
        )
    }
    if (showPortDialog) {
        EditPortDialog(
            currentPort = settings.port,
            onSave = { newPort ->
                viewModel.updatePort(newPort)
                scope.launch { snackbarHostState.showSnackbar("TCP listener updated to port $newPort") }
            },
            onDismiss = { showPortDialog = false }
        )
    }
    if (showDiscoveryIntervalDialog) {
        SingleChoiceDialog(
            title = "Discovery Refresh Interval",
            selectedLabel = "${settings.discoveryIntervalSeconds} seconds",
            options = listOf(5, 10, 20, 30, 60).map { sec ->
                "$sec seconds" to { viewModel.updateDiscoveryIntervalSeconds(sec) }
            },
            onDismiss = { showDiscoveryIntervalDialog = false }
        )
    }
    if (showTimeoutDialog) {
        SingleChoiceDialog(
            title = "Connection Timeout",
            selectedLabel = "${settings.connectionTimeoutMs / 1000} seconds",
            options = listOf(5, 10, 15, 30, 60).map { sec ->
                "$sec seconds" to { viewModel.updateConnectionTimeoutMs(sec * 1000) }
            },
            onDismiss = { showTimeoutDialog = false }
        )
    }
    if (showConcurrentDialog) {
        SingleChoiceDialog(
            title = "Max Concurrent Transfers",
            selectedLabel = "${settings.maxConcurrentTransfers} concurrent",
            options = listOf(1, 2, 3, 4).map { count ->
                "$count concurrent" to { viewModel.updateMaxConcurrent(count) }
            },
            onDismiss = { showConcurrentDialog = false }
        )
    }
    if (showDuplicateDialog) {
        SingleChoiceDialog(
            title = "Duplicate Filename Handling",
            selectedLabel = when (settings.duplicateHandling) {
                "overwrite" -> "Overwrite existing file"
                "skip" -> "Skip duplicate file"
                else -> "Rename automatically"
            },
            options = listOf(
                "Rename automatically" to { viewModel.updateDuplicateHandling("rename") },
                "Overwrite existing file" to { viewModel.updateDuplicateHandling("overwrite") },
                "Skip duplicate file" to { viewModel.updateDuplicateHandling("skip") }
            ),
            onDismiss = { showDuplicateDialog = false }
        )
    }
    if (showMaxSizeDialog) {
        SingleChoiceDialog(
            title = "Max Accepted File Size",
            selectedLabel = if (settings.maxAcceptedFileSizeMb <= 0L) "Unlimited" else "${settings.maxAcceptedFileSizeMb} MB",
            options = listOf(
                "Unlimited" to { viewModel.updateMaxAcceptedFileSizeMb(0L) },
                "100 MB" to { viewModel.updateMaxAcceptedFileSizeMb(100L) },
                "512 MB" to { viewModel.updateMaxAcceptedFileSizeMb(512L) },
                "2048 MB" to { viewModel.updateMaxAcceptedFileSizeMb(2048L) }
            ),
            onDismiss = { showMaxSizeDialog = false }
        )
    }
    if (showClearCredentialsDialog) {
        ClearCredentialsConfirmDialog(
            onConfirm = {
                viewModel.clearStoredCredentials {
                    scope.launch { snackbarHostState.showSnackbar("All trusted peers & Keystore keys cleared") }
                }
            },
            onDismiss = { showClearCredentialsDialog = false }
        )
    }
    if (showPrivacyNoticeDialog) {
        InfoNoticeDialog(
            title = "Privacy & Zero-Cloud Guarantee",
            body = "LocalDrop communicates exclusively over your local Wi-Fi / LAN using mDNS (_lsend._tcp) and direct peer-to-peer TCP sockets. No files, filenames, device lists, or telemetry are ever transmitted to external servers.",
            onDismiss = { showPrivacyNoticeDialog = false }
        )
    }
    if (showLicensesDialog) {
        InfoNoticeDialog(
            title = "Open-Source Libraries",
            body = "• AndroidX Jetpack Compose & Material 3 (Apache 2.0)\n• AndroidX Room, DataStore, Lifecycle & Navigation (Apache 2.0)\n• CameraX (Apache 2.0)\n• ZXing Core QR Engine (Apache 2.0)\n• Kotlin Coroutines & Kotlinx Serialization (Apache 2.0)",
            onDismiss = { showLicensesDialog = false }
        )
    }
}

@Composable
private fun SettingsCard(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
    ) {
        Column(modifier = Modifier.padding(vertical = 4.dp)) {
            content()
        }
    }
}

@Composable
private fun SettingsRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(14.dp))
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(modifier = Modifier.width(8.dp))
        if (trailing != null) {
            trailing()
        } else {
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
private fun SettingsToggleRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(14.dp))
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(modifier = Modifier.width(8.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = MaterialTheme.colorScheme.primary,
                checkedTrackColor = MaterialTheme.colorScheme.primaryContainer
            )
        )
    }
}

@Composable
private fun SettingsDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 16.dp),
        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.14f)
    )
}
