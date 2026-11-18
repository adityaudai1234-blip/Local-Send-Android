package com.example.presentation.privacy

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.PrivacyTip
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.WifiTethering
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.discovery.NsdDiscoveryManager
import com.example.data.network.ConnectionManager
import com.example.data.network.ConnectionState
import com.example.data.transfer.StorageManager
import com.example.data.transfer.TransferManager
import com.example.domain.model.TransferItem
import com.example.domain.repository.PairedDeviceRepository
import com.example.domain.repository.SettingsRepository
import com.example.presentation.components.SectionHeader
import com.example.presentation.components.StatusBadge
import com.example.ui.theme.StatusSuccess
import com.example.ui.theme.StatusWarning
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrivacyDashboardScreen(
    settingsRepository: SettingsRepository,
    pairedDeviceRepository: PairedDeviceRepository,
    connectionManager: ConnectionManager,
    transferManager: TransferManager,
    storageManager: StorageManager,
    nsdDiscoveryManager: NsdDiscoveryManager,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    BackHandler(onBack = onBack)

    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    val settings by settingsRepository.settings.collectAsState()
    val pairedDevices by pairedDeviceRepository.pairedDevices.collectAsState(initial = emptyList())
    val connectionStates by connectionManager.connectionStates.collectAsState()
    val history by transferManager.transferHistory.collectAsState()

    var tempBytes by remember { mutableLongStateOf(0L) }
    var receivedBytes by remember { mutableLongStateOf(0L) }
    var showRevokeAllDialog by remember { mutableStateOf(false) }
    var showClearHistoryDialog by remember { mutableStateOf(false) }

    fun refreshStorageStats() {
        scope.launch {
            withContext(Dispatchers.IO) {
                tempBytes = storageManager.getTotalTempStorageBytes()
                receivedBytes = storageManager.getTotalReceivedStorageBytes()
            }
        }
    }

    LaunchedEffect(Unit) {
        refreshStorageStats()
    }

    val activeConnectedCount = connectionStates.values.count { it is ConnectionState.Connected }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .testTag("privacy_dashboard_screen"),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Privacy Dashboard", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text("Zero-cloud security controls", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("privacy_back_button")) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                    ),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.25f))
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Shield, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Local Privacy Summary", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        }
                        PrivacyRow("Cloud Uploads", "Disabled (100% local LAN)")
                        PrivacyRow("Trusted Paired Peers", "${pairedDevices.size} device(s)")
                        PrivacyRow("Active Peer Connections", "$activeConnectedCount connected")
                        PrivacyRow("Discovery Broadcast", if (settings.broadcastEnabled) "Advertising" else "Paused")
                        PrivacyRow("Auto-Accept Default Policy", if (settings.autoAcceptTrusted) "Enabled for trusted peers" else "Explicit approval required")
                        PrivacyRow("Transfer History Records", "${history.size} entry/entries")
                        PrivacyRow("Received Storage / Cache", "${TransferItem.formatBytes(receivedBytes)} / ${TransferItem.formatBytes(tempBytes)}")
                    }
                }
            }

            item { SectionHeader(title = "Visibility & Trust Safeguards") }
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                    )
                ) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                Icon(Icons.Default.WifiTethering, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                Spacer(modifier = Modifier.width(10.dp))
                                Column {
                                    Text("Broadcast on Local Network", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                                    Text("Allow nearby peers to see this device name", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            Switch(
                                checked = settings.broadcastEnabled,
                                onCheckedChange = { enabled ->
                                    scope.launch {
                                        settingsRepository.setBroadcastEnabled(enabled)
                                        if (!enabled) nsdDiscoveryManager.stopAdvertising()
                                        else nsdDiscoveryManager.startAdvertising(settings.deviceName, settings.port)
                                    }
                                },
                                modifier = Modifier.testTag("privacy_broadcast_switch")
                            )
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.14f))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                Icon(Icons.Default.PrivacyTip, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                Spacer(modifier = Modifier.width(10.dp))
                                Column {
                                    Text("Require Unknown Approval", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                                    Text("Block unapproved transfers from unknown senders", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            Switch(
                                checked = settings.requireApprovalUnknown,
                                onCheckedChange = { enabled ->
                                    scope.launch { settingsRepository.setRequireApprovalUnknown(enabled) }
                                }
                            )
                        }
                    }
                }
            }

            item { SectionHeader(title = "Data & Credential Hygiene") }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                val removed = withContext(Dispatchers.IO) { storageManager.cleanAllTempFiles() }
                                refreshStorageStats()
                                snackbarHostState.showSnackbar(
                                    if (removed > 0) "Cleared $removed temp file(s)" else "Temporary cache is already empty"
                                )
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("privacy_clear_temp_button"),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.CleaningServices, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Clear Temporary Transfer Cache (${TransferItem.formatBytes(tempBytes)})")
                    }

                    OutlinedButton(
                        onClick = { showClearHistoryDialog = true },
                        enabled = history.isNotEmpty(),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("privacy_clear_history_button"),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.History, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Clear Persistent Transfer History (${history.size})")
                    }

                    OutlinedButton(
                        onClick = { showRevokeAllDialog = true },
                        enabled = pairedDevices.isNotEmpty(),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("privacy_revoke_keys_button"),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.Key, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Revoke All Trusted Devices & Wipe Keystore (${pairedDevices.size})")
                    }
                }
            }

            if (pairedDevices.isNotEmpty()) {
                item { SectionHeader(title = "Paired Devices (${pairedDevices.size})") }
                items(pairedDevices, key = { it.id }) { peer ->
                    val isConn = connectionStates[peer.id] is ConnectionState.Connected
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(peer.alias, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                                Text(
                                    text = "${peer.ipAddress}:${peer.port}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            StatusBadge(
                                text = if (isConn) "Connected" else "Paired",
                                color = if (isConn) StatusSuccess else StatusWarning
                            )
                            IconButton(
                                onClick = {
                                    scope.launch {
                                        connectionManager.disconnect(peer.id)
                                        pairedDeviceRepository.removePairedDevice(peer.id)
                                        snackbarHostState.showSnackbar("Removed trust for ${peer.alias}")
                                    }
                                },
                                modifier = Modifier.size(48.dp)
                            ) {
                                Icon(Icons.Default.DeleteOutline, contentDescription = "Remove ${peer.alias}", tint = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }

            item { Spacer(modifier = Modifier.height(16.dp)) }
        }
    }

    if (showClearHistoryDialog) {
        AlertDialog(
            onDismissRequest = { showClearHistoryDialog = false },
            title = { Text("Clear Transfer History?") },
            text = { Text("Removes all ${history.size} transfer history record(s) from the local database. Received files on disk remain intact.") },
            confirmButton = {
                Button(
                    onClick = {
                        transferManager.clearAllHistory()
                        showClearHistoryDialog = false
                        scope.launch { snackbarHostState.showSnackbar("Transfer history cleared") }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Clear History")
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearHistoryDialog = false }) { Text("Cancel") }
            }
        )
    }

    if (showRevokeAllDialog) {
        AlertDialog(
            onDismissRequest = { showRevokeAllDialog = false },
            title = { Text("Revoke All Keystore Credentials?") },
            text = { Text("Disconnects all peers and permanently wipes stored authentication keys from Android Keystore.") },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch {
                            pairedDevices.forEach { connectionManager.disconnect(it.id) }
                            withContext(Dispatchers.IO) { pairedDeviceRepository.clearAllDevices() }
                            showRevokeAllDialog = false
                            snackbarHostState.showSnackbar("All trusted devices & Keystore keys removed")
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Revoke All")
                }
            },
            dismissButton = {
                TextButton(onClick = { showRevokeAllDialog = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun PrivacyRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
    }
}
