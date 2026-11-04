package com.owen282000.lifedashboard.screens.ld

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.owen282000.lifedashboard.DataExportCsv
import com.owen282000.lifedashboard.ExportManager
import com.owen282000.lifedashboard.R
import com.owen282000.lifedashboard.screens.exportFileName
import com.owen282000.lifedashboard.ui.ld.LDCard
import com.owen282000.lifedashboard.ui.ld.LDGap
import com.owen282000.lifedashboard.ui.ld.LDHeading
import com.owen282000.lifedashboard.ui.ld.LDNote
import com.owen282000.lifedashboard.ui.ld.LDNoteSize
import com.owen282000.lifedashboard.ui.ld.LDRow
import com.owen282000.lifedashboard.ui.ld.LDRowSpinner
import com.owen282000.lifedashboard.ui.ld.LDScreen
import com.owen282000.lifedashboard.ui.ld.LDSection
import com.owen282000.lifedashboard.ui.ld.LDTheme
import com.owen282000.lifedashboard.ui.ld.ldStaggerRise
import com.owen282000.lifedashboard.ui.ld.rememberLDDraft
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private enum class ExportFormat(val extension: String, val mimeType: String) {
    Csv("csv", "text/csv"),
    Json("json", "application/json")
}

/** What differs between the sections' exports, so the page itself does not branch on the section. */
private class SectionExport(
    val json: String?,
    val isExporting: Boolean,
    val canExport: Boolean,
    val filePrefix: String,
    val start: () -> Unit,
    val finished: () -> Unit
)

@Composable
private fun sectionExport(section: LDSection): SectionExport = when (section) {
    LDSection.Health -> {
        val viewModel = ldHealthViewModel()
        val state by viewModel.state.collectAsStateWithLifecycle()
        SectionExport(state.exportJson, state.isExporting, state.saved.enabledTypes.isNotEmpty(), "health_data", viewModel::export, viewModel::dismissExport)
    }
    LDSection.ScreenTime -> {
        val viewModel = ldScreenTimeViewModel()
        val state by viewModel.state.collectAsStateWithLifecycle()
        SectionExport(state.exportJson, state.isExporting, state.hasUsageAccess, "screen_time", viewModel::export, viewModel::dismissExport)
    }
}

/** A file with what a sync would send, as stored now, for the share sheet. A failure to read comes as the view model's toast. */
@Composable
fun ExportDataScreen(section: LDSection) {
    val export = sectionExport(section)
    val context = LocalContext.current
    val resources = LocalResources.current
    // The draft goes with the page, so an export that arrives after the page was left is not shared on the next visit.
    var format by rememberLDDraft<ExportFormat?> { null }
    var failure by rememberLDDraft<String?> { null }

    LaunchedEffect(export.json) {
        val json = export.json ?: return@LaunchedEffect
        val chosen = format
        if (chosen != null) {
            try {
                val content = withContext(Dispatchers.Default) {
                    if (chosen == ExportFormat.Csv) DataExportCsv.fromPayload(json) else json
                }
                withContext(Dispatchers.IO) {
                    ExportManager(context).shareFile(
                        content,
                        exportFileName(export.filePrefix, chosen.extension),
                        chosen.mimeType,
                        resources.getString(R.string.ld_title_export_data)
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failure = resources.getString(R.string.sync_export_failed_with_reason, e.message.orEmpty())
            }
        }
        format = null
        export.finished()
    }

    val row: @Composable (String, ExportFormat) -> Unit = { title, rowFormat ->
        val busy = export.isExporting && format == rowFormat
        LDRow(
            title,
            enabled = export.canExport && (!export.isExporting || busy),
            onClick = {
                failure = null
                format = rowFormat
                export.start()
            },
            trailing = if (busy) {
                { LDRowSpinner() }
            } else {
                null
            }
        )
    }

    LDScreen(title = stringResource(R.string.ld_title_more)) {
        LDHeading(stringResource(R.string.ld_export_heading))
        LDNote(
            stringResource(if (section == LDSection.Health) R.string.ld_export_intro_health else R.string.ld_export_intro_screen_time),
            top = 6.dp
        )
        LDGap(12.dp)
        LDCard(Modifier.ldStaggerRise(0)) {
            row(stringResource(R.string.ld_export_csv), ExportFormat.Csv)
            row(stringResource(R.string.ld_export_json), ExportFormat.Json)
        }
        LDNote(
            when {
                export.canExport -> stringResource(R.string.ld_export_opens_share)
                section == LDSection.Health -> stringResource(R.string.ld_export_no_types)
                else -> stringResource(R.string.screentime_access_needed)
            },
            size = LDNoteSize.Small
        )
        failure?.let {
            LDNote(
                it,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                color = LDTheme.colors.warning,
                size = LDNoteSize.Small
            )
        }
    }
}
