package com.example.presentation.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.data.security.SecurityValidator
import com.example.domain.model.ThemeMode

@Composable
fun EditDeviceNameDialog(
    initialName: String,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var tempName by remember { mutableStateOf(initialName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit Device Name") },
        text = {
            OutlinedTextField(
                value = tempName,
                onValueChange = { tempName = it },
                label = { Text("Device Alias") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("input_device_name")
            )
        },
        confirmButton = {
            Button(
                onClick = {
                    if (tempName.isNotBlank()) onSave(tempName.trim())
                    onDismiss()
                },
                enabled = tempName.isNotBlank(),
                modifier = Modifier.testTag("save_device_name_button")
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
fun ThemeSelectionDialog(
    currentMode: ThemeMode,
    onSelect: (ThemeMode) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Choose Theme") },
        text = {
            Column {
                SettingsOptionRow("Follow System", currentMode == ThemeMode.SYSTEM) {
                    onSelect(ThemeMode.SYSTEM)
                    onDismiss()
                }
                SettingsOptionRow("Light Theme", currentMode == ThemeMode.LIGHT) {
                    onSelect(ThemeMode.LIGHT)
                    onDismiss()
                }
                SettingsOptionRow("Dark Theme", currentMode == ThemeMode.DARK) {
                    onSelect(ThemeMode.DARK)
                    onDismiss()
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}

@Composable
fun EditPortDialog(
    currentPort: Int,
    onSave: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    var portInput by remember { mutableStateOf(currentPort.toString()) }
    val parsed = portInput.toIntOrNull()
    val isValid = parsed != null && SecurityValidator.isValidPort(parsed)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Listening TCP Port") },
        text = {
            Column {
                OutlinedTextField(
                    value = portInput,
                    onValueChange = { portInput = it.filter { ch -> ch.isDigit() } },
                    label = { Text("Port (1024 - 65535)") },
                    singleLine = true,
                    isError = !isValid,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    supportingText = {
                        Text(
                            if (isValid) "Default LocalDrop/Tauri port is 53317."
                            else "Enter a valid non-privileged port between 1024 and 65535."
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (parsed != null && isValid) {
                        onSave(parsed)
                        onDismiss()
                    }
                },
                enabled = isValid
            ) {
                Text("Apply")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
fun SingleChoiceDialog(
    title: String,
    options: List<Pair<String, () -> Unit>>,
    selectedLabel: String,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                options.forEach { (label, onClick) ->
                    SettingsOptionRow(
                        title = label,
                        selected = label == selectedLabel,
                        onSelect = {
                            onClick()
                            onDismiss()
                        }
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}

@Composable
fun ClearCredentialsConfirmDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Clear Stored Credentials?") },
        text = {
            Text("This deletes all trusted device records and erases every authentication key stored in Android Keystore. All peers must re-pair via QR code before transferring again.")
        },
        confirmButton = {
            Button(
                onClick = {
                    onConfirm()
                    onDismiss()
                },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
            ) {
                Text("Clear Credentials")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
fun InfoNoticeDialog(
    title: String,
    body: String,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body, style = MaterialTheme.typography.bodySmall) },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}

@Composable
private fun SettingsOptionRow(
    title: String,
    selected: Boolean,
    onSelect: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Spacer(modifier = Modifier.width(8.dp))
        Text(text = title, style = MaterialTheme.typography.bodyLarge)
    }
}
