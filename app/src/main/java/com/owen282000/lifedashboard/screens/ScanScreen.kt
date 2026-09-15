package com.owen282000.lifedashboard.screens

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.owen282000.lifedashboard.PairingLink
import com.owen282000.lifedashboard.PairingLinks
import com.owen282000.lifedashboard.PairingParse
import com.owen282000.lifedashboard.R
import java.util.concurrent.Executors

/**
 * The camera, looking for a pairing code.
 *
 * Opened only when the user taps scan, and closed as soon as a code is read: the link
 * goes to the confirmation dialog, which is where anything is actually decided. A code
 * that is not ours says so and scanning continues, because the user is probably pointing
 * at the wrong thing rather than at a broken code.
 */
@Composable
fun ScanScreen(
    onScanned: (PairingLink) -> Unit,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val hasCamera = remember {
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)
    }
    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    var asked by remember { mutableStateOf(false) }
    var notACode by remember { mutableStateOf(false) }
    var cameraFailed by remember { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { allowed ->
        granted = allowed
        asked = true
    }

    // Ask once, on opening. A denial is answered with an explanation and a way out, never
    // with the dialog again: Android stops showing it after the second refusal anyway.
    LaunchedEffect(Unit) {
        if (hasCamera && !granted) launcher.launch(Manifest.permission.CAMERA)
    }

    Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
        Box(modifier = Modifier.fillMaxSize()) {
            when {
                !hasCamera -> Explanation(
                    message = stringResource(R.string.scan_no_camera),
                    onClose = onClose
                )

                cameraFailed -> Explanation(
                    message = stringResource(R.string.scan_camera_failed),
                    onClose = onClose
                )

                granted -> CameraPreview(
                    onText = { text ->
                        // Only a usable link closes the scanner. An unreadable one, and
                        // anything that is not ours, leaves the camera running: the user
                        // is probably aiming at the wrong code rather than a broken one.
                        val parsed = PairingLinks.parse(text)
                        if (parsed is PairingParse.Ok) onScanned(parsed.link) else notACode = true
                    },
                    onFailed = { cameraFailed = true }
                )

                asked -> PermissionRefused(onClose = onClose)

                // Between opening and the user answering the system dialog.
                else -> Unit
            }

            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Only while the camera is running: every other state is an
                // Explanation, which carries its own hint and Close button.
                if (granted && hasCamera && !cameraFailed) {
                    Text(
                        stringResource(
                            if (notACode) R.string.scan_not_a_code else R.string.scan_hint
                        ),
                        color = Color.White,
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .background(
                                Color.Black.copy(alpha = 0.6f),
                                MaterialTheme.shapes.medium
                            )
                            .padding(horizontal = 16.dp, vertical = 12.dp)
                    )
                    TextButton(onClick = onClose) {
                        Text(stringResource(R.string.scan_close), color = Color.White)
                    }
                }
            }
        }
    }
}

/** The preview, with every frame offered to the decoder. */
@Composable
private fun CameraPreview(onText: (String) -> Unit, onFailed: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val latestOnText by rememberUpdatedState(onText)
    val previewView = remember { PreviewView(context) }
    val executor = remember { Executors.newSingleThreadExecutor() }

    AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())

    DisposableEffect(lifecycleOwner) {
        val provider = ProcessCameraProvider.getInstance(context)
        provider.addListener({
            runCatching {
                val cameraProvider = provider.get()
                val preview = Preview.Builder().build().also {
                    it.surfaceProvider = previewView.surfaceProvider
                }
                val analysis = ImageAnalysis.Builder()
                    // Decode the newest frame and drop the backlog: a stale frame is a
                    // code the camera is no longer pointing at.
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also { it.setAnalyzer(executor, QrAnalyzer { text -> latestOnText(text) }) }

                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analysis
                )
            }.onFailure {
                Log.w(TAG, "Could not start the camera", it)
                onFailed()
            }
        }, ContextCompat.getMainExecutor(context))

        onDispose {
            runCatching { provider.get().unbindAll() }
            executor.shutdown()
        }
    }
}

@Composable
private fun PermissionRefused(onClose: () -> Unit) {
    val context = LocalContext.current
    Explanation(
        message = stringResource(R.string.scan_permission_needed),
        onClose = onClose,
        action = stringResource(R.string.scan_permission_open_settings) to { openAppSettings(context) }
    )
}

@Composable
private fun Explanation(
    message: String,
    onClose: () -> Unit,
    action: Pair<String, () -> Unit>? = null
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            stringResource(R.string.scan_title),
            color = Color.White,
            style = MaterialTheme.typography.titleMedium
        )
        Text(
            message,
            color = Color.White,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center
        )
        action?.let { (label, onAction) ->
            Button(onClick = onAction) { Text(label) }
        }
        TextButton(onClick = onClose) {
            Text(stringResource(R.string.scan_close), color = Color.White)
        }
    }
}

private fun openAppSettings(context: Context) {
    runCatching {
        context.startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", context.packageName, null)
            )
        )
    }
}

private const val TAG = "ScanScreen"
