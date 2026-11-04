package com.owen282000.lifedashboard.screens.ld

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle
import android.widget.Toast
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.owen282000.lifedashboard.ExportManager
import com.owen282000.lifedashboard.PayloadPreview
import com.owen282000.lifedashboard.R
import com.owen282000.lifedashboard.screens.exportFileName
import com.owen282000.lifedashboard.screens.formatCount
import com.owen282000.lifedashboard.screens.text
import com.owen282000.lifedashboard.ui.ld.LDButton
import com.owen282000.lifedashboard.ui.ld.LDButtonKind
import com.owen282000.lifedashboard.ui.ld.LDButtonSize
import com.owen282000.lifedashboard.ui.ld.LDCodeBlock
import com.owen282000.lifedashboard.ui.ld.LDFooterBar
import com.owen282000.lifedashboard.ui.ld.LDGap
import com.owen282000.lifedashboard.ui.ld.LDNote
import com.owen282000.lifedashboard.ui.ld.LDNoteSize
import com.owen282000.lifedashboard.ui.ld.LDScreen
import com.owen282000.lifedashboard.ui.ld.LDSection
import com.owen282000.lifedashboard.ui.ld.LDTheme
import com.owen282000.lifedashboard.viewmodel.UiMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the page needs of either section. */
private class PreviewSource(
    val data: String?,
    val isLoading: Boolean,
    val problem: UiMessage?,
    /** Why there is nothing to build, when that is known before trying. */
    val blockedBy: String?,
    val filePrefix: String,
    val load: () -> Unit,
    val dismiss: () -> Unit
)

@Composable
private fun previewSource(section: LDSection): PreviewSource = when (section) {
    LDSection.Health -> {
        val viewModel = ldHealthViewModel()
        val state by viewModel.state.collectAsStateWithLifecycle()
        PreviewSource(
            state.previewData,
            state.isPreviewing,
            state.previewProblem,
            stringResource(R.string.ld_preview_no_types).takeIf { state.saved.enabledTypes.isEmpty() },
            "health_data",
            viewModel::preview,
            viewModel::dismissPreview
        )
    }
    LDSection.ScreenTime -> {
        val viewModel = ldScreenTimeViewModel()
        val state by viewModel.state.collectAsStateWithLifecycle()
        PreviewSource(
            state.previewData,
            state.isPreviewing,
            state.previewProblem,
            stringResource(R.string.screentime_permission_not_granted).takeUnless { state.hasUsageAccess },
            "screen_time",
            viewModel::preview,
            viewModel::dismissPreview
        )
    }
}

/**
 * What a sync would send now, as it is stored. The screen shows at most PayloadPreview's 12,000
 * characters; Copy JSON and Share have all of it, Copy as far as the clipboard takes it.
 */
@Composable
fun PayloadPreviewScreen(section: LDSection) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val source = previewSource(section)
    var requested by remember { mutableStateOf(false) }
    var copyRefused by remember { mutableStateOf(false) }
    val blocked = source.blockedBy != null

    LaunchedEffect(blocked) {
        if (!blocked) {
            source.load()
            requested = true
        }
    }
    DisposableEffect(section) {
        onDispose { source.dismiss() }
    }

    val data = source.data
    val copied = stringResource(R.string.payload_copied)
    val shareTitle = stringResource(R.string.payload_share)
    LDScreen(
        title = stringResource(R.string.ld_title_preview),
        footer = {
            Column {
                if (copyRefused) {
                    LDNote(
                        stringResource(R.string.payload_too_large_to_copy),
                        Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        color = LDTheme.colors.warning,
                        size = LDNoteSize.Small
                    )
                    LDGap(8.dp)
                }
                LDFooterBar {
                    val copyLabel = stringResource(R.string.payload_copy_json_a11y)
                    val shareLabel = stringResource(R.string.payload_share_a11y)
                    LDButton(
                        stringResource(R.string.payload_copy_json),
                        Modifier.semantics { contentDescription = copyLabel },
                        kind = LDButtonKind.Ghost,
                        size = LDButtonSize.Medium,
                        enabled = data != null,
                        onClick = {
                            if (data != null) copyRefused = !PayloadPreview.fitsClipboard(data) || !copyPayload(context, data, copied)
                        }
                    )
                    LDButton(
                        stringResource(R.string.payload_share),
                        Modifier.semantics { contentDescription = shareLabel },
                        kind = LDButtonKind.Ghost,
                        size = LDButtonSize.Medium,
                        enabled = data != null,
                        onClick = {
                            // A week of heart rate is a few megabytes: written off the main thread.
                            if (data != null) {
                                scope.launch {
                                    try {
                                        withContext(Dispatchers.IO) {
                                            ExportManager(context).shareFile(data, exportFileName(source.filePrefix, "json"), "application/json", shareTitle)
                                        }
                                    } catch (e: CancellationException) {
                                        throw e
                                    } catch (e: Exception) {
                                        Toast.makeText(context, UiMessage.ExportFailed(e.message).text(resources), Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                        }
                    )
                }
            }
        }
    ) {
        LDNote(stringResource(R.string.ld_preview_intro), top = 4.dp)
        LDGap(10.dp)
        when {
            source.blockedBy != null -> LDNote(source.blockedBy, top = 0.dp)
            data != null -> PayloadText(data)
            source.isLoading || !requested -> Box(Modifier.fillMaxWidth().heightIn(min = 120.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = LDTheme.colors.accent)
            }
            // Nothing wrong: the last sync took everything there was.
            source.problem == UiMessage.NoNewData -> LDNote(stringResource(R.string.ld_preview_nothing_new), top = 0.dp)
            source.problem != null -> LDNote(source.problem.text(resources), color = LDTheme.colors.warning, top = 0.dp)
            else -> LDNote(stringResource(R.string.sync_preview_failed), color = LDTheme.colors.warning, top = 0.dp)
        }
    }
}

@Composable
private fun PayloadText(data: String) {
    val shown = remember(data) { PayloadPreview.of(data) }
    val description = stringResource(R.string.payload_preview_a11y, formatCount(shown.totalLength))
    // Selection and semantics over the whole text is what the cap is there to avoid; Copy moves it.
    LDCodeBlock(shown.text, Modifier.clearAndSetSemantics { contentDescription = description }, wraps = true, selectable = false)
    if (shown.cutForDisplay) {
        LDNote(
            stringResource(R.string.payload_preview_shown_part, formatCount(PayloadPreview.MAX_CHARS), formatCount(shown.totalLength)),
            size = LDNoteSize.Small
        )
        LDNote(
            stringResource(if (PayloadPreview.fitsClipboard(data)) R.string.sync_preview_full_in_copy_share else R.string.sync_preview_full_in_share),
            top = 2.dp,
            size = LDNoteSize.Small
        )
    }
}

/**
 * Health data on the clipboard, flagged sensitive: from Android 13 the clipboard overlay shows dots
 * and the system confirms by itself. False when the clipboard refused the clip, which one too large
 * for the binder makes it do. The key is Android 13's ClipDescription.EXTRA_IS_SENSITIVE: androidx
 * has no compat constant, and older keyboards' clipboard strips may honour it too.
 */
private fun copyPayload(context: Context, data: String, copiedMessage: String): Boolean {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clip = ClipData.newPlainText("payload", data)
    clip.description.extras = PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
    try {
        clipboard.setPrimaryClip(clip)
    } catch (e: RuntimeException) {
        return false
    }
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        Toast.makeText(context, copiedMessage, Toast.LENGTH_SHORT).show()
    }
    return true
}
