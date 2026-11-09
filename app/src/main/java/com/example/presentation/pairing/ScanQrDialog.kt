package com.example.presentation.pairing

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.data.pairing.PairingPayload
import com.example.domain.model.DeviceType
import com.example.ui.theme.CyanAccent
import com.example.ui.theme.StatusError
import com.example.ui.theme.StatusSuccess
import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import java.nio.ByteBuffer
import java.util.concurrent.Executors

@Composable
fun ScanQrDialog(
    onPayloadScanned: (PairingPayload) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }

    var selectedTab by remember { mutableIntStateOf(0) } // 0: Camera Scan, 1: Manual / Test Code
    var manualInputText by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isProcessing by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasCameraPermission = isGranted
        if (!isGranted) {
            selectedTab = 1 // Switch to manual / simulated entry
        }
    }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = modifier
                .fillMaxWidth(0.92f)
                .clip(RoundedCornerShape(24.dp))
                .testTag("scan_qr_dialog"),
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
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.QrCodeScanner,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Scan Device QR",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }

                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Tabs: Camera Viewfinder vs Manual / Test Input
                TabRow(
                    selectedTabIndex = selectedTab,
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    contentColor = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clip(RoundedCornerShape(12.dp))
                ) {
                    Tab(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        text = { Text("Camera Scan") }
                    )
                    Tab(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        text = { Text("Manual / Test Code") }
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                if (selectedTab == 0) {
                    // Camera Scanner View
                    if (hasCameraPermission) {
                        Box(
                            modifier = Modifier
                                .size(260.dp)
                                .clip(RoundedCornerShape(20.dp))
                                .background(Color.Black),
                            contentAlignment = Alignment.Center
                        ) {
                            AndroidView(
                                factory = { ctx ->
                                    val previewView = PreviewView(ctx)
                                    val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                                    val executor = Executors.newSingleThreadExecutor()

                                    cameraProviderFuture.addListener({
                                        try {
                                            val cameraProvider = cameraProviderFuture.get()
                                            val preview = Preview.Builder().build().also {
                                                it.setSurfaceProvider(previewView.surfaceProvider)
                                            }

                                            val imageAnalysis = ImageAnalysis.Builder()
                                                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                                .build()

                                            val reader = MultiFormatReader()

                                            imageAnalysis.setAnalyzer(executor) { imageProxy ->
                                                if (!isProcessing) {
                                                    val rawText = decodeQrFromImage(imageProxy, reader)
                                                    if (rawText != null) {
                                                        val payload = PairingPayload.fromJson(rawText)
                                                        if (payload != null) {
                                                            if (payload.isExpired) {
                                                                errorMessage = "Scanned QR code has expired!"
                                                            } else {
                                                                isProcessing = true
                                                                onPayloadScanned(payload)
                                                            }
                                                        } else {
                                                            errorMessage = "Invalid or unsupported LocalDrop QR code"
                                                        }
                                                    }
                                                }
                                                imageProxy.close()
                                            }

                                            cameraProvider.unbindAll()
                                            cameraProvider.bindToLifecycle(
                                                lifecycleOwner,
                                                CameraSelector.DEFAULT_BACK_CAMERA,
                                                preview,
                                                imageAnalysis
                                            )
                                        } catch (e: Exception) {
                                            Log.e("ScanQrDialog", "Camera init failed", e)
                                        }
                                    }, ContextCompat.getMainExecutor(ctx))

                                    previewView
                                },
                                modifier = Modifier.fillMaxSize()
                            )

                            // Reticle bounding box overlay
                            Box(
                                modifier = Modifier
                                    .size(190.dp)
                                    .border(2.dp, CyanAccent, RoundedCornerShape(14.dp))
                            )
                        }
                    } else {
                        // Permission Denied View
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                imageVector = Icons.Default.CameraAlt,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(48.dp)
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = "Camera permission needed to scan QR codes.",
                                style = MaterialTheme.typography.bodyMedium,
                                textAlign = TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(14.dp))
                            Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) {
                                Text("Grant Permission")
                            }
                        }
                    }
                } else {
                    // Manual or Test Payload Entry
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp)
                    ) {
                        Text(
                            text = "Paste Pairing Payload JSON",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        OutlinedTextField(
                            value = manualInputText,
                            onValueChange = {
                                manualInputText = it
                                errorMessage = null
                            },
                            placeholder = { Text("{\"protocolVersion\":\"v1\",...}") },
                            minLines = 4,
                            maxLines = 6,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("manual_qr_input")
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        // Quick Test Button for developer emulator testing
                        OutlinedButton(
                            onClick = {
                                val testPayload = PairingPayload(
                                    protocolVersion = "v1",
                                    sessionId = java.util.UUID.randomUUID().toString(),
                                    pairingToken = "test_token_847194",
                                    deviceId = "tauri-desktop-${java.util.UUID.randomUUID().toString().take(4)}",
                                    deviceName = "Tauri Studio PC",
                                    platform = "windows",
                                    ipAddress = "192.168.1.142",
                                    port = 53317,
                                    expiresAt = System.currentTimeMillis() + 120_000L
                                )
                                manualInputText = testPayload.toJson()
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("load_test_qr_button")
                        ) {
                            Text("Insert Test Desktop Peer QR")
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        Button(
                            onClick = {
                                val payload = PairingPayload.fromJson(manualInputText)
                                if (payload == null) {
                                    errorMessage = "Invalid JSON or unsupported protocol version."
                                } else if (payload.isExpired) {
                                    errorMessage = "This pairing token has expired."
                                } else {
                                    onPayloadScanned(payload)
                                }
                            },
                            enabled = manualInputText.isNotBlank(),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("validate_manual_qr_button")
                        ) {
                            Text("Validate & Pair")
                        }
                    }
                }

                // Error Message Display
                if (errorMessage != null) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f))
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.ErrorOutline,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = errorMessage ?: "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }
    }
}

private fun decodeQrFromImage(image: ImageProxy, reader: MultiFormatReader): String? {
    return try {
        val plane = image.planes[0]
        val buffer: ByteBuffer = plane.buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)

        val source = PlanarYUVLuminanceSource(
            bytes,
            image.width,
            image.height,
            0,
            0,
            image.width,
            image.height,
            false
        )
        val binaryBitmap = BinaryBitmap(HybridBinarizer(source))
        val result = reader.decodeWithState(binaryBitmap)
        reader.reset()
        result.text
    } catch (_: Exception) {
        null
    }
}
