package com.example.presentation.devices

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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.ManageAccounts
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.WifiFind
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.platform.LocalContext
import com.example.data.network.ConnectionState
import com.example.data.pairing.PairingPayload
import com.example.domain.model.AppErrorMapper
import com.example.domain.model.Device
import com.example.domain.model.ErrorCategory
import com.example.domain.model.PairedDevice
import com.example.presentation.components.CategorizedErrorCard
import com.example.presentation.components.ContextualPermissionCard
import com.example.presentation.components.DeviceCard
import com.example.presentation.components.EmptyStateView
import com.example.presentation.components.PulseScanningIndicator
import com.example.presentation.pairing.GenerateQrDialog
import com.example.presentation.pairing.PairingConfirmationDialog
import com.example.presentation.pairing.PairingProgressDialog
import com.example.presentation.pairing.ScanQrDialog
import com.example.ui.theme.CyanAccent
import com.example.util.PermissionHelper
import com.example.util.PermissionState
import java.util.UUID

@Composable
fun DevicesScreen(
    viewModel: DevicesViewModel,
    onDeviceSelected: (Device) -> Unit,
    onManageTrustedClick: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val devices by viewModel.devices.collectAsState()
    val isScanning by viewModel.isScanning.collectAsState()
    val connectionStatus by viewModel.connectionStatus.collectAsState()
    val error by viewModel.error.collectAsState()

    val pairedDevices by viewModel.pairedDevices.collectAsState()
    val sortMode by viewModel.sortMode.collectAsState()
    val connectionStates by viewModel.connectionStates.collectAsState()
    val pairingResult by viewModel.pairingResult.collectAsState()

    val showGenerateQr by viewModel.showGenerateQr.collectAsState()
    val showScanQr by viewModel.showScanQr.collectAsState()
    val pendingScannedPayload by viewModel.pendingScannedPayload.collectAsState()
    val selectedDeviceForDetails by viewModel.selectedDeviceForDetails.collectAsState()

    var devicePendingUnpair by remember { mutableStateOf<PairedDevice?>(null) }
    var devicePendingRename by remember { mutableStateOf<PairedDevice?>(null) }
    var renameText by remember { mutableStateOf("") }

    val favoriteDevices = remember(pairedDevices) { pairedDevices.filter { it.isFavorite } }
    val regularPairedDevices = remember(pairedDevices) {
        if (sortMode == DeviceSortMode.FAVORITES_FIRST && favoriteDevices.isNotEmpty()) {
            pairedDevices.filter { !it.isFavorite }
        } else {
            pairedDevices
        }
    }

    val context = LocalContext.current
    var wifiPermNonce by remember { mutableIntStateOf(0) }
    val showNearbyWifiPrompt = PermissionHelper.isNearbyWifiRequiredOnThisDevice()
    val nearbyWifiState = remember(wifiPermNonce) {
        if (showNearbyWifiPrompt) {
            PermissionHelper.getPermissionState(context, Manifest.permission.NEARBY_WIFI_DEVICES)
        } else {
            PermissionState.NOT_REQUIRED
        }
    }
    var nearbyBannerDismissed by remember(wifiPermNonce) {
        mutableStateOf(
            showNearbyWifiPrompt &&
                PermissionHelper.isBannerDismissed(context, Manifest.permission.NEARBY_WIFI_DEVICES)
        )
    }
    val nearbyWifiLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            PermissionHelper.markPermissionAsked(context, Manifest.permission.NEARBY_WIFI_DEVICES)
        }
        wifiPermNonce++
        viewModel.refreshDiscovery()
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag("devices_screen"),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        if (showNearbyWifiPrompt &&
            !nearbyBannerDismissed &&
            nearbyWifiState != PermissionState.GRANTED &&
            nearbyWifiState != PermissionState.NOT_REQUIRED
        ) {
            item {
                ContextualPermissionCard(
                    title = "Nearby Wi-Fi Discovery",
                    rationale = "Grant Nearby Wi-Fi Devices on Android 13+ so LocalDrop can discover peers over mDNS. You can still pair manually by QR anytime.",
                    permissionState = nearbyWifiState,
                    icon = Icons.Default.Wifi,
                    onRequestPermission = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            nearbyWifiLauncher.launch(Manifest.permission.NEARBY_WIFI_DEVICES)
                        }
                    },
                    onOpenSettings = { PermissionHelper.openAppSettings(context) },
                    onDismiss = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            PermissionHelper.markBannerDismissed(context, Manifest.permission.NEARBY_WIFI_DEVICES)
                        }
                        nearbyBannerDismissed = true
                    },
                    modifier = Modifier.testTag("devices_nearby_wifi_permission_card")
                )
            }
        }
        // 1. Top Card: Quick Pairing Actions (Show QR / Scan QR)
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                ),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            PulseScanningIndicator(isScanning = isScanning)
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(
                                    text = if (isScanning) "Searching for local peers…" else "mDNS & TCP Server Active",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "_lsend._tcp • Local peer discovery",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        IconButton(
                            onClick = { viewModel.refreshDiscovery() },
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("refresh_devices_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "Refresh Discovery",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(
                            onClick = { viewModel.openGenerateQr() },
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("show_my_qr_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.QrCodeScanner,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Show My QR", style = MaterialTheme.typography.labelMedium)
                        }

                        FilledTonalButton(
                            onClick = { viewModel.openScanQr() },
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("scan_qr_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.QrCodeScanner,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Scan QR Code", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
        }

        if (!error.isNullOrBlank()) {
            item {
                CategorizedErrorCard(
                    error = AppErrorMapper.fromMessage(
                        category = ErrorCategory.DISCOVERY,
                        userMessage = error ?: "",
                        technicalDetails = error,
                        targetLabel = "mDNS Discovery"
                    )
                )
            }
        }

        // FAVORITES SECTION (when starred devices exist)
        if (favoriteDevices.isNotEmpty() && sortMode == DeviceSortMode.FAVORITES_FIRST) {
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Star,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Favorite Devices (${favoriteDevices.size})",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            items(
                items = favoriteDevices,
                key = { "fav_${it.id}" }
            ) { fav ->
                val connState = connectionStates[fav.id] ?: ConnectionState.Disconnected
                PairedDeviceCard(
                    device = fav,
                    connectionState = connState,
                    onConnectClick = { viewModel.connectDevice(fav) },
                    onDisconnectClick = { viewModel.disconnectDevice(fav.id) },
                    onDetailsClick = { viewModel.openDeviceDetails(fav) },
                    onRenameClick = {
                        renameText = fav.alias
                        devicePendingRename = fav
                    },
                    onToggleFavorite = { isFav -> viewModel.toggleFavorite(fav.id, isFav) },
                    onRemoveClick = { devicePendingUnpair = fav }
                )
            }
        }

        // SECTION 1: PAIRED / TRUSTED DEVICES
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.VerifiedUser,
                            contentDescription = null,
                            tint = CyanAccent,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Trusted Devices (${pairedDevices.size})",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }

                    TextButton(
                        onClick = onManageTrustedClick,
                        modifier = Modifier.testTag("manage_trusted_devices_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.ManageAccounts,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Manage")
                    }
                }

                if (pairedDevices.size > 1) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(DeviceSortMode.entries) { mode ->
                            FilterChip(
                                selected = sortMode == mode,
                                onClick = { viewModel.setSortMode(mode) },
                                label = { Text("Sort: ${mode.label}") },
                                modifier = Modifier.testTag("device_sort_${mode.name}")
                            )
                        }
                    }
                }
            }
        }

        if (pairedDevices.isEmpty()) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                    ),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "No trusted devices yet",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Scan a QR code from a Tauri desktop app or nearby Android device to establish a mutually authenticated bond.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        } else if (regularPairedDevices.isNotEmpty()) {
            items(
                items = regularPairedDevices,
                key = { it.id }
            ) { pairedDevice ->
                val connState = connectionStates[pairedDevice.id] ?: ConnectionState.Disconnected
                PairedDeviceCard(
                    device = pairedDevice,
                    connectionState = connState,
                    onConnectClick = { viewModel.connectDevice(pairedDevice) },
                    onDisconnectClick = { viewModel.disconnectDevice(pairedDevice.id) },
                    onDetailsClick = { viewModel.openDeviceDetails(pairedDevice) },
                    onRenameClick = {
                        renameText = pairedDevice.alias
                        devicePendingRename = pairedDevice
                    },
                    onToggleFavorite = { isFav -> viewModel.toggleFavorite(pairedDevice.id, isFav) },
                    onRemoveClick = { devicePendingUnpair = pairedDevice }
                )
            }
        }

        // SECTION 2: NEARBY DEVICES (Discovered via mDNS)
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Nearby Devices (${devices.size})",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )

                TextButton(
                    onClick = { viewModel.refreshDiscovery() },
                    modifier = Modifier.testTag("rescan_nearby_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Rescan")
                }
            }
        }

        if (devices.isEmpty()) {
            item {
                EmptyStateView(
                    icon = Icons.Default.WifiFind,
                    title = "No nearby devices found",
                    description = "Make sure Tauri desktop or mobile peers are on '${connectionStatus.networkName}' and broadcasting.",
                    actionButtonText = "Scan Again",
                    onActionClick = { viewModel.refreshDiscovery() }
                )
            }
        } else {
            items(
                items = devices,
                key = { it.id }
            ) { device ->
                DeviceCard(
                    device = device,
                    onClick = { onDeviceSelected(device) },
                    onSendClick = {
                        val payload = PairingPayload(
                            protocolVersion = "v2",
                            sessionId = UUID.randomUUID().toString(),
                            pairingToken = "mdns_peer_token",
                            deviceId = device.id,
                            deviceName = device.alias,
                            platform = device.deviceType.identifier,
                            ipAddress = device.ipAddress,
                            port = device.port,
                            expiresAt = System.currentTimeMillis() + 120_000L
                        )
                        viewModel.onPayloadScanned(payload)
                    }
                )
            }
        }

        item {
            Spacer(modifier = Modifier.height(16.dp))
        }
    }

    if (showGenerateQr) {
        GenerateQrDialog(
            pairingManager = viewModel.pairingManager,
            onDismiss = { viewModel.closeGenerateQr() }
        )
    }

    if (showScanQr) {
        ScanQrDialog(
            pairedDevices = pairedDevices,
            onFastReconnectPeer = { viewModel.connectDevice(it) },
            onPayloadScanned = { payload ->
                viewModel.onPayloadScanned(payload)
            },
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
        val connState = connectionStates[device.id] ?: ConnectionState.Disconnected
        DeviceDetailsDialog(
            device = device,
            connectionState = connState,
            connectionManager = viewModel.connectionManager,
            onRemoveDevice = {
                viewModel.closeDeviceDetails()
                devicePendingUnpair = device
            },
            onDismiss = { viewModel.closeDeviceDetails() }
        )
    }

    devicePendingRename?.let { target ->
        AlertDialog(
            onDismissRequest = { devicePendingRename = null },
            title = { Text("Rename Trusted Device") },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    label = { Text("Local Device Alias") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("input_rename_paired_device")
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (renameText.isNotBlank()) {
                            viewModel.renamePairedDevice(target.id, renameText.trim())
                        }
                        devicePendingRename = null
                    },
                    enabled = renameText.isNotBlank(),
                    modifier = Modifier.testTag("confirm_rename_device_button")
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { devicePendingRename = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    devicePendingUnpair?.let { target ->
        AlertDialog(
            onDismissRequest = { devicePendingUnpair = null },
            title = { Text("Remove Trusted Device?") },
            text = {
                Text(
                    "Removing trust for '${target.alias}' disconnects any active session and permanently deletes its encrypted authentication credentials from Android Keystore."
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.removePairedDevice(target.id)
                        devicePendingUnpair = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.testTag("confirm_unpair_device_button")
                ) {
                    Text("Remove Trust")
                }
            },
            dismissButton = {
                TextButton(onClick = { devicePendingUnpair = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}
