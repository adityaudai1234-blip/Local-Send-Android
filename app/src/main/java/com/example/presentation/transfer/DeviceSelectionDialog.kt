package com.example.presentation.transfer

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.domain.model.Device
import com.example.domain.model.PairedDevice
import com.example.domain.model.TransferItem
import com.example.presentation.components.PlatformBadge
import com.example.presentation.components.PulseScanningIndicator
import com.example.ui.theme.CyanAccent
import com.example.ui.theme.StatusSuccess

@Composable
fun DeviceSelectionDialog(
    files: List<SelectedFile>,
    discoveredDevices: List<Device>,
    pairedDevices: List<PairedDevice>,
    onSendToDevices: (List<Device>) -> Unit,
    onBackToFiles: () -> Unit,
    onRefreshDiscovery: () -> Unit,
    onDismiss: () -> Unit
) {
    val totalBytes = files.sumOf { it.size }
    val formattedSize = TransferItem.formatBytes(totalBytes)

    // Build a unified device list from discovered and paired devices
    val allDevices = remember(discoveredDevices, pairedDevices) {
        val list = mutableListOf<Device>()
        // Discovered peers take precedence as active
        list.addAll(discoveredDevices)
        // Add paired devices not currently in discovered list
        val discoveredIds = discoveredDevices.map { it.id }.toSet()
        val discoveredIps = discoveredDevices.map { it.ipAddress }.toSet()
        pairedDevices.forEach { paired ->
            if (paired.id !in discoveredIds && paired.ipAddress !in discoveredIps) {
                list.add(
                    Device(
                        id = paired.id,
                        alias = paired.alias,
                        deviceType = paired.deviceType,
                        ipAddress = paired.ipAddress,
                        port = paired.port,
                        isOnline = paired.isConnected
                    )
                )
            }
        }
        list
    }

    val selectedDeviceIds = remember {
        mutableStateListOf<String>().apply {
            // Default select the first available device if present
            allDevices.firstOrNull()?.let { add(it.id) }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("device_selection_dialog"),
        shape = RoundedCornerShape(24.dp),
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Select Destination",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Sending ${files.size} file(s) • $formattedSize",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                IconButton(
                    onClick = onRefreshDiscovery,
                    modifier = Modifier.testTag("refresh_device_selection_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Refresh Devices",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                if (allDevices.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 28.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                imageVector = Icons.Default.Devices,
                                contentDescription = null,
                                modifier = Modifier.size(36.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            Text(
                                text = "Searching for nearby devices…",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Ensure the Tauri app or mobile peer is open on the same network.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 300.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(items = allDevices, key = { it.id }) { device ->
                            val isSelected = device.id in selectedDeviceIds
                            SelectableDeviceRow(
                                device = device,
                                isSelected = isSelected,
                                onToggle = {
                                    if (isSelected) {
                                        selectedDeviceIds.remove(device.id)
                                    } else {
                                        selectedDeviceIds.add(device.id)
                                    }
                                }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val chosen = allDevices.filter { it.id in selectedDeviceIds }
                    if (chosen.isNotEmpty()) {
                        onSendToDevices(chosen)
                    }
                },
                enabled = selectedDeviceIds.isNotEmpty(),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.testTag("confirm_send_files_button")
            ) {
                Icon(
                    imageVector = Icons.Default.Send,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = if (selectedDeviceIds.size > 1) {
                        "Send to (${selectedDeviceIds.size}) Devices"
                    } else {
                        "Send Files"
                    }
                )
            }
        },
        dismissButton = {
            TextButton(
                onClick = onBackToFiles,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.testTag("back_to_files_button")
            ) {
                Text("← Back to Files")
            }
        }
    )
}

@Composable
private fun SelectableDeviceRow(
    device: Device,
    isSelected: Boolean,
    onToggle: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onToggle)
            .testTag("device_select_row_${device.id}"),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected)
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
            else
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        ),
        border = BorderStroke(
            1.dp,
            if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(
                checked = isSelected,
                onCheckedChange = { onToggle() },
                colors = CheckboxDefaults.colors(
                    checkedColor = MaterialTheme.colorScheme.primary
                )
            )

            Spacer(modifier = Modifier.width(4.dp))

            PlatformBadge(deviceType = device.deviceType)

            Spacer(modifier = Modifier.width(10.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = device.alias,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "${device.ipAddress}:${device.port}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Online indicator dot
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(if (device.isOnline) StatusSuccess else Color.Gray)
            )
        }
    }
}
