package com.example.presentation.devices

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
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.network.ConnectionState
import com.example.domain.model.PairedDevice
import com.example.presentation.components.ConnectionStatusBadge
import com.example.presentation.components.EmptyStateView
import com.example.presentation.components.StatusBadge
import com.example.presentation.pairing.ScanQrDialog
import com.example.presentation.pairing.PairingConfirmationDialog
import com.example.presentation.pairing.PairingProgressDialog
import com.example.ui.theme.CyanAccent
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrustedDevicesScreen(
    viewModel: DevicesViewModel,
    onBack: () -> Unit
) {
    BackHandler(onBack = onBack)

    val pairedDevices by viewModel.pairedDevices.collectAsState()
    val connectionStates by viewModel.connectionStates.collectAsState()
    val selectedDeviceForDetails by viewModel.selectedDeviceForDetails.collectAsState()
    val showScanQr by viewModel.showScanQr.collectAsState()
    val pendingScannedPayload by viewModel.pendingScannedPayload.collectAsState()
    val pairingResult by viewModel.pairingResult.collectAsState()

    var deviceToRename by remember { mutableStateOf<PairedDevice?>(null) }
    var aliasInput by remember { mutableStateOf("") }
    var deviceToRemove by remember { mutableStateOf<PairedDevice?>(null) }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .testTag("trusted_devices_screen"),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "Trusted Devices",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "${pairedDevices.size} paired peer${if (pairedDevices.size == 1) "" else "s"}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.testTag("trusted_devices_back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = { viewModel.openScanQr() },
                        modifier = Modifier.testTag("trusted_devices_scan_qr_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.QrCodeScanner,
                            contentDescription = "Pair new device"
                        )
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
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                    ),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.25f))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Icon(
                            imageVector = Icons.Default.Security,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "Mutual authentication tokens for paired devices are encrypted via Android Keystore. Unpairing permanently deletes stored credentials for that peer.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            if (pairedDevices.isEmpty()) {
                item {
                    EmptyStateView(
                        icon = Icons.Default.VerifiedUser,
                        title = "No trusted devices",
                        description = "Scan a peer's QR code to establish a trusted device relationship.",
                        actionButtonText = "Scan QR Code",
                        onActionClick = { viewModel.openScanQr() }
                    )
                }
            } else {
                items(pairedDevices, key = { it.id }) { device ->
                    val connState = connectionStates[device.id] ?: ConnectionState.Disconnected
                    val isConnected = connState is ConnectionState.Connected
                    val isConnecting = connState is ConnectionState.Connecting || connState is ConnectionState.Authenticating

                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("trusted_device_item_${device.id}"),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                        ),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.22f))
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(
                                        imageVector = device.deviceType.icon,
                                        contentDescription = device.deviceType.displayName,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(26.dp)
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Column {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                text = device.alias,
                                                style = MaterialTheme.typography.titleMedium,
                                                fontWeight = FontWeight.Bold,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Spacer(modifier = Modifier.width(6.dp))
                                            StatusBadge(text = "Trusted", color = CyanAccent, showDot = false)
                                        }
                                        Text(
                                            text = "${device.deviceType.displayName} • ${device.ipAddress}:${device.port}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                                ConnectionStatusBadge(state = connState)
                            }

                            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))

                            MetaInfoRow("Device ID", device.id, monospace = true)
                            MetaInfoRow("Last Connected", formatFullDate(device.lastConnectedAt))
                            MetaInfoRow("Paired On", formatFullDate(device.pairedAt))
                            if (!device.model.isNullOrBlank()) {
                                MetaInfoRow("Hardware Model", device.model)
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                if (isConnected) {
                                    OutlinedButton(
                                        onClick = { viewModel.disconnectDevice(device.id) },
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Icon(Icons.Default.LinkOff, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Disconnect", style = MaterialTheme.typography.labelMedium)
                                    }
                                } else {
                                    FilledTonalButton(
                                        onClick = { viewModel.connectDevice(device) },
                                        enabled = !isConnecting,
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Icon(Icons.Default.Link, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(if (isConnecting) "Connecting…" else "Connect", style = MaterialTheme.typography.labelMedium)
                                    }
                                }

                                OutlinedButton(
                                    onClick = {
                                        aliasInput = device.alias
                                        deviceToRename = device
                                    },
                                    modifier = Modifier.testTag("trusted_rename_${device.id}")
                                ) {
                                    Icon(Icons.Default.Edit, contentDescription = "Rename", modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Rename", style = MaterialTheme.typography.labelMedium)
                                }
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                TextButton(
                                    onClick = { viewModel.openDeviceDetails(device) },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text("Connection Details & Ping")
                                }

                                TextButton(
                                    onClick = { deviceToRemove = device },
                                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                                    modifier = Modifier.testTag("trusted_remove_${device.id}")
                                ) {
                                    Icon(Icons.Default.DeleteForever, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Remove Trust")
                                }
                            }
                        }
                    }
                }
            }

            item { Spacer(modifier = Modifier.height(16.dp)) }
        }
    }

    if (showScanQr) {
        ScanQrDialog(
            onPayloadScanned = { viewModel.onPayloadScanned(it) },
            onDismiss = { viewModel.closeScanQr() }
        )
    }

    pendingScannedPayload?.let { payload ->
        PairingConfirmationDialog(
            payload = payload,
            onConfirm = { viewModel.confirmPairing(payload) },
            onCancel = { viewModel.dismissConfirmation() }
        )
    }

    PairingProgressDialog(
        result = pairingResult,
        onDismiss = { viewModel.resetPairingResult() }
    )

    selectedDeviceForDetails?.let { device ->
        DeviceDetailsDialog(
            device = device,
            connectionState = connectionStates[device.id] ?: ConnectionState.Disconnected,
            connectionManager = viewModel.connectionManager,
            onRemoveDevice = {
                viewModel.closeDeviceDetails()
                deviceToRemove = device
            },
            onDismiss = { viewModel.closeDeviceDetails() }
        )
    }

    deviceToRename?.let { target ->
        AlertDialog(
            onDismissRequest = { deviceToRename = null },
            title = { Text("Rename Trusted Device") },
            text = {
                OutlinedTextField(
                    value = aliasInput,
                    onValueChange = { aliasInput = it },
                    label = { Text("Device Alias") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (aliasInput.isNotBlank()) {
                            viewModel.renamePairedDevice(target.id, aliasInput.trim())
                        }
                        deviceToRename = null
                    },
                    enabled = aliasInput.isNotBlank()
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { deviceToRename = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    deviceToRemove?.let { target ->
        AlertDialog(
            onDismissRequest = { deviceToRemove = null },
            title = { Text("Revoke Device Trust?") },
            text = {
                Text("This removes '${target.alias}' from trusted peers and deletes its encrypted credentials from Android Keystore. Future transfers will require re-pairing.")
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.removePairedDevice(target.id)
                        deviceToRemove = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Revoke & Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { deviceToRemove = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun MetaInfoRow(label: String, value: String, monospace: Boolean = false) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = if (monospace) FontFamily.Monospace else FontFamily.Default,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

private fun formatFullDate(timestamp: Long): String {
    return SimpleDateFormat("MMM d, yyyy • HH:mm", Locale.getDefault()).format(Date(timestamp))
}
