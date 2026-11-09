package com.example.presentation.pairing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.data.pairing.PairingResult
import com.example.ui.theme.StatusError
import com.example.ui.theme.StatusSuccess

@Composable
fun PairingProgressDialog(
    result: PairingResult,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (result is PairingResult.Idle) return

    Dialog(
        onDismissRequest = {
            if (result !is PairingResult.InProgress) onDismiss()
        },
        properties = DialogProperties(
            dismissOnBackPress = result !is PairingResult.InProgress,
            dismissOnClickOutside = result !is PairingResult.InProgress,
            usePlatformDefaultWidth = false
        )
    ) {
        Surface(
            modifier = modifier
                .fillMaxWidth(0.85f)
                .clip(RoundedCornerShape(24.dp))
                .testTag("pairing_progress_dialog"),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                when (result) {
                    is PairingResult.InProgress -> {
                        CircularProgressIndicator(
                            modifier = Modifier.size(48.dp),
                            color = MaterialTheme.colorScheme.primary,
                            strokeWidth = 3.dp
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = result.status,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Exchanging security tokens over local TCP…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }

                    is PairingResult.Success -> {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = "Success",
                            tint = StatusSuccess,
                            modifier = Modifier.size(54.dp)
                        )
                        Spacer(modifier = Modifier.height(14.dp))
                        Text(
                            text = "Pairing Successful!",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Device '${result.device.alias}' is now trusted and paired.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(20.dp))
                        Button(
                            onClick = onDismiss,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("pairing_success_done_button")
                        ) {
                            Text("Done")
                        }
                    }

                    is PairingResult.Failure -> {
                        Icon(
                            imageVector = Icons.Default.Error,
                            contentDescription = "Failed",
                            tint = StatusError,
                            modifier = Modifier.size(54.dp)
                        )
                        Spacer(modifier = Modifier.height(14.dp))
                        Text(
                            text = "Pairing Failed",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = result.reason,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(20.dp))
                        Button(
                            onClick = onDismiss,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("pairing_failure_close_button")
                        ) {
                            Text("Close")
                        }
                    }

                    PairingResult.Idle -> {}
                }
            }
        }
    }
}
