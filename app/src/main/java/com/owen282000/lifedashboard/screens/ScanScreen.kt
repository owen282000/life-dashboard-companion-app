package com.owen282000.lifedashboard.screens

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.util.Log
import android.util.Size
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.owen282000.lifedashboard.PairingLink
import com.owen282000.lifedashboard.PairingLinks
import com.owen282000.lifedashboard.PairingParse
import com.owen282000.lifedashboard.R
import com.owen282000.lifedashboard.ui.ld.LDButton
import com.owen282000.lifedashboard.ui.ld.LDButtonKind
import com.owen282000.lifedashboard.ui.ld.LDGap
import com.owen282000.lifedashboard.ui.ld.LDIcons
import com.owen282000.lifedashboard.ui.ld.LDNote
import com.owen282000.lifedashboard.ui.ld.LDScreen
import com.owen282000.lifedashboard.ui.ld.LDTheme
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The camera, looking for a pairing code. An overlay over the tabs and the wizard, owned by
 * MainActivity.
 *
 * Opened only when the user taps scan, and closed as soon as a code is read: the link
 * goes to the confirmation, which is where anything is actually decided. A code
 * that is not ours says so and scanning continues, because the user is probably pointing
 * at the wrong thing rather than at a broken code.
 */
@Composable
fun ScanScreen(
    onScanned: (PairingLink) -> Unit,
    onClose: () -> Unit
) {
    // Over the wizard and the shell, which have their own Back: this one goes first.
    BackHandler(onBack = onClose)
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

    BackHandler(onBack = onClose)

    val running = hasCamera && !cameraFailed && granted
    val denied = hasCamera && !cameraFailed && !granted && asked
    val message = when {
        !hasCamera -> stringResource(R.string.scan_no_camera)
        cameraFailed -> stringResource(R.string.scan_camera_failed)
        running -> stringResource(if (notACode) R.string.scan_not_a_code else R.string.scan_hint)
        denied -> stringResource(R.string.scan_permission_needed)
        // Between opening and the user answering the system dialog.
        else -> null
    }

    // A Surface, which takes the touches and hides what is under it from TalkBack.
    Surface(modifier = Modifier.fillMaxSize(), color = LDTheme.colors.ground) {
        LDScreen(title = stringResource(R.string.webhook_scan_button), onBack = onClose) {
            LDGap(90.dp)
            ScanViewfinder(
                running = running,
                waiting = !running && hasCamera && !cameraFailed && !denied,
                onText = { text ->
                    // Only a usable link closes the scanner. An unreadable one, and
                    // anything that is not ours, leaves the camera running: the user
                    // is probably aiming at the wrong code rather than a broken one.
                    val parsed = PairingLinks.parse(text)
                    if (parsed is PairingParse.Ok) onScanned(parsed.link) else notACode = true
                },
                onFailed = { cameraFailed = true }
            )
            if (message != null) {
                LDNote(
                    message,
                    Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    color = LDTheme.colors.text,
                    top = 28.dp
                )
            }
            LDGap(20.dp)
            if (denied) {
                LDButton(stringResource(R.string.scan_permission_open_settings)) { openAppSettings(context) }
                LDGap(8.dp)
            }
            LDButton(stringResource(R.string.scan_close), kind = LDButtonKind.Ghost, onClick = onClose)
        }
    }
}

/**
 * The outline around the area the code should fill, with the camera only inside it, so the text
 * below stays on the ground.
 *
 * The outline is the whole point rather than decoration. Measured against softened
 * frames: a code covering a quarter of the viewfinder is unreadable at any resolution,
 * one covering most of it survives several pixels of camera softness. Nothing in the app
 * can make up for standing too far away, so the frame asks the user to come closer.
 */
@Composable
private fun ScanViewfinder(running: Boolean, waiting: Boolean, onText: (String) -> Unit, onFailed: () -> Unit) {
    val colors = LDTheme.colors
    val shape = RoundedCornerShape(28.dp)
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .size(270.dp)
                .clip(shape)
                .border(3.dp, if (running || waiting) colors.accent else colors.ghostBorder, shape)
                .clearAndSetSemantics { },
            contentAlignment = Alignment.Center
        ) {
            when {
                running -> CameraPreview(onText = onText, onFailed = onFailed)
                waiting -> CircularProgressIndicator(Modifier.size(24.dp), color = colors.accent, strokeWidth = 2.dp)
                else -> Icon(LDIcons.qr, contentDescription = null, tint = colors.muted, modifier = Modifier.size(48.dp))
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
    // A TextureView: the default SurfaceView draws past the rounded corners of the frame.
    val previewView = remember { PreviewView(context).apply { implementationMode = PreviewView.ImplementationMode.COMPATIBLE } }
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
                    // CameraX analyses at 640x480 unless asked. Measured against a
                    // softened frame, that reads a pairing code only when the code fills
                    // most of the viewfinder; 960p roughly doubles the softness it
                    // tolerates at every size. How much of the frame the code fills
                    // matters more than either, which is what the frame outline and the
                    // zoom below are for.
                    .setResolutionSelector(
                        ResolutionSelector.Builder()
                            .setResolutionStrategy(
                                ResolutionStrategy(
                                    Size(1280, 960),
                                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                                )
                            )
                            .build()
                    )
                    .build()
                    .also { it.setAnalyzer(executor, QrAnalyzer { text -> latestOnText(text) }) }

                cameraProvider.unbindAll()
                val camera = cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analysis
                )

                // Keep the middle of the frame sharp. Autofocus otherwise settles on
                // whatever has the most contrast, which next to a code on a screen is
                // usually the text under it, and a soft code is an unreadable one.
                val centre = previewView.meteringPointFactory.createPoint(0.5f, 0.5f)
                camera.cameraControl.startFocusAndMetering(
                    FocusMeteringAction.Builder(centre, FocusMeteringAction.FLAG_AF)
                        .setAutoCancelDuration(3, TimeUnit.SECONDS)
                        .build()
                )

                // A modest zoom, so a code held at a comfortable distance fills more of
                // the frame. Measured: how much of the frame the code covers is the one
                // thing that really buys blur tolerance, far more than resolution.
                val maxZoom = camera.cameraInfo.zoomState.value?.maxZoomRatio ?: 1f
                if (maxZoom > 1f) {
                    camera.cameraControl.setZoomRatio(minOf(1.5f, maxZoom))
                }
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
