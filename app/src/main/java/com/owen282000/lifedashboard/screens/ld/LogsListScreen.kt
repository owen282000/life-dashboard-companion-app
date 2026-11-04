package com.owen282000.lifedashboard.screens.ld

import android.text.format.DateFormat
import android.text.format.Formatter
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.owen282000.lifedashboard.BACKFILL_DATA_TYPE
import com.owen282000.lifedashboard.BACKFILL_RUN_DATA_TYPE
import com.owen282000.lifedashboard.LogType
import com.owen282000.lifedashboard.R
import com.owen282000.lifedashboard.WebhookLog
import com.owen282000.lifedashboard.WebhookLogRow
import com.owen282000.lifedashboard.WebhookLogStore
import com.owen282000.lifedashboard.WriteBackType
import com.owen282000.lifedashboard.screens.formatCount
import com.owen282000.lifedashboard.ui.ld.LDButton
import com.owen282000.lifedashboard.ui.ld.LDButtonKind
import com.owen282000.lifedashboard.ui.ld.LDButtonSize
import com.owen282000.lifedashboard.ui.ld.LDChevron
import com.owen282000.lifedashboard.ui.ld.LDChip
import com.owen282000.lifedashboard.ui.ld.LDChips
import com.owen282000.lifedashboard.ui.ld.LDFooterBar
import com.owen282000.lifedashboard.ui.ld.LDGap
import com.owen282000.lifedashboard.ui.ld.LDHairline
import com.owen282000.lifedashboard.ui.ld.LDHeading
import com.owen282000.lifedashboard.ui.ld.LDListScreen
import com.owen282000.lifedashboard.ui.ld.LDMotion
import com.owen282000.lifedashboard.ui.ld.LDNote
import com.owen282000.lifedashboard.ui.ld.LDRoute
import com.owen282000.lifedashboard.ui.ld.LDTheme
import com.owen282000.lifedashboard.ui.ld.LDType
import com.owen282000.lifedashboard.ui.ld.LocalLDNavigator
import com.owen282000.lifedashboard.ui.ld.ldCodeStyle
import com.owen282000.lifedashboard.ui.ld.ldKeepingSeparators
import com.owen282000.lifedashboard.ui.ld.ldLargeText
import com.owen282000.lifedashboard.ui.ld.ldStaggerRise
import com.owen282000.lifedashboard.viewmodel.LogExportFormat
import com.owen282000.lifedashboard.viewmodel.LogsState
import com.owen282000.lifedashboard.viewmodel.LogsViewModel
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date
import java.util.Locale

private enum class LogDialog { Export, Clear }

/**
 * The Logs tab, for developers who scan: one line per delivery with what the log stores. Duration,
 * response body and attempt count are not stored, so they are not here. Export and Clear go by the
 * rows the filter shows.
 */
@Composable
fun LogsListScreen() {
    val viewModel: LogsViewModel = viewModel()
    val logs by viewModel.state.collectAsStateWithLifecycle()
    val isPulling by viewModel.isPulling.collectAsStateWithLifecycle()
    val health = ldHealthViewModel()
    val screenTime = ldScreenTimeViewModel()
    val healthState by health.state.collectAsStateWithLifecycle()
    val screenTimeState by screenTime.state.collectAsStateWithLifecycle()
    val navigator = LocalLDNavigator.current

    var filter by rememberSaveable { mutableStateOf(LogFilter.All) }
    var dialog by rememberSaveable { mutableStateOf<LogDialog?>(null) }
    var hasRisen by rememberSaveable { mutableStateOf(false) }

    LifecycleResumeEffect(viewModel) {
        viewModel.refresh()
        onPauseOrDispose { }
    }
    // Any sync or ping that wrote its rows while this tab is open, the worker's too.
    LaunchedEffect(healthState.refreshKey, screenTimeState.refreshKey) {
        viewModel.refresh()
    }
    LaunchedEffect(logs != null) {
        if (logs != null) {
            delay((LDMotion.RISE_MILLIS + LDMotion.STAGGER_CAP * LDMotion.STAGGER_MILLIS).toLong())
            hasRisen = true
        }
    }

    val rows = logs?.rows.orEmpty()
    val shown = remember(rows, filter) { rows.filter { filter.includes(it.log) } }
    val zone = ZoneId.systemDefault()
    val lines = remember(shown) { LogLine.of(shown, LocalDate.now(zone), zone) }
    val formats = rememberLogFormats()
    val pingHealth = filter != LogFilter.ScreenTime && healthState.saved.webhook.urls.isNotEmpty()
    val pingScreenTime = filter != LogFilter.Health && screenTimeState.saved.webhook.urls.isNotEmpty()

    LDListScreen(
        isRefreshing = isPulling,
        onRefresh = { viewModel.refresh(pulled = true) },
        footer = {
            LDFooterBar {
                LDButton(
                    stringResource(R.string.ld_logs_send_test),
                    kind = LDButtonKind.Ghost,
                    size = LDButtonSize.Small,
                    enabled = pingHealth || pingScreenTime,
                    busy = healthState.isPinging || screenTimeState.isPinging
                ) {
                    if (pingHealth) health.testPing()
                    if (pingScreenTime) screenTime.testPing()
                }
                LDButton(
                    stringResource(R.string.sync_action_export),
                    kind = LDButtonKind.Ghost,
                    size = LDButtonSize.Small,
                    enabled = shown.isNotEmpty()
                ) { dialog = LogDialog.Export }
                LDButton(
                    stringResource(R.string.logs_clear_confirm),
                    kind = LDButtonKind.Ghost,
                    size = LDButtonSize.Small,
                    enabled = shown.isNotEmpty()
                ) { dialog = LogDialog.Clear }
            }
        }
    ) {
        logs?.let { loaded ->
            item(key = "top", contentType = "top") {
                Column { LogsTop(loaded, formats) }
            }
            if (rows.isNotEmpty()) {
                item(key = "filters", contentType = "filters") {
                    Column {
                        LogFilters(filter, failed = rows.count { LogFilter.Failed.includes(it.log) }) { filter = it }
                        LDGap(10.dp)
                        LDHairline()
                        if (shown.isEmpty()) LDNote(stringResource(R.string.ld_logs_no_match), top = 12.dp)
                    }
                }
            }
        }
        itemsIndexed(lines, key = { _, line -> line.key }, contentType = { _, line -> line::class }) { index, line ->
            when (line) {
                is LogLine.Day -> LogDayLine(line.date, formats)
                is LogLine.Row -> LogRowLine(
                    line.row,
                    formats,
                    onOpen = { navigator.push(LDRoute.LogDetail(line.row.log.id)) },
                    onDelete = { viewModel.delete(setOf(line.row.log.id)) },
                    onShare = { viewModel.sharePayload(line.row.log.id) },
                    modifier = if (hasRisen) Modifier else Modifier.ldStaggerRise(index)
                )
            }
        }
    }

    val ids = { shown.mapTo(HashSet()) { it.log.id } }
    when (dialog) {
        LogDialog.Export -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text(stringResource(R.string.logs_export_title)) },
            text = { Text(pluralStringResource(R.plurals.logs_export_dialog_description, shown.size, shown.size)) },
            confirmButton = {
                TextButton(onClick = {
                    dialog = null
                    viewModel.export(ids(), LogExportFormat.Json)
                }) { Text(stringResource(R.string.common_json)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    dialog = null
                    viewModel.export(ids(), LogExportFormat.Csv)
                }) { Text(stringResource(R.string.common_csv)) }
            }
        )
        // The logs are the only record of what was delivered, so one tap does not wipe them.
        LogDialog.Clear -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text(stringResource(R.string.logs_clear_title)) },
            text = { Text(pluralStringResource(R.plurals.logs_clear_body, shown.size, shown.size)) },
            confirmButton = {
                TextButton(onClick = {
                    dialog = null
                    viewModel.delete(ids())
                }) { Text(stringResource(R.string.logs_clear_confirm), color = LDTheme.colors.warning) }
            },
            dismissButton = {
                TextButton(onClick = { dialog = null }) { Text(stringResource(R.string.common_cancel)) }
            }
        )
        null -> Unit
    }
}

/** The heading says the state, the lines below it the figures of what went out. */
@Composable
private fun LogsTop(logs: LogsState, formats: LogFormats) {
    val stats = logs.stats
    val outbox = if (logs.pending == 0) {
        stringResource(R.string.ld_logs_outbox_empty)
    } else {
        pluralStringResource(R.plurals.ld_logs_pending, logs.pending, logs.pending)
    }
    if (logs.rows.isEmpty()) {
        LDHeading(stringResource(R.string.ld_logs_nothing_yet))
        LogsFigures(listOf(outbox))
        LDNote(stringResource(R.string.ld_logs_empty_note, WebhookLogStore.MAX_ENTRIES), top = 6.dp)
        return
    }
    LDHeading(stringResource(R.string.ld_logs_state, stats.failed, stats.delivered))
    val figures = buildList {
        val percent = stats.successPercent
        add(if (percent == null) outbox else "$outbox · ${stringResource(R.string.ld_logs_success_rate, percent)}")
        val lastSuccess = stats.lastSuccess?.let(formats::dateTime) ?: stringResource(R.string.common_never)
        add(stringResource(R.string.logs_last_success, lastSuccess))
        if (stats.deliveries > 0) {
            val deliveries = pluralStringResource(R.plurals.ld_logs_deliveries, stats.deliveries, formatCount(stats.deliveries))
            val records = pluralStringResource(R.plurals.ld_logs_records, stats.records, formatCount(stats.records))
            add("$deliveries · $records")
            add(stringResource(R.string.ld_logs_by_section, stats.healthDelivered, stats.health, stats.screenTimeDelivered, stats.screenTime))
        }
    }
    LogsFigures(figures)
}

@Composable
private fun LogsFigures(lines: List<String>) {
    Column(Modifier.fillMaxWidth().padding(start = LDTheme.textInset, end = LDTheme.textInset, top = 2.dp, bottom = 12.dp)) {
        lines.forEach { line ->
            Text(line.ldKeepingSeparators, style = LDType.monoValue, color = LDTheme.colors.muted)
        }
    }
}

@Composable
private fun LogFilters(selected: LogFilter, failed: Int, onSelect: (LogFilter) -> Unit) {
    LDChips {
        LogFilter.entries.forEach { filter ->
            val title = when (filter) {
                LogFilter.All -> stringResource(R.string.logs_filter_all)
                LogFilter.Failed -> stringResource(R.string.ld_logs_filter_failed, failed)
                LogFilter.Webhook -> stringResource(R.string.logs_destination_webhook)
                LogFilter.Mqtt -> stringResource(R.string.logs_destination_mqtt)
                LogFilter.Health -> stringResource(R.string.logs_filter_health)
                LogFilter.ScreenTime -> stringResource(R.string.logs_filter_screen_time)
            }
            LDChip(title, selected = filter == selected) { onSelect(filter) }
        }
    }
}

@Composable
private fun LogDayLine(date: LocalDate, formats: LogFormats) {
    Column(Modifier.fillMaxWidth().semantics(mergeDescendants = true) { heading() }) {
        Text(
            formats.day(date),
            style = LDType.mono(12.sp, lineHeight = 17.sp),
            color = LDTheme.colors.muted,
            modifier = Modifier.padding(start = LDTheme.textInset, end = LDTheme.textInset, top = 12.dp, bottom = 4.dp)
        )
        LDHairline()
    }
}

/** One delivery: the code in a fixed column, where it went and when, type, count and size, and the reason of a failure. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LogRowLine(
    row: WebhookLogRow,
    formats: LogFormats,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
    onShare: () -> Unit,
    modifier: Modifier = Modifier
) {
    val log = row.log
    val colors = LDTheme.colors
    val isFailure = !log.success
    val codeWidth = with(LocalDensity.current) { 38.sp.toDp() }
    val host = log.hosts.ifBlank { stringResource(R.string.receive_title) }
    val time = formats.time(log.timestamp)
    val facts = logRowFacts(row)
    val outcome = stringResource(logOutcome(log))
    val canShare = row.payloadBytes != null && !log.isIncoming
    var menuOpen by remember { mutableStateOf(false) }

    val delete = stringResource(R.string.ld_logs_delete)
    val share = stringResource(R.string.payload_share)
    // How it went first, so a failure is heard before the rest.
    val spoken = listOfNotNull(outcome, log.statusCode?.toString(), host, time, facts.joinToString(", "), log.errorMessage?.takeIf { isFailure }).joinToString(", ")
    val details = stringResource(R.string.ld_logs_show_details)
    val actions = stringResource(R.string.ld_logs_show_actions)

    Box(modifier) {
        Column(
            Modifier
                .fillMaxWidth()
                .then(if (isFailure) Modifier.background(colors.warningSurface) else Modifier)
                .combinedClickable(
                    role = Role.Button,
                    onClickLabel = details,
                    onLongClickLabel = actions,
                    onLongClick = { menuOpen = true },
                    onClick = onOpen
                )
                .clearAndSetSemantics {
                    contentDescription = spoken
                    customActions = buildList {
                        if (canShare) add(CustomAccessibilityAction(share) { onShare(); true })
                        add(CustomAccessibilityAction(delete) { onDelete(); true })
                    }
                }
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 57.dp)
                    .padding(start = LDTheme.textInset, end = 12.dp, top = 8.dp, bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    log.code,
                    style = LDType.mono(13.sp, bold = true).ldCodeStyle(),
                    color = logCodeColor(log),
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier.width(codeWidth)
                )
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    LogRowTitle(host, time)
                    Text(
                        facts.joinToString(" · ").ldKeepingSeparators,
                        style = LDType.mono(12.sp, lineHeight = 17.sp),
                        color = if (isFailure) colors.warningText else colors.secondary
                    )
                    if (isFailure && log.errorMessage != null) {
                        Text(
                            log.errorMessage,
                            style = LDType.mono(12.sp, lineHeight = 17.sp),
                            color = colors.warningText,
                            maxLines = if (ldLargeText()) Int.MAX_VALUE else 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                LDChevron()
            }
            LDHairline()
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            if (canShare) {
                DropdownMenuItem(text = { Text(share) }, onClick = { menuOpen = false; onShare() })
            }
            DropdownMenuItem(text = { Text(delete) }, onClick = { menuOpen = false; onDelete() })
        }
    }
}

/** Under each other at the largest font sizes, where the time would squeeze the name. */
@Composable
private fun LogRowTitle(host: String, time: String) {
    val colors = LDTheme.colors
    val hostStyle = LDType.sans(15.sp, FontWeight.Bold, 20.sp).ldCodeStyle()
    val timeStyle = LDType.monoValue.ldCodeStyle()
    if (ldLargeText()) {
        Text(host, style = hostStyle, color = colors.text)
        Text(time, style = timeStyle, color = colors.muted)
    } else {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
            Text(
                host,
                style = hostStyle,
                color = colors.text,
                maxLines = 1,
                overflow = TextOverflow.MiddleEllipsis,
                modifier = Modifier.weight(1f)
            )
            Text(time, style = timeStyle, color = colors.muted, maxLines = 1, softWrap = false)
        }
    }
}

@Composable
internal fun logCodeColor(log: WebhookLog) = when {
    !log.success -> LDTheme.colors.warning
    log.isSkippedReceive -> LDTheme.colors.muted
    else -> LDTheme.colors.accentText
}

/** How it went, in the words of the old status pill. */
internal fun logOutcome(log: WebhookLog): Int = when {
    !log.success -> R.string.logs_failed
    log.isSkippedReceive -> R.string.logs_skipped
    log.isIncoming -> R.string.logs_written
    log.isMqtt -> R.string.logs_published
    else -> R.string.logs_delivered
}

/** Type, count and size, and the word when it was not a plain delivery. */
@Composable
private fun logRowFacts(row: WebhookLogRow): List<String> {
    val log = row.log
    val count = log.recordCount ?: 0
    if (log.isIncoming) {
        return buildList {
            add(stringResource(R.string.receive_title))
            when {
                !log.success -> add(stringResource(R.string.logs_failed))
                count > 0 -> add(pluralStringResource(R.plurals.ld_logs_written, count, formatCount(count)))
                else -> add(stringResource(R.string.logs_skipped))
            }
            // Past two the row gets long, and the page has them all.
            val names = row.receiveTypes.take(2).map { receiveTypeName(it) }
            val more = row.receiveTypes.size - names.size
            if (names.isNotEmpty()) add(names.joinToString(", ") + if (more > 0) " +$more" else "")
        }
    }
    return buildList {
        add(logTypeLabel(log))
        if (count > 0) add(logCountText(log, count))
        row.payloadBytes?.let { bytes ->
            val size = Formatter.formatShortFileSize(LocalContext.current, bytes)
            add(if (row.cutInStorage) stringResource(R.string.ld_logs_size_stored, size) else size)
        }
        if (!log.success || log.isMqtt) add(stringResource(logOutcome(log)))
    }
}

@Composable
private fun logTypeLabel(log: WebhookLog): String = when {
    log.dataType == "test" -> stringResource(R.string.sync_action_ping)
    log.dataType == BACKFILL_DATA_TYPE || log.dataType == BACKFILL_RUN_DATA_TYPE -> stringResource(R.string.ld_title_backfill)
    log.logType == LogType.HEALTH_CONNECT.name -> stringResource(R.string.logs_filter_health)
    log.logType == LogType.SCREEN_TIME.name -> stringResource(R.string.logs_filter_screen_time)
    else -> stringResource(R.string.logs_filter_unknown)
}

@Composable
private fun logCountText(log: WebhookLog, count: Int): String = when {
    log.isMqtt -> pluralStringResource(R.plurals.ld_logs_sensors, count, formatCount(count))
    log.dataType == "screen_time" -> pluralStringResource(R.plurals.ld_logs_apps, count, formatCount(count))
    else -> pluralStringResource(R.plurals.ld_logs_records, count, formatCount(count))
}

/** A reading's type in the phone's language, or as it came when this version does not know it. */
@Composable
internal fun receiveTypeName(key: String): String =
    WriteBackType.fromKey(key)?.dataType?.let { stringResource(it.displayNameRes) } ?: key

/** Dates and times in the phone's language and clock. */
internal class LogFormats(locale: Locale, is24Hour: Boolean) {
    private fun format(locale: Locale, skeleton: String) = SimpleDateFormat(DateFormat.getBestDateTimePattern(locale, skeleton), locale)

    private val clock = format(locale, if (is24Hour) "Hms" else "hms")
    private val minutes = format(locale, if (is24Hour) "MMMdHm" else "MMMdhm")
    private val seconds = format(locale, if (is24Hour) "MMMdHms" else "MMMdhms")
    private val date = format(locale, "MMMd")

    fun time(millis: Long): String = clock.format(Date(millis))

    fun dateTime(millis: Long): String = minutes.format(Date(millis))

    fun dateTimeSeconds(millis: Long): String = seconds.format(Date(millis))

    fun day(day: LocalDate): String = date.format(Date(day.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()))
}

@Composable
internal fun rememberLogFormats(): LogFormats {
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    val is24Hour = DateFormat.is24HourFormat(context)
    return remember(locale, is24Hour) { LogFormats(locale, is24Hour) }
}
