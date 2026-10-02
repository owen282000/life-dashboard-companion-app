package com.owen282000.lifedashboard.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.owen282000.lifedashboard.ExportManager
import com.owen282000.lifedashboard.LogDestination
import com.owen282000.lifedashboard.LogDirection
import com.owen282000.lifedashboard.LogType
import com.owen282000.lifedashboard.PayloadPreview
import com.owen282000.lifedashboard.R
import com.owen282000.lifedashboard.ReceiveLogLine
import com.owen282000.lifedashboard.WebhookLog
import com.owen282000.lifedashboard.WriteBackType
import com.owen282000.lifedashboard.appPreferences
import com.owen282000.lifedashboard.ui.theme.LogsPrimary
import com.owen282000.lifedashboard.ui.theme.Success
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Every delivery the app made, webhook and MQTT alike, newest first. The banner carries the
 * one setting this tab owns (whether payloads are kept whole), the stat card the totals, and
 * each row folds out to the URL, status, note and payload.
 */
@Composable
fun LogsScreen() {
    val context = LocalContext.current
    val preferencesManager = remember { context.appPreferences() }
    val accent = LogsPrimary

    var selectedFilter by remember { mutableStateOf<LogType?>(null) }
    var allLogs by remember { mutableStateOf(preferencesManager.getWebhookLogs(null)) }
    var showExportDialog by remember { mutableStateOf(false) }
    var keepFullPayloads by remember { mutableStateOf(preferencesManager.keepFullPayloads()) }

    val logs = remember(allLogs, selectedFilter) {
        selectedFilter?.let { filter -> allLogs.filter { it.logType == filter.name } } ?: allLogs
    }
    val filters = listOf<LogType?>(null, LogType.HEALTH_CONNECT, LogType.SCREEN_TIME)

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            // Payloads are raw health data and are the bulk of what the log store keeps, so
            // they are truncated unless the user wants them whole for debugging.
            StatusBanner(
                accent = accent,
                title = stringResource(R.string.logs_keep_full_payloads),
                subtitle = if (keepFullPayloads) stringResource(R.string.logs_keep_full_payloads_on)
                else stringResource(R.string.logs_keep_full_payloads_off)
            ) {
                Switch(
                    checked = keepFullPayloads,
                    onCheckedChange = {
                        keepFullPayloads = it
                        preferencesManager.setKeepFullPayloads(it)
                    },
                    colors = bannerSwitchColors(accent)
                )
            }
        }
        item { SyncStatsCard(allLogs) }
        item {
            SegmentedFilter(
                options = listOf(
                    stringResource(R.string.logs_filter_all),
                    stringResource(R.string.logs_filter_health),
                    stringResource(R.string.logs_filter_screen_time)
                ),
                selectedIndex = filters.indexOf(selectedFilter),
                onSelect = { selectedFilter = filters[it] }
            )
        }
        if (logs.isNotEmpty()) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ActionTile(Icons.Outlined.Share, stringResource(R.string.logs_export_logs), accent, onClick = { showExportDialog = true })
                    ActionTile(Icons.Outlined.Delete, stringResource(R.string.logs_clear_logs), MaterialTheme.colorScheme.error, onClick = {
                        preferencesManager.clearWebhookLogs(selectedFilter)
                        allLogs = preferencesManager.getWebhookLogs(null)
                    })
                }
            }
            item {
                PremiumCard {
                    logs.forEachIndexed { index, log ->
                        LogRow(log, accent)
                        if (index < logs.lastIndex) GroupDivider()
                    }
                }
            }
        } else {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 48.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        stringResource(R.string.logs_empty),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        item { Spacer(modifier = Modifier.height(72.dp)) }
    }

    if (showExportDialog) {
        AlertDialog(
            onDismissRequest = { showExportDialog = false },
            title = { Text(stringResource(R.string.logs_export_title)) },
            text = {
                Text(
                    pluralStringResource(R.plurals.logs_export_dialog_description, logs.size, logs.size),
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showExportDialog = false
                    val exportManager = ExportManager(context)
                    exportManager.shareFile(exportManager.exportAsJson(logs), exportFileName("logs", "json"), "application/json")
                }) { Text(stringResource(R.string.common_json), color = accent) }
            },
            dismissButton = {
                TextButton(onClick = {
                    showExportDialog = false
                    val exportManager = ExportManager(context)
                    exportManager.shareFile(exportManager.exportAsCsv(logs), exportFileName("logs", "csv"), "text/csv")
                }) { Text(stringResource(R.string.common_csv), color = accent) }
            }
        )
    }
}

@Composable
private fun SyncStatsCard(logs: List<WebhookLog>) {
    // What went in from Home Assistant is not a delivery, so it stays out of every figure on
    // this card; its own count is in the Receive row.
    val outgoing = logs.filter { it.direction != LogDirection.IN.name }
    val total = outgoing.size
    val successful = outgoing.count { it.success }
    val successRate = if (total > 0) (successful * 100) / total else 0
    val totalRecords = outgoing.filter { it.success }.sumOf { it.recordCount ?: 0 }
    val lastSuccess = outgoing.filter { it.success }.maxByOrNull { it.timestamp }
    val healthSyncs = outgoing.count { it.logType == LogType.HEALTH_CONNECT.name }
    val healthSuccess = outgoing.count { it.logType == LogType.HEALTH_CONNECT.name && it.success }
    val screenSyncs = logs.count { it.logType == LogType.SCREEN_TIME.name }
    val screenSuccess = logs.count { it.logType == LogType.SCREEN_TIME.name && it.success }

    PremiumCard {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                StatColumn(stringResource(R.string.logs_stat_success), stringResource(R.string.logs_stat_success_rate, successRate), Success)
                StatColumn(stringResource(R.string.logs_stat_total), "$total", MaterialTheme.colorScheme.onSurface)
                StatColumn(stringResource(R.string.logs_stat_records), "$totalRecords", MaterialTheme.colorScheme.onSurface, end = true)
            }
            Text(
                stringResource(R.string.main_title_health_connect) + " " + stringResource(R.string.logs_type_ratio, healthSuccess, healthSyncs) +
                    " · " + stringResource(R.string.main_title_screen_time) + " " + stringResource(R.string.logs_type_ratio, screenSuccess, screenSyncs) +
                    (lastSuccess?.let { " · " + stringResource(R.string.logs_last_success, formatTimestamp(it.timestamp)) } ?: ""),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun StatColumn(label: String, value: String, color: androidx.compose.ui.graphics.Color, end: Boolean = false) {
    Column(horizontalAlignment = if (end) Alignment.End else Alignment.Start) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = color, maxLines = 1)
    }
}

@Composable
private fun LogRow(log: WebhookLog, accent: androidx.compose.ui.graphics.Color) {
    var expanded by remember(log.id) { mutableStateOf(false) }
    val isMqtt = log.destination == LogDestination.MQTT.name
    val isIncoming = log.direction == LogDirection.IN.name
    val source = when {
        isIncoming -> stringResource(R.string.logs_row_receive_title)
        log.logType == LogType.HEALTH_CONNECT.name -> stringResource(R.string.logs_filter_health)
        log.logType == LogType.SCREEN_TIME.name -> stringResource(R.string.logs_filter_screen_time)
        else -> stringResource(R.string.logs_filter_unknown)
    }
    val time = formatTimestamp(log.timestamp)
    val subtitle = when {
        log.recordCount == null -> time
        isIncoming -> pluralStringResource(R.plurals.logs_row_written, log.recordCount, time, log.recordCount)
        isMqtt -> stringResource(R.string.logs_row_sensors, time, log.recordCount)
        else -> stringResource(R.string.logs_row_records, time, log.recordCount)
    }
    // A Receive round that wrote nothing is neither a success worth green nor a failure.
    val skipped = isIncoming && log.success && (log.recordCount ?: 0) == 0
    val statusColor = when {
        !log.success -> MaterialTheme.colorScheme.error
        skipped -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> Success
    }

    Column {
        SettingRow(
            icon = when {
                isIncoming -> Icons.Outlined.Download
                isMqtt -> Icons.Outlined.Home
                else -> Icons.Outlined.Link
            },
            accent = accent,
            title = source,
            subtitle = subtitle,
            onClick = { expanded = !expanded }
        ) {
            StatusPill(
                label = when {
                    !log.success -> stringResource(R.string.logs_failed)
                    skipped -> stringResource(R.string.logs_skipped)
                    isIncoming -> stringResource(R.string.logs_written)
                    isMqtt -> stringResource(R.string.logs_published)
                    else -> stringResource(R.string.logs_delivered)
                },
                color = statusColor
            )
        }
        if (isIncoming) {
            AnimatedVisibility(visible = expanded, enter = expandVertically(), exit = shrinkVertically()) {
                ReceiveLogDetails(log)
            }
            return
        }
        AnimatedVisibility(visible = expanded, enter = expandVertically(), exit = shrinkVertically()) {
            Column(
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(log.url, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    if (log.success) stringResource(R.string.logs_status_success, log.statusCode?.toString() ?: stringResource(R.string.logs_ok))
                    else stringResource(R.string.logs_status_failure, log.statusCode?.toString() ?: stringResource(R.string.logs_error)),
                    style = MaterialTheme.typography.bodySmall,
                    color = statusColor
                )
                if (log.dataType != null && log.recordCount != null && !isMqtt) {
                    Text(
                        stringResource(R.string.logs_record_count, log.dataType, log.recordCount),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                log.note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                if (!log.success && log.errorMessage != null) {
                    Text(log.errorMessage, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                if (log.rawPayload != null) {
                    Text(
                        stringResource(R.string.logs_payload),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                    // Formatted off the main thread and capped (P2-11): a payload kept in full can
                    // be a few hundred KB, too much to parse on a tap or to lay out in one Text.
                    val preview by produceState<PayloadPreview?>(null, log.id) {
                        value = withContext(Dispatchers.Default) { PayloadPreview.of(PayloadPreview.pretty(log.rawPayload)) }
                    }
                    preview?.let { shown ->
                        val description = stringResource(R.string.payload_preview_a11y, formatCount(shown.totalLength))
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .horizontalScroll(rememberScrollState())
                                    .padding(10.dp)
                            ) {
                                Text(
                                    shown.text,
                                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 10.sp, lineHeight = 14.sp),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    // Replaces the text's own semantics: a plain contentDescription
                                    // would sit next to the whole text, not instead of it.
                                    modifier = Modifier.clearAndSetSemantics { contentDescription = description }
                                )
                            }
                        }
                        val notes = buildList {
                            if (shown.cutForDisplay) {
                                add(stringResource(R.string.payload_preview_shown_part, formatCount(PayloadPreview.MAX_CHARS), formatCount(shown.totalLength)))
                            }
                            // A payload the store cut short is not in the JSON export either.
                            if (shown.cutInStorage) {
                                add(stringResource(R.string.logs_payload_stored_part))
                            } else if (shown.cutForDisplay) {
                                add(stringResource(R.string.logs_payload_full_in_export))
                            }
                        }
                        if (notes.isNotEmpty()) {
                            Text(
                                notes.joinToString(" "),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * The readings of one Receive round: entity, type, value and unit (only when full payloads
 * are kept), measurement time, outcome and reason. Never the secret or the signature; the
 * line for a rejected response names the reason and nothing else.
 */
@Composable
private fun ReceiveLogDetails(log: WebhookLog) {
    val lines = remember(log.id) { ReceiveLogLine.decode(log.rawPayload) }
    Column(
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(log.url, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (!log.success && log.errorMessage != null) {
            Text(log.errorMessage, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        lines?.forEach { line ->
            val typeName = line.type?.let { key -> WriteBackType.fromKey(key)?.dataType?.displayName ?: key }
                ?: stringResource(R.string.logs_filter_unknown)
            val value = line.value?.let { v ->
                val number = if (line.diastolic != null) "${formatValue(v)}/${formatValue(line.diastolic)}" else formatValue(v)
                "$number ${line.unit.orEmpty()}".trim()
            }
            val measured = line.time?.let { runCatching { formatTimestamp(java.time.Instant.parse(it).toEpochMilli()) }.getOrNull() }
            val outcome = when (line.outcome) {
                ReceiveLogLine.WRITTEN -> stringResource(R.string.logs_written)
                ReceiveLogLine.SKIPPED -> stringResource(R.string.logs_skipped)
                else -> stringResource(R.string.logs_failed)
            }
            val outcomeWithReason = line.reason?.let { stringResource(R.string.logs_receive_outcome_reason, outcome, it) } ?: outcome
            Column {
                Text(
                    listOfNotNull(line.entity, typeName, value).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    listOfNotNull(measured, outcomeWithReason).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (line.outcome == ReceiveLogLine.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private fun formatValue(value: Double): String =
    if (value == Math.rint(value)) value.toLong().toString() else String.format(Locale.getDefault(), "%.2f", value).trimEnd('0').trimEnd('.', ',')

private fun formatTimestamp(timestamp: Long): String {
    val sdf = SimpleDateFormat("MMM dd, HH:mm:ss", Locale.getDefault())
    return sdf.format(Date(timestamp))
}
