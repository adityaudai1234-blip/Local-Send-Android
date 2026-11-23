package com.example.presentation.privacy

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.presentation.components.StatusBadge
import com.example.ui.theme.StatusSuccess
import com.example.ui.theme.TealPrimary
import kotlinx.coroutines.launch

data class PrivacyPolicySection(
    val title: String,
    val summary: String
)

object LocalDropPrivacyPolicyContent {
    val sections: List<PrivacyPolicySection> = listOf(
        PrivacyPolicySection(
            title = "1. Local-Network Communication",
            summary = "LocalDrop communicates exclusively on your local Wi-Fi or wired LAN. No files, device names, pairing tokens, or analytics are sent to external servers or cloud relays."
        ),
        PrivacyPolicySection(
            title = "2. File Transfer Behavior",
            summary = "Files and folders move directly from sender to receiver over local TCP sockets (with optional TLS v2 framing) and are verified with SHA-256 checksums before being finalized."
        ),
        PrivacyPolicySection(
            title = "3. Device Discovery",
            summary = "When broadcasting is enabled, LocalDrop announces its chosen device alias, platform type, and listening port on your local subnet via Android NSD/mDNS (_lsend._tcp). You can turn off broadcasting anytime in Settings."
        ),
        PrivacyPolicySection(
            title = "4. Pairing & Authentication",
            summary = "QR-code pairing exchanges short-lived session secrets and stores long-lived per-device authentication tokens encrypted with Android Keystore AES-256-GCM inside the app-private Room database."
        ),
        PrivacyPolicySection(
            title = "5. Transfer History Storage",
            summary = "Transfer metadata (filenames, sizes, timestamps, peer names, status, and SHA-256 hashes) is stored locally in an on-device Room SQLite database so you can inspect or clear your history anytime."
        ),
        PrivacyPolicySection(
            title = "6. Received-File Storage",
            summary = "Incoming files are written to a temporary app cache until checksum verification completes, then saved to Downloads/LocalDrop or the custom Storage Access Framework (SAF) folder you selected."
        ),
        PrivacyPolicySection(
            title = "7. Android Permissions",
            summary = "Camera permission is used solely for scanning pairing QR codes. Nearby Wi-Fi Devices (Android 13+) assists local discovery, and Notifications (Android 13+) displays transfer alerts. File selection uses zero-permission system pickers."
        ),
        PrivacyPolicySection(
            title = "8. Security Mechanisms & Scope",
            summary = "LocalDrop applies Keystore token encryption, filename/path sanitization, default-deny incoming transfer approval, and opportunistic TLS on v2 peers. Note that plaintext LAN fallback remains supported only when interoperating with legacy/unencrypted peers unless TLS is mutually available."
        ),
        PrivacyPolicySection(
            title = "9. Data Retention & Deletion",
            summary = "You retain total control over local records: clear temporary caches, remove individual transfer history entries, unpair trusted devices, or wipe all credentials and history at any time in the Privacy Dashboard."
        )
    )

    fun asPlainText(): String = buildString {
        appendLine("LocalDrop Privacy Policy (Zero-Cloud Peer-to-Peer)")
        appendLine()
        sections.forEach { section ->
            appendLine(section.title)
            appendLine(section.summary)
            appendLine()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrivacyPolicyScreen(
    onBack: () -> Unit,
    onOpenPrivacyDashboard: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .testTag("privacy_policy_screen"),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Privacy Information", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text("Zero-Cloud Local Policy", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("privacy_policy_back_button")) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                            cm?.setPrimaryClip(ClipData.newPlainText("LocalDrop Privacy Policy", LocalDropPrivacyPolicyContent.asPlainText()))
                            scope.launch { snackbarHostState.showSnackbar("Privacy Policy copied to clipboard") }
                        },
                        modifier = Modifier.testTag("copy_privacy_policy_button")
                    ) {
                        Icon(Icons.Default.ContentCopy, contentDescription = "Copy Privacy Policy")
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
                        containerColor = TealPrimary.copy(alpha = 0.1f)
                    ),
                    border = BorderStroke(1.dp, TealPrimary.copy(alpha = 0.3f))
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.CloudOff, contentDescription = null, tint = TealPrimary)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("100% On-Device & Local LAN", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                            }
                            StatusBadge(text = "No Cloud", color = StatusSuccess, showDot = false)
                        }
                        Text(
                            text = "LocalDrop collects no personal analytics, creates no cloud accounts, and never sends your files to third-party servers.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (onOpenPrivacyDashboard != null) {
                            TextButton(
                                onClick = onOpenPrivacyDashboard,
                                modifier = Modifier.testTag("open_privacy_dashboard_from_policy")
                            ) {
                                Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Open Privacy Controls & Data Wipe")
                            }
                        }
                    }
                }
            }

            items(LocalDropPrivacyPolicyContent.sections, key = { it.title }) { section ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f)
                    ),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.VerifiedUser, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(section.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        }
                        Text(
                            text = section.summary,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            item { Spacer(modifier = Modifier.height(16.dp)) }
        }
    }
}
