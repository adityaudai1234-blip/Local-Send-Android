package com.example.presentation.pairing

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.data.pairing.PairingManager
import com.example.data.pairing.PairingResult
import com.example.data.pairing.QrCodeGenerator
import com.example.presentation.components.StatusBadge
import com.example.ui.theme.CyanAccent
import com.example.ui.theme.StatusError
import com.example.ui.theme.StatusSuccess
import com.example.ui.theme.StatusWarning

@Composable
fun GenerateQrDialog(
    pairingManager: PairingManager,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val activeSession by pairingManager.activeSession.collectAsState()
    val pairingResult by pairingManager.pairingResult.collectAsState()

    // Ensure session is started when dialog opens
    LaunchedEffect(Unit) {
        if (activeSession == null || activeSession?.remainingSeconds == 0) {
            pairingManager.generateNewPairingSession()
        }
    }

    val session = activeSession
    val qrBitmap = remember(session?.payload?.toJson()) {
        session?.payload?.toJson()?.let { content ->
            QrCodeGenerator.generateQrBitmap(content = content, sizePx = 600)
        }
    }

    Dialog(
        onDismissRequest = {
            pairingManager.cancelActiveSession()
            onDismiss()
        },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = modifier
                .fillMaxWidth(0.92f)
                .clip(RoundedCornerShape(24.dp))
                .testTag("generate_qr_dialog"),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "Pair New Device",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Scan from desktop or mobile",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    IconButton(
                        onClick = {
                            pairingManager.cancelActiveSession()
                            onDismiss()
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // QR Code Container with high contrast white background
                Box(
                    modifier = Modifier
                        .size(240.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color.White)
                        .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f), RoundedCornerShape(16.dp))
                        .padding(12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    if (qrBitmap != null) {
                        Image(
                            bitmap = qrBitmap.asImageBitmap(),
                            contentDescription = "Pairing QR Code",
                            modifier = Modifier.size(216.dp)
                        )
                    } else {
                        CircularProgressIndicator(
                            color = CyanAccent,
                            modifier = Modifier.size(36.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Live Countdown Timer
                val seconds = session?.remainingSeconds ?: 0
                val minutes = seconds / 60
                val secRemain = seconds % 60
                val timeStr = String.format("%d:%02d", minutes, secRemain)

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (seconds > 20) MaterialTheme.colorScheme.surfaceVariant else StatusWarning.copy(alpha = 0.15f))
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.AccessTime,
                        contentDescription = null,
                        tint = if (seconds > 20) MaterialTheme.colorScheme.primary else StatusWarning,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (seconds > 0) "Valid for $timeStr" else "Expired",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = if (seconds > 20) MaterialTheme.colorScheme.primary else StatusWarning
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Status Banner
                val statusText = session?.status ?: "Ready to pair"
                when (pairingResult) {
                    is PairingResult.Success -> {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(vertical = 4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = StatusSuccess,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Paired with ${(pairingResult as PairingResult.Success).device.alias}!",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                color = StatusSuccess
                            )
                        }
                    }
                    is PairingResult.Failure -> {
                        Text(
                            text = (pairingResult as PairingResult.Failure).reason,
                            style = MaterialTheme.typography.bodySmall,
                            color = StatusError,
                            textAlign = TextAlign.Center
                        )
                    }
                    else -> {
                        Text(
                            text = statusText,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Actions: Regenerate & Close
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            pairingManager.generateNewPairingSession()
                        },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("regenerate_qr_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Regenerate")
                    }

                    Button(
                        onClick = {
                            pairingManager.cancelActiveSession()
                            onDismiss()
                        },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("close_qr_button")
                    ) {
                        Text("Done")
                    }
                }
            }
        }
    }
}
