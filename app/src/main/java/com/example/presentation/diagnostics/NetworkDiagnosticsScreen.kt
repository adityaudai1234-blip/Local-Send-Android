package com.example.presentation.diagnostics

import android.text.format.DateFormat
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
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material3.FilterChip
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.example.domain.model.AppErrorMapper
import com.example.domain.model.ErrorCategory
import com.example.presentation.components.CategorizedErrorCard
import com.example.presentation.components.SectionHeader
import com.example.presentation.components.StatusBadge
import com.example.ui.theme.StatusError
import com.example.ui.theme.StatusSuccess
import com.example.ui.theme.StatusWarning
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NetworkDiagnosticsScreen(
    viewModel: DiagnosticsViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    BackHandler(onBack = onBack)

    val report by viewModel.report.collectAsState()
    val pairedDevices by viewModel.pairedDevices.collectAsState()
    val recentErrors by viewModel.recentErrors.collectAsState()
    val appErrors by viewModel.appErrors.collectAsState()
    val lastCrashSummary by viewModel.lastCrashSummary.collectAsState()
    var selectedErrorCategory by remember { mutableStateOf<ErrorCategory?>(null) }

    val combinedErrors = remember(appErrors, recentErrors) {
        if (appErrors.isNotEmpty()) {
            appErrors
        } else {
            recentErrors.map { ev ->
                AppErrorMapper.fromMessage(
                    category = ErrorCategory.CONNECTION,
                    userMessage = ev.message,
                    technicalDetails = ev.message,
                    targetLabel = ev.targetLabel,
                    address = ev.address
                ).copy(id = ev.id, timestamp = ev.timestamp)
            }
        }
    }
    val filteredErrors = remember(combinedErrors, selectedErrorCategory) {
        if (selectedErrorCategory == null) combinedErrors
        else combinedErrors.filter { it.category == selectedErrorCategory }
    }

    LaunchedEffect(Unit) {
        viewModel.refreshStatus()
    }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .testTag("network_diagnostics_screen"),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "Network Diagnostics",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = report.networkName,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.testTag("diagnostics_back_button")
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(
                        onClick = { viewModel.refreshStatus() },
                        modifier = Modifier.testTag("diagnostics_refresh_button")
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh Diagnostics")
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
            item { SectionHeader(title = "Live Network & Socket State") }
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                    ),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.22f))
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Wifi, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("LAN Status", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                            }
                            StatusBadge(
                                text = if (report.isNetworkConnected) "Connected" else "Offline",
                                color = if (report.isNetworkConnected) StatusSuccess else StatusWarning
                            )
                        }
                        DiagRow("Network Name", report.networkName)
                        DiagRow("Local IPv4 Address", report.localIp)
                        DiagRow("Active Interfaces", report.interfaceSummary)
                        DiagRow("mDNS Discovery", report.discoveryStatusText)
                        DiagRow(
                            "TCP Listener",
                            if (report.isServerRunning) "Listening on port ${report.listeningPort}" else "Stopped"
                        )
                        DiagRow("Discovered Nearby", "${report.discoveredCount} peer(s)")
                        DiagRow("Connected Paired Peers", "${report.connectedPairedCount} peer(s)")
                        DiagRow("Network Monitor", if (report.isNetworkMonitorActive) "Active (auto-reconnect enabled)" else "Unavailable")
                    }
                }
            }

            item { SectionHeader(title = "Interactive Tests") }
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                    ),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.22f))
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = { viewModel.runLocalNetworkAndPortTest() },
                                enabled = !report.isTesting,
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("test_port_button"),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(Icons.Default.NetworkCheck, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(if (report.isTesting) "Testing…" else "Check Port", style = MaterialTheme.typography.labelMedium)
                            }

                            FilledTonalButton(
                                onClick = { viewModel.restartDiscovery() },
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("restart_discovery_button"),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(Icons.Default.Router, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Restart mDNS", style = MaterialTheme.typography.labelMedium)
                            }
                        }

                        OutlinedButton(
                            onClick = { viewModel.triggerReconnectAll() },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("reconnect_all_peers_button"),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.Sync, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Trigger Bounded Reconnect (Max 3 Retries)")
                        }

                        report.localPortCheckMessage?.let { msg ->
                            Text(
                                text = msg,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
            }

            if (pairedDevices.isNotEmpty()) {
                item { SectionHeader(title = "Paired Peer Latency Checks") }
                items(pairedDevices, key = { it.id }) { peer ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                        ),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.18f))
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(peer.alias, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                                Text(
                                    text = report.peerLatencyResults[peer.id] ?: "${peer.ipAddress}:${peer.port}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            FilledTonalButton(
                                onClick = { viewModel.pingPairedDevice(peer) },
                                modifier = Modifier.testTag("ping_peer_${peer.id}")
                            ) {
                                Text("Ping")
                            }
                        }
                    }
                }
            }

            if (!lastCrashSummary.isNullOrBlank()) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        SectionHeader(title = "Local Crash Diagnostic (On-Device Only)")
                        TextButton(
                            onClick = { viewModel.clearLastCrashSummary() },
                            modifier = Modifier.testTag("clear_crash_log_button")
                        ) {
                            Text("Clear Log")
                        }
                    }
                }
                item {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("last_crash_summary_card"),
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(containerColor = StatusWarning.copy(alpha = 0.1f)),
                        border = BorderStroke(1.dp, StatusWarning.copy(alpha = 0.3f))
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.BugReport, contentDescription = null, tint = StatusWarning, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Previous Unhandled Exception Snapshot", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                            }
                            Text(
                                text = lastCrashSummary.orEmpty(),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        SectionHeader(title = "Categorized Error Log (${filteredErrors.size})")
                        if (combinedErrors.isNotEmpty()) {
                            TextButton(
                                onClick = { viewModel.clearErrors() },
                                modifier = Modifier.testTag("clear_diagnostics_errors_button")
                            ) {
                                Text("Clear")
                            }
                        }
                    }

                    if (combinedErrors.isNotEmpty()) {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            item {
                                FilterChip(
                                    selected = selectedErrorCategory == null,
                                    onClick = { selectedErrorCategory = null },
                                    label = { Text("All (${combinedErrors.size})") }
                                )
                            }
                            items(ErrorCategory.entries) { cat ->
                                val count = combinedErrors.count { it.category == cat }
                                if (count > 0) {
                                    FilterChip(
                                        selected = selectedErrorCategory == cat,
                                        onClick = {
                                            selectedErrorCategory = if (selectedErrorCategory == cat) null else cat
                                        },
                                        label = { Text("${cat.label} ($count)") }
                                    )
                                }
                            }
                        }
                    }
                }
            }

            if (filteredErrors.isEmpty()) {
                item {
                    Text(
                        text = "No connection, transfer, or protocol errors logged in this session.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp)
                    )
                }
            } else {
                items(filteredErrors, key = { it.id }) { err ->
                    CategorizedErrorCard(
                        error = err,
                        timestampText = DateFormat.format("HH:mm:ss", Date(err.timestamp)).toString()
                    )
                }
            }

            item { SectionHeader(title = "Troubleshooting Guide") }
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        TipRow("Different Subnets", "Ensure both devices are on the same Wi-Fi band/subnet and VPNs are paused.")
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.14f))
                        TipRow("AP / Client Isolation", "Guest Wi-Fi networks often block peer-to-peer mDNS and direct TCP sockets.")
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.14f))
                        TipRow("Desktop Firewall", "Allow incoming TCP on port ${report.listeningPort} and UDP 5353 in Windows/macOS/Linux firewall.")
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.14f))
                        TipRow("Peer Unreachable", "Use 'Restart mDNS' if the remote laptop slept or changed DHCP IP address.")
                    }
                }
            }

            item { Spacer(modifier = Modifier.height(16.dp)) }
        }
    }
}

@Composable
private fun DiagRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun TipRow(title: String, detail: String) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(Icons.Default.WarningAmber, contentDescription = null, tint = StatusWarning, modifier = Modifier.size(18.dp))
        Spacer(modifier = Modifier.width(8.dp))
        Column {
            Text(title, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
            Text(detail, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
