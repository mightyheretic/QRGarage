package com.example.qrgarage.ui.scan

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FlashlightOff
import androidx.compose.material.icons.filled.FlashlightOn
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.NoPhotography
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.qrgarage.data.repo.displayName
import com.example.qrgarage.ui.components.BigButton
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executors

@Composable
fun ScanScreen(
    onResolved: (locationId: String, pickMode: Boolean) -> Unit,
    onClose: () -> Unit,
    vm: ScanViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val state by vm.state.collectAsStateWithLifecycle()
    var hasPermission by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    var denied by rememberSaveable { mutableStateOf(false) }
    var torch by rememberSaveable { mutableStateOf(false) }
    var hasFlash by remember { mutableStateOf(false) }
    var cameraError by remember { mutableStateOf<String?>(null) }
    var manualEntry by rememberSaveable { mutableStateOf(false) }

    // NFR-3: the camera permission is requested when the user first opens the scanner.
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasPermission = granted
        denied = !granted
    }
    LaunchedEffect(Unit) { if (!hasPermission && !denied) permission.launch(Manifest.permission.CAMERA) }
    LaunchedEffect(Unit) { vm.resolved.collect { onResolved(it, vm.pickMode) } }

    val cameraActive = hasPermission && cameraError == null
    Box(Modifier.fillMaxSize().background(if (cameraActive) Color.Black else MaterialTheme.colorScheme.surface)) {
        when {
            cameraActive -> {
                CameraPreview(
                    torchOn = torch,
                    onCode = vm::onCode,
                    onFlashAvailable = { hasFlash = it },
                    onError = { cameraError = it },
                )
                // Aiming reticle.
                Box(
                    Modifier.align(Alignment.Center).size(260.dp)
                        .border(BorderStroke(4.dp, Color.White.copy(alpha = 0.85f)), RoundedCornerShape(28.dp))
                )
            }
            else -> Column(
                Modifier.align(Alignment.Center).padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Icon(Icons.Outlined.NoPhotography, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    cameraError ?: "QR Garage needs the camera to read location labels. You can also type the code printed under the label.",
                    style = MaterialTheme.typography.bodyLarge,
                )
                if (cameraError == null) {
                    BigButton("Allow camera", null, onClick = {
                        if (denied) {
                            context.startActivity(
                                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                            )
                        } else permission.launch(Manifest.permission.CAMERA)
                    }, modifier = Modifier.fillMaxWidth())
                }
            }
        }

        Row(
            Modifier.fillMaxWidth().safeDrawingPadding().padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            FilledTonalIconButton(onClick = onClose, modifier = Modifier.size(64.dp)) {
                Icon(Icons.Filled.Close, contentDescription = "Close scanner", modifier = Modifier.size(32.dp))
            }
            if (hasPermission && hasFlash) {
                FilledTonalIconButton(onClick = { torch = !torch }, modifier = Modifier.size(64.dp)) {
                    Icon(
                        if (torch) Icons.Filled.FlashlightOn else Icons.Filled.FlashlightOff,
                        contentDescription = if (torch) "Turn flashlight off" else "Turn flashlight on",
                        modifier = Modifier.size(32.dp),
                    )
                }
            }
        }

        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().safeDrawingPadding().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when {
                state.busy -> Card(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(28.dp))
                        Text("Looking up label…", Modifier.padding(start = 16.dp), style = MaterialTheme.typography.bodyLarge)
                    }
                }
                state.error != null -> MessageCard(
                    text = state.error!!, actionLabel = "Scan again", onAction = vm::dismiss, isError = true,
                )
                state.trashed != null -> MessageCard(
                    text = "“${state.trashed!!.displayName}” is in the trash. Restore it to use this label?",
                    actionLabel = "Restore", onAction = vm::restoreTrashed,
                    secondaryLabel = "Cancel", onSecondary = vm::dismiss,
                )
                cameraActive -> Text(
                    if (vm.pickMode) "Scan the label of the destination" else "Point the camera at a location label",
                    color = Color.White, style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )
            }
            BigButton(
                text = "Enter code",
                icon = Icons.Filled.Keyboard,
                onClick = { manualEntry = true },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.filledTonalButtonColors(),
            )
        }
    }

    if (manualEntry) {
        ManualEntryDialog(
            onSubmit = { vm.dismiss(); vm.onCode(it); manualEntry = false },
            onDismiss = { manualEntry = false },
        )
    }
}

@Composable
private fun MessageCard(
    text: String,
    actionLabel: String,
    onAction: () -> Unit,
    isError: Boolean = false,
    secondaryLabel: String? = null,
    onSecondary: (() -> Unit)? = null,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isError) Icon(Icons.Outlined.ErrorOutline, null, tint = MaterialTheme.colorScheme.error)
                Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = if (isError) 12.dp else 0.dp))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                if (secondaryLabel != null && onSecondary != null) {
                    TextButton(onClick = onSecondary, modifier = Modifier.heightIn(min = 56.dp)) { Text(secondaryLabel) }
                }
                TextButton(onClick = onAction, modifier = Modifier.heightIn(min = 56.dp)) { Text(actionLabel) }
            }
        }
    }
}

/** LOC-4: fallback for damaged labels: type the short code (or paste a full payload). */
@Composable
private fun ManualEntryDialog(onSubmit: (String) -> Unit, onDismiss: () -> Unit) {
    var code by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Enter location code") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Type the 5-character code printed under the QR code.")
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it.trim() },
                    singleLine = true,
                    placeholder = { Text("e.g. 7KX3M") },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { if (code.isNotBlank()) onSubmit(code) }),
                    textStyle = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSubmit(code) }, enabled = code.isNotBlank(), modifier = Modifier.heightIn(min = 48.dp)) { Text("Open") }
        },
        dismissButton = { TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text("Cancel") } },
    )
}

@OptIn(ExperimentalGetImage::class)
@Composable
private fun CameraPreview(
    torchOn: Boolean,
    onCode: (String) -> Unit,
    onFlashAvailable: (Boolean) -> Unit,
    onError: (String) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnCode by rememberUpdatedState(onCode)
    val previewView = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }
    var camera by remember { mutableStateOf<Camera?>(null) }

    DisposableEffect(lifecycleOwner) {
        val scanner = BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
        )
        val executor = Executors.newSingleThreadExecutor()
        val main = ContextCompat.getMainExecutor(context)
        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener({
            try {
                val provider = providerFuture.get()
                val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(executor) { proxy ->
                    val media = proxy.image
                    if (media == null) {
                        proxy.close()
                        return@setAnalyzer
                    }
                    scanner.process(InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees))
                        .addOnSuccessListener(main) { codes ->
                            codes.firstNotNullOfOrNull { it.rawValue }?.let { currentOnCode(it) }
                        }
                        .addOnCompleteListener { proxy.close() }
                }
                provider.unbindAll()
                val cam = provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                camera = cam
                onFlashAvailable(cam.cameraInfo.hasFlashUnit())
            } catch (e: Exception) {
                Log.e("ScanScreen", "Camera unavailable", e)
                onError("The camera couldn't be started. Enter the code printed under the label instead.")
            }
        }, main)
        onDispose {
            runCatching { providerFuture.get().unbindAll() }
            executor.shutdown()
            scanner.close()
        }
    }
    LaunchedEffect(camera, torchOn) { camera?.cameraControl?.enableTorch(torchOn) }
    AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
}
