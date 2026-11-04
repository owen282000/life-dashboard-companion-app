package com.owen282000.lifedashboard.screens.ld

import android.text.format.Formatter
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.owen282000.lifedashboard.LogType
import com.owen282000.lifedashboard.PayloadPreview
import com.owen282000.lifedashboard.R
import com.owen282000.lifedashboard.ReceiveLogLine
import com.owen282000.lifedashboard.WebhookLog
import com.owen282000.lifedashboard.screens.copySensitive
import com.owen282000.lifedashboard.screens.formatCount
import com.owen282000.lifedashboard.screens.fullPayloadHint
import com.owen282000.lifedashboard.ui.ld.LDButton
import com.owen282000.lifedashboard.ui.ld.LDButtonKind
import com.owen282000.lifedashboard.ui.ld.LDButtonSize
import com.owen282000.lifedashboard.ui.ld.LDCard
import com.owen282000.lifedashboard.ui.ld.LDCodeBlock
import com.owen282000.lifedashboard.ui.ld.LDFooterBar
import com.owen282000.lifedashboard.ui.ld.LDGap
import com.owen282000.lifedashboard.ui.ld.LDHeading
import com.owen282000.lifedashboard.ui.ld.LDKeyValue
import com.owen282000.lifedashboard.ui.ld.LDKeyValueList
import com.owen282000.lifedashboard.ui.ld.LDNote
import com.owen282000.lifedashboard.ui.ld.LDNoteSize
import com.owen282000.lifedashboard.ui.ld.LDScreen
import com.owen282000.lifedashboard.ui.ld.LDSectionLabel
import com.owen282000.lifedashboard.ui.ld.LDTheme
import com.owen282000.lifedashboard.ui.ld.LDType
import com.owen282000.lifedashboard.ui.ld.LocalLDEntryId
import com.owen282000.lifedashboard.ui.ld.LocalLDNavigator
import com.owen282000.lifedashboard.ui.ld.ldKeepingSeparators
import com.owen282000.lifedashboard.ui.ld.ldStaggerRise
import com.owen282000.lifedashboard.viewmodel.LogsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.util.Locale

/** A delivery as it was read: the payload formatted once, off the main thread (P2-11). */
private data class LoadedDelivery(
    val log: WebhookLog,
    val preview: PayloadPreview?,
    val stored: String?,
    val storedBytes: Int,
    val readings: List<ReceiveLogLine>
)

/**
 * One delivery with everything the log stores about it. Duration, response body and attempt count
 * are not stored, and a row cannot be sent again, so the board's fields and Try again are not here.
 * A Receive row has its readings instead of a payload.
 */
@Composable
fun LogDetailScreen(logId: String) {
    val viewModel: LogsViewModel = viewModel()
    val navigator = LocalLDNavigator.current
    val page = LocalLDEntryId.current
    val context = LocalContext.current
    var hasLoaded by remember { mutableStateOf(false) }
    val delivery by produceState<LoadedDelivery?>(null, logId) {
        value = viewModel.log(logId)?.let { log -> withContext(Dispatchers.Default) { load(log) } }
        hasLoaded = true
    }
    var copyRefused by rememberSaveable { mutableStateOf(false) }

    LDScreen(
        title = stringResource(R.string.ld_title_delivery),
        footer = delivery?.stored?.let { stored ->
            {
                Column {
                    if (copyRefused) {
                        LDNote(
                            stringResource(R.string.payload_too_large_to_copy),
                            Modifier.semantics { liveRegion = LiveRegionMode.Polite }.padding(bottom = 8.dp),
                            color = LDTheme.colors.warningText,
                            size = LDNoteSize.Small
                        )
                    }
                    val copied = stringResource(R.string.payload_copied)
                    val copyLabel = stringResource(R.string.payload_copy_json_a11y)
                    val shareLabel = stringResource(R.string.payload_share_a11y)
                    LDFooterBar {
                        LDButton(
                            stringResource(R.string.payload_copy_json),
                            Modifier.semantics { contentDescription = copyLabel },
                            kind = LDButtonKind.Ghost,
                            size = LDButtonSize.Medium
                        ) {
                            copyRefused = !PayloadPreview.fitsClipboard(stored) || !copySensitive(context, "payload", stored, copied)
                        }
                        LDButton(
                            stringResource(R.string.payload_share),
                            Modifier.semantics { contentDescription = shareLabel },
                            kind = LDButtonKind.Ghost,
                            size = LDButtonSize.Medium
                        ) { viewModel.sharePayload(logId) }
                    }
                }
            }
        }
    ) {
        val loaded = delivery
        if (loaded != null) {
            DeliveryContent(loaded) {
                viewModel.delete(setOf(logId))
                navigator.pop(page)
            }
        } else if (hasLoaded) {
            LDHeading(stringResource(R.string.ld_title_delivery))
            LDNote(stringResource(R.string.ld_logs_gone), top = 4.dp)
        }
    }
}

private fun load(log: WebhookLog): LoadedDelivery {
    val payload = log.rawPayload
    if (log.isIncoming || payload == null) {
        return LoadedDelivery(log, null, null, 0, ReceiveLogLine.decode(payload.takeIf { log.isIncoming }).orEmpty())
    }
    // What Copy JSON and Share hand over: the payload as it was stored, without the store's marker.
    val stored = PayloadPreview.stored(payload)
    val preview = PayloadPreview.of(PayloadPreview.pretty(payload))
    return LoadedDelivery(log, preview, stored, stored.toByteArray().size, emptyList())
}

@Composable
private fun DeliveryContent(delivery: LoadedDelivery, onDelete: () -> Unit) {
    val log = delivery.log
    val colors = LDTheme.colors
    val outcome = stringResource(logOutcome(log))
    val formats = rememberLogFormats()
    LDHeading(
        if (log.isIncoming || log.isMqtt || log.statusCode != null) {
            stringResource(R.string.ld_logs_headline, log.code, outcome)
        } else {
            outcome
        }
    )
    deliveryNote(log)?.let { note ->
        LDNote(note, color = if (log.success) colors.accentTextSoft else colors.warningText, top = 4.dp)
    }
    LDGap(8.dp)
    LDKeyValueList(deliveryFacts(delivery, formats), Modifier.ldStaggerRise(0))
    delivery.preview?.let { PayloadBlock(it, delivery.stored.orEmpty()) }
    if (delivery.readings.isNotEmpty()) {
        LDSectionLabel(stringResource(R.string.ld_logs_readings))
        LDCard(Modifier.ldStaggerRise(1)) {
            delivery.readings.forEach { ReceiveReading(it, formats) }
        }
        if (delivery.readings.any { it.value == null }) {
            LDNote(stringResource(R.string.ld_logs_values_note), color = colors.muted, size = LDNoteSize.Small)
        }
    }
    LDGap(14.dp)
    LDButton(stringResource(R.string.ld_logs_delete), kind = LDButtonKind.Danger, size = LDButtonSize.Medium, onClick = onDelete)
}

/** Only what the row can back up. Without a status code there was no answer to go on, as for a backfill run: its reason is under Error. */
@Composable
private fun deliveryNote(log: WebhookLog): String? = when {
    log.isIncoming -> (log.recordCount ?: 0).takeIf { it > 0 }?.let { pluralStringResource(R.plurals.receive_written_count, it, it) }
    log.isMqtt -> if (log.success) stringResource(R.string.ld_logs_broker_accepted) else null
    log.statusCode == null -> null
    log.success -> stringResource(R.string.ld_logs_accepted, log.hosts)
    else -> stringResource(R.string.ld_logs_not_accepted, log.hosts)
}

@Composable
private fun deliveryFacts(delivery: LoadedDelivery, formats: LogFormats): List<LDKeyValue> {
    val log = delivery.log
    val problem = if (log.success) null else LDTheme.colors.warningText
    val section = when (log.logType) {
        LogType.HEALTH_CONNECT.name -> R.string.logs_filter_health
        LogType.SCREEN_TIME.name -> R.string.logs_filter_screen_time
        else -> R.string.logs_filter_unknown
    }
    val countKey = when {
        log.isIncoming -> R.string.logs_written
        log.isMqtt -> R.string.ld_logs_key_sensors
        log.dataType == "screen_time" -> R.string.ld_logs_key_apps
        else -> R.string.logs_stat_records
    }
    return buildList {
        add(LDKeyValue(stringResource(R.string.ld_logs_key_time), formats.dateTimeSeconds(log.timestamp)))
        if (log.url.isNotBlank()) add(LDKeyValue(stringResource(R.string.ld_logs_key_url), log.url, isCode = true))
        log.statusCode?.let { add(LDKeyValue(stringResource(R.string.ld_logs_key_status), it.toString(), color = problem)) }
        log.errorMessage?.let { add(LDKeyValue(stringResource(R.string.logs_error), it, color = problem)) }
        log.note?.let { add(LDKeyValue(stringResource(R.string.ld_logs_key_note), it)) }
        log.recordCount?.let { add(LDKeyValue(stringResource(countKey), formatCount(it))) }
        log.dataType?.let { add(LDKeyValue(stringResource(R.string.ld_logs_key_type), it, isCode = true)) }
        add(LDKeyValue(stringResource(R.string.ld_logs_key_section), stringResource(section)))
        delivery.preview?.let { preview ->
            val size = Formatter.formatShortFileSize(LocalContext.current, delivery.storedBytes.toLong())
            add(
                LDKeyValue(
                    stringResource(R.string.ld_logs_key_payload_size),
                    if (preview.cutInStorage) stringResource(R.string.ld_logs_size_stored, size) else size
                )
            )
        }
    }
}

@Composable
private fun PayloadBlock(preview: PayloadPreview, stored: String) {
    val colors = LDTheme.colors
    val description = stringResource(R.string.payload_preview_a11y, formatCount(preview.totalLength))
    LDSectionLabel(stringResource(R.string.logs_payload))
    // Replaces the text's own semantics: a plain contentDescription would sit next to the whole text.
    LDCodeBlock(preview.text, Modifier.clearAndSetSemantics { contentDescription = description }, wraps = true, selectable = false)
    if (preview.cutForDisplay) {
        LDNote(
            stringResource(R.string.payload_preview_shown_part, formatCount(PayloadPreview.MAX_CHARS), formatCount(preview.totalLength)),
            color = colors.muted,
            size = LDNoteSize.Small
        )
    }
    // A payload the store cut short is not whole anywhere, so nothing promises it.
    if (preview.cutInStorage) {
        LDNote(stringResource(R.string.logs_payload_stored_part), color = colors.muted, size = LDNoteSize.Small)
    } else if (preview.cutForDisplay) {
        LDNote(stringResource(fullPayloadHint(stored)), color = colors.muted, size = LDNoteSize.Small)
    }
}

/**
 * One reading of a Receive round: entity, type, value and unit (only when full payloads are kept),
 * measurement time, outcome and reason. Never the secret or the signature; the line for a rejected
 * response names the reason and nothing else.
 */
@Composable
private fun ReceiveReading(line: ReceiveLogLine, formats: LogFormats) {
    val colors = LDTheme.colors
    val type = line.type?.let { receiveTypeName(it) } ?: stringResource(R.string.logs_filter_unknown)
    val value = line.value?.let { reading ->
        val number = if (line.diastolic != null) "${readingNumber(reading)}/${readingNumber(line.diastolic)}" else readingNumber(reading)
        "$number ${line.unit.orEmpty()}".trim()
    }
    val measured = line.time?.let { time ->
        runCatching { formats.dateTimeSeconds(Instant.parse(time).toEpochMilli()) }.getOrNull()
    }
    val outcome = stringResource(
        when (line.outcome) {
            ReceiveLogLine.WRITTEN -> R.string.logs_written
            ReceiveLogLine.SKIPPED -> R.string.logs_skipped
            else -> R.string.logs_failed
        }
    )
    val outcomeWithReason = line.reason?.let { stringResource(R.string.logs_receive_outcome_reason, outcome, it) } ?: outcome
    Column(
        Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) { }
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Text(
            listOfNotNull(line.entity, type, value).joinToString(" · ").ldKeepingSeparators,
            style = LDType.mono(12.5.sp, bold = true, lineHeight = 19.sp),
            color = colors.text
        )
        Text(
            listOfNotNull(measured, outcomeWithReason).joinToString(" · ").ldKeepingSeparators,
            style = LDType.mono(12.sp, lineHeight = 17.sp),
            color = if (line.outcome == ReceiveLogLine.FAILED) colors.warningText else colors.muted
        )
    }
}

private fun readingNumber(value: Double): String =
    if (value == Math.rint(value)) value.toLong().toString() else String.format(Locale.getDefault(), "%.2f", value).trimEnd('0').trimEnd('.', ',')
