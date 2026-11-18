package com.example.presentation.home

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.PrivacyTip
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.domain.model.Device
import com.example.presentation.components.DeviceCard
import com.example.presentation.components.PlatformBadge
import com.example.presentation.components.PulseScanningIndicator
import com.example.ui.theme.CyanAccent
import com.example.ui.theme.StatusSuccess
import com.example.ui.theme.StatusWarning
import com.example.ui.theme.TealDark
import com.example.ui.theme.TealPrimary

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    onNavigateToDevices: () -> Unit,
    onNavigateToTransfers: () -> Unit,
    onNavigateToReceivedFiles: () -> Unit = {},
    onNavigateToDiagnostics: () -> Unit = {},
    onNavigateToPrivacyDashboard: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val settings by viewModel.settings.collectAsState()
    val connectionStatus by viewModel.connectionStatus.collectAsState()
    val discoveredDevices by viewModel.discoveredDevices.collectAsState()
    val quickSendDevices by viewModel.quickSendDevices.collectAsState()
    val transferStats by viewModel.transferStats.collectAsState()
    val isScanning by viewModel.isScanning.collectAsState()
    val isReceiving by viewModel.isReceivingActive.collectAsState()

    val selectedFiles by viewModel.selectedFiles.collectAsState()
    val pendingTargets by viewModel.pendingTargets.collectAsState()
    val showFileSelectionDialog by viewModel.showFileSelectionDialog.collectAsState()
    val showDeviceSelectionDialog by viewModel.showDeviceSelectionDialog.collectAsState()
    val showConfirmationDialog by viewModel.showConfirmationDialog.collectAsState()
    val incomingRequest by viewModel.incomingRequest.collectAsState()
    val pairedDevices by viewModel.pairedDevices.collectAsState()

    var deviceForDirectSend by remember { mutableStateOf<Device?>(null) }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (!uris.isNullOrEmpty()) {
            if (settings.hapticsEnabled) {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            }
            viewModel.onFilesPicked(uris, context, deviceForDirectSend)
            deviceForDirectSend = null
        }
    }

    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { treeUri ->
        if (treeUri != null) {
            if (settings.hapticsEnabled) {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            }
            viewModel.onFolderPicked(treeUri, context, deviceForDirectSend)
            deviceForDirectSend = null
        }
    }

    val showGenerateQr by viewModel.showGenerateQr.collectAsState()
    val favoriteIds = remember(pairedDevices) {
        pairedDevices.filter { it.isFavorite }.map { it.id }.toSet()
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag("home_screen"),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 1. Header: Logo, App Name, Device Chip & Network Status
        item {
            HomeHeaderSection(
                deviceName = settings.deviceName,
                connectionName = connectionStatus.networkName,
                isConnected = connectionStatus.isConnected,
                isScanning = isScanning,
                onRefresh = { viewModel.refreshDiscovery() },
                onPairClick = { viewModel.openGenerateQr() }
            )
        }

        // 2. Quick Actions: SEND FILES & RECEIVE FILES Cards + Folder Send shortcut
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    QuickActionCard(
                        title = "SEND FILES",
                        description = "Send files to nearby devices",
                        icon = Icons.Default.FileUpload,
                        accentColor = CyanAccent,
                        buttonText = "Select Files",
                        onButtonClick = {
                            deviceForDirectSend = null
                            filePickerLauncher.launch(arrayOf("*/*"))
                        },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("quick_action_send")
                    )

                    QuickActionCard(
                        title = "RECEIVE FILES",
                        description = if (isReceiving) "Receiving is active on network" else "Ready to accept incoming files",
                        icon = Icons.Default.Download,
                        accentColor = TealPrimary,
                        buttonText = if (isReceiving) "Receiving Active" else "Start Receiving",
                        isToggled = isReceiving,
                        onButtonClick = {
                            if (settings.hapticsEnabled) {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            }
                            viewModel.toggleReceiving()
                        },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("quick_action_receive")
                    )
                }

                OutlinedButton(
                    onClick = {
                        deviceForDirectSend = null
                        folderPickerLauncher.launch(null)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("send_folder_button"),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.CreateNewFolder,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Send Entire Folder (Preserves Subfolders)")
                }
            }
        }

        // 3. Quick Send Favorites & Recent Destinations Rail
        if (quickSendDevices.isNotEmpty()) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Bolt,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Quick Send",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Text(
                            text = "Tap peer to pick files",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("quick_send_rail")
                    ) {
                        items(quickSendDevices, key = { it.id }) { peer ->
                            QuickSendPeerCard(
                                device = peer,
                                isFavorite = peer.id in favoriteIds,
                                isLastDestination = peer.id == settings.lastDestinationDeviceId,
                                onClick = {
                                    deviceForDirectSend = peer
                                    filePickerLauncher.launch(arrayOf("*/*"))
                                }
                            )
                        }
                    }
                }
            }
        }

        // 4. Utility Shortcuts (Received Files, Network Diagnostics, Privacy)
        item {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                item {
                    AssistChip(
                        onClick = onNavigateToReceivedFiles,
                        label = { Text("Received Files") },
                        leadingIcon = {
                            Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(16.dp))
                        },
                        modifier = Modifier.testTag("home_chip_received_files")
                    )
                }
                item {
                    AssistChip(
                        onClick = onNavigateToDiagnostics,
                        label = { Text("Diagnostics") },
                        leadingIcon = {
                            Icon(Icons.Default.NetworkCheck, contentDescription = null, modifier = Modifier.size(16.dp))
                        },
                        modifier = Modifier.testTag("home_chip_diagnostics")
                    )
                }
                item {
                    AssistChip(
                        onClick = onNavigateToPrivacyDashboard,
                        label = { Text("Privacy & Security") },
                        leadingIcon = {
                            Icon(Icons.Default.PrivacyTip, contentDescription = null, modifier = Modifier.size(16.dp))
                        },
                        modifier = Modifier.testTag("home_chip_privacy")
                    )
                }
            }
        }

        // 5. Transfer Summary Card
        item {
            TransferSummaryCard(
                activeTransfers = transferStats.activeCount,
                completedTransfers = transferStats.completedCount,
                totalFiles = transferStats.totalFilesTransferred,
                totalBytes = transferStats.formattedTotalBytes,
                averageSpeed = transferStats.formattedAverageSpeed,
                onClick = onNavigateToTransfers
            )
        }

        // 6. Nearby Devices Header with Pulse Indicator & "View all"
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Nearby Devices",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    PulseScanningIndicator(isScanning = isScanning)
                }

                Text(
                    text = "View All (${discoveredDevices.size})",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .clickable(onClick = onNavigateToDevices)
                        .padding(4.dp)
                )
            }
        }

        // 7. Discovered Devices List / Preview
        if (discoveredDevices.isEmpty()) {
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp)),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                    ),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            imageVector = Icons.Default.Devices,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier.size(36.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Looking for devices on network…",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Make sure Tauri desktop or mobile peers are on the same Wi-Fi.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp)
                        )
                    }
                }
            }
        } else {
            items(
                items = discoveredDevices.take(3),
                key = { it.id }
            ) { device ->
                DeviceCard(
                    device = device,
                    onClick = onNavigateToDevices,
                    onSendClick = {
                        deviceForDirectSend = device
                        filePickerLauncher.launch(arrayOf("*/*"))
                    }
                )
            }
        }

        item {
            Spacer(modifier = Modifier.height(16.dp))
        }
    }

    if (showGenerateQr) {
        com.example.presentation.pairing.GenerateQrDialog(
            pairingManager = viewModel.pairingManager,
            onDismiss = { viewModel.closeGenerateQr() }
        )
    }

    if (showFileSelectionDialog) {
        com.example.presentation.transfer.FileSelectionDialog(
            files = selectedFiles,
            onRemoveFile = { viewModel.removeSelectedFile(it) },
            onAddFiles = { viewModel.addMoreFiles(it) },
            onClearAll = { viewModel.clearSelectedFiles() },
            onProceedToDevices = { viewModel.proceedToDeviceSelection() },
            onDismiss = { viewModel.dismissFileFlow() }
        )
    }

    if (showDeviceSelectionDialog) {
        com.example.presentation.transfer.DeviceSelectionDialog(
            files = selectedFiles,
            discoveredDevices = discoveredDevices,
            pairedDevices = pairedDevices,
            lastDestinationDeviceId = settings.lastDestinationDeviceId,
            onSendToDevices = { targets ->
                viewModel.sendSelectedFilesToTargets(targets)
                onNavigateToTransfers()
            },
            onProceedToConfirmation = { targets ->
                viewModel.proceedToConfirmation(targets)
            },
            onBackToFiles = { viewModel.backToFileSelection() },
            onRefreshDiscovery = { viewModel.refreshDiscovery() },
            onDismiss = { viewModel.dismissFileFlow() }
        )
    }

    if (showConfirmationDialog) {
        com.example.presentation.transfer.TransferConfirmationDialog(
            files = selectedFiles,
            targets = pendingTargets,
            estimatedSeconds = viewModel.estimateTransferSeconds(),
            onModifyFiles = { viewModel.backToFileSelection() },
            onChangeDevices = { viewModel.backFromConfirmationToDevices() },
            onConfirmSend = {
                if (settings.hapticsEnabled) {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                }
                viewModel.confirmAndSendTransfers()
                onNavigateToTransfers()
            },
            onDismiss = { viewModel.dismissFileFlow() }
        )
    }

    incomingRequest?.let { req ->
        com.example.presentation.transfer.IncomingTransferDialog(
            request = req,
            onAccept = {
                if (settings.hapticsEnabled) {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                }
                viewModel.acceptIncomingTransfer(req)
                onNavigateToTransfers()
            },
            onDecline = {
                viewModel.rejectIncomingTransfer(req)
            }
        )
    }
}

@Composable
private fun QuickSendPeerCard(
    device: Device,
    isFavorite: Boolean,
    isLastDestination: Boolean,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .width(156.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .testTag("quick_send_peer_${device.id}"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
        ),
        border = BorderStroke(
            1.dp,
            if (isFavorite) MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
            else MaterialTheme.colorScheme.outline.copy(alpha = 0.22f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                PlatformBadge(deviceType = device.deviceType, modifier = Modifier.size(32.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (isFavorite) {
                        Icon(
                            imageVector = Icons.Default.Star,
                            contentDescription = "Favorite",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                    }
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(if (device.isOnline) StatusSuccess else Color.Gray)
                    )
                }
            }
            Text(
                text = device.alias,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = when {
                    isLastDestination -> "Last used"
                    device.isOnline -> "Online • Tap to send"
                    else -> device.ipAddress
                },
                style = MaterialTheme.typography.labelSmall,
                color = if (isLastDestination) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun HomeHeaderSection(
    deviceName: String,
    connectionName: String,
    isConnected: Boolean,
    isScanning: Boolean,
    onRefresh: () -> Unit,
    onPairClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp)),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
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
                    Box(
                        modifier = Modifier
                            .size(42.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Brush.linearGradient(listOf(TealDark, CyanAccent))),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.SwapHoriz,
                            contentDescription = "Logo",
                            tint = Color.White,
                            modifier = Modifier.size(24.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    Column {
                        Text(
                            text = "LocalDrop",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Cross-Platform Local Share",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    FilledTonalButton(
                        onClick = onPairClick,
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        modifier = Modifier
                            .height(34.dp)
                            .testTag("pair_device_home_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.QrCodeScanner,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Pair", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                    }

                    Spacer(modifier = Modifier.width(6.dp))

                    IconButton(
                        onClick = onRefresh,
                        modifier = Modifier.testTag("refresh_home_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Refresh",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.7f))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(TealPrimary)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = deviceName,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = if (isConnected) Icons.Default.Wifi else Icons.Default.WifiOff,
                        contentDescription = "Network",
                        tint = if (isConnected) StatusSuccess else StatusWarning,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = connectionName,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun QuickActionCard(
    title: String,
    description: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    accentColor: Color,
    buttonText: String,
    onButtonClick: () -> Unit,
    modifier: Modifier = Modifier,
    isToggled: Boolean = false
) {
    Card(
        modifier = modifier
            .clip(RoundedCornerShape(18.dp)),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
        ),
        border = BorderStroke(
            1.dp,
            if (isToggled) accentColor.copy(alpha = 0.5f) else MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(accentColor.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = title,
                    tint = accentColor,
                    modifier = Modifier.size(22.dp)
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(modifier = Modifier.height(2.dp))

            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                minLines = 2,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(12.dp))

            Button(
                onClick = onButtonClick,
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isToggled) accentColor else MaterialTheme.colorScheme.primary,
                    contentColor = if (isToggled) Color.White else MaterialTheme.colorScheme.onPrimary
                ),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = buttonText,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun TransferSummaryCard(
    activeTransfers: Int,
    completedTransfers: Int,
    totalFiles: Int,
    totalBytes: String,
    averageSpeed: String,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("transfer_summary_card")
            .clip(RoundedCornerShape(18.dp))
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
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
                    Icon(
                        imageVector = Icons.Default.SwapHoriz,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Transfer Summary",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                Text(
                    text = if (averageSpeed != "—") "Avg $averageSpeed • Details →" else "Details →",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                SummaryMetricItem(
                    label = "Active",
                    value = activeTransfers.toString(),
                    highlightColor = if (activeTransfers > 0) CyanAccent else MaterialTheme.colorScheme.onSurface
                )
                SummaryMetricItem(
                    label = "Completed",
                    value = completedTransfers.toString(),
                    highlightColor = StatusSuccess
                )
                SummaryMetricItem(
                    label = "Total Files",
                    value = totalFiles.toString(),
                    highlightColor = MaterialTheme.colorScheme.primary
                )
                SummaryMetricItem(
                    label = "Total Size",
                    value = totalBytes,
                    highlightColor = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

@Composable
private fun SummaryMetricItem(
    label: String,
    value: String,
    highlightColor: Color
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = highlightColor
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
