package com.owen282000.lifedashboard.screens.ld

import android.content.Context
import android.icu.text.MeasureFormat
import android.icu.util.Measure
import android.icu.util.MeasureUnit
import android.text.format.DateUtils
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.owen282000.lifedashboard.AppFilterMode
import com.owen282000.lifedashboard.AppUsageData
import com.owen282000.lifedashboard.LogType
import com.owen282000.lifedashboard.R
import com.owen282000.lifedashboard.ScreenTimeManager
import com.owen282000.lifedashboard.SyncMode
import com.owen282000.lifedashboard.appPreferences
import com.owen282000.lifedashboard.screens.ScheduleStatusLine
import com.owen282000.lifedashboard.screens.formatTime
import com.owen282000.lifedashboard.screens.text
import com.owen282000.lifedashboard.ui.ld.LDBanner
import com.owen282000.lifedashboard.ui.ld.LDBannerAction
import com.owen282000.lifedashboard.ui.ld.LDButton
import com.owen282000.lifedashboard.ui.ld.LDButtonSize
import com.owen282000.lifedashboard.ui.ld.LDDestinationRow
import com.owen282000.lifedashboard.ui.ld.LDGap
import com.owen282000.lifedashboard.ui.ld.LDHeading
import com.owen282000.lifedashboard.ui.ld.LDIcons
import com.owen282000.lifedashboard.ui.ld.LDNavigator
import com.owen282000.lifedashboard.ui.ld.LDNote
import com.owen282000.lifedashboard.ui.ld.LDNoteSize
import com.owen282000.lifedashboard.ui.ld.LDPulseVariant
import com.owen282000.lifedashboard.ui.ld.LDRefusedBanner
import com.owen282000.lifedashboard.ui.ld.LDRoute
import com.owen282000.lifedashboard.ui.ld.LDScreen
import com.owen282000.lifedashboard.ui.ld.LDSection
import com.owen282000.lifedashboard.ui.ld.LDSectionLabel
import com.owen282000.lifedashboard.ui.ld.LDTextButton
import com.owen282000.lifedashboard.ui.ld.LDTheme
import com.owen282000.lifedashboard.ui.ld.LDTile
import com.owen282000.lifedashboard.ui.ld.LDTileGrid
import com.owen282000.lifedashboard.ui.ld.LDType
import com.owen282000.lifedashboard.ui.ld.LocalLDNavigator
import com.owen282000.lifedashboard.ui.ld.PulseLine
import com.owen282000.lifedashboard.ui.ld.SparkLine
import com.owen282000.lifedashboard.ui.ld.ldLargeText
import com.owen282000.lifedashboard.ui.ld.ldStaggerRise
import com.owen282000.lifedashboard.ui.ld.rememberLDDraft
import com.owen282000.lifedashboard.viewmodel.ScreenTimeUiState
import com.owen282000.lifedashboard.viewmodel.ScreenTimeViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.Locale

private const val MOST_USED_COUNT = 5

/** How often the open tab reads again, so today's total and "12 min ago" do not stand still. */
private const val RELOAD_MILLIS = 60_000L

/** The Screen Time tab. The shell polls usage access, so losing or getting it switches the screen by itself. */
@Composable
fun ScreenTimeHomeScreen() {
    val viewModel = ldScreenTimeViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val navigator = LocalLDNavigator.current
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    // Kept with the page, so coming back from a pushed page does not flash an empty panel.
    var data by rememberLDDraft<ScreenTimeHomeData?> { null }

    LaunchedEffect(state.hasUsageAccess, state.refreshKey, state.saved.useDayBoundary, state.saved.dayBoundaryHour) {
        if (!state.hasUsageAccess) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                data = readScreenTimeHome(context)
                delay(RELOAD_MILLIS)
            }
        }
    }

    LDScreen(pinned = { ScreenTimeTiles(state, navigator) }) {
        if (state.hasUsageAccess) {
            ScreenTimeGranted(state, data, viewModel, navigator)
        } else {
            LDHeading(stringResource(R.string.ld_screen_heading_no_access))
            PulseLine(variant = LDPulseVariant.Flat)
            LDNote(stringResource(R.string.ld_screen_no_access_body), top = 0.dp)
            LDGap(14.dp)
            LDButton(stringResource(R.string.ld_screen_open_settings), onClick = viewModel::requestUsageAccess)
            ScreenTimeNoticeBanners(state, viewModel)
        }
    }
}

private suspend fun readScreenTimeHome(context: Context): ScreenTimeHomeData = withContext(Dispatchers.IO) {
    val preferences = context.appPreferences()
    val days = ScreenTimeManager(context, preferences).readScreenTimeData(lookbackDays = 7).getOrDefault(emptyList())
    ScreenTimeHomeModel.data(
        days = days,
        today = ScreenTimeHomeModel.logicalToday(
            LocalDateTime.now(),
            preferences.useScreenTimeDayBoundary(),
            preferences.getScreenTimeDayBoundaryHour()
        ),
        lastSyncMillis = preferences.getScreenTimeLastSyncTimestamp(),
        logs = preferences.getWebhookLogsWithoutPayloads(LogType.SCREEN_TIME),
        readAt = System.currentTimeMillis()
    )
}

@Composable
private fun ScreenTimeGranted(
    state: ScreenTimeUiState,
    data: ScreenTimeHomeData?,
    viewModel: ScreenTimeViewModel,
    navigator: LDNavigator
) {
    val rows = screenTimeDestinationRows(state, data)
    LDHeading(
        if (data != null) {
            stringResource(R.string.ld_screen_heading_total, screenTimeDuration(data.totalMinutes))
        } else {
            stringResource(R.string.main_title_screen_time)
        }
    )
    PulseLine(
        variant = when {
            rows.any { it.failed } -> LDPulseVariant.Warning
            rows.any { it.delivered } -> LDPulseVariant.Normal
            else -> LDPulseVariant.Flat
        }
    )
    rows.forEachIndexed { index, row ->
        LDDestinationRow(
            row.name,
            row.status,
            modifier = Modifier.ldStaggerRise(index),
            statusColor = row.statusColor(),
            onClick = { navigator.push(row.route) }
        )
    }
    ScreenTimeSyncActions(state, viewModel, navigator)
    state.syncMessage?.let { message ->
        LDNote(
            message.text(LocalResources.current),
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            color = if (message.isFailure) LDTheme.colors.warning else LDTheme.colors.accentTextSoft,
            top = 4.dp,
            size = LDNoteSize.Small
        )
    }
    ScheduleStatusLine(
        schedule = state.saved.schedule,
        webhookCount = state.saved.webhook.urls.size,
        mqttEnabled = state.saved.mqtt.section.enabled,
        modifier = Modifier.fillMaxWidth().padding(start = LDTheme.textInset, end = LDTheme.textInset, top = 4.dp)
    )
    ScreenTimeNoticeBanners(state, viewModel)
    LDBatteryCard(rememberLDBatteryHint())
    ScreenTimePanel(data, Modifier.ldStaggerRise(rows.size))
    ScreenTimeMostUsed(data, firstIndex = rows.size + 1)
}

private data class ScreenTimeDestinationRow(
    val name: String,
    val status: String,
    val route: LDRoute,
    val delivered: Boolean = false,
    val failed: Boolean = false,
    val needsBroker: Boolean = false
)

@Composable
private fun ScreenTimeDestinationRow.statusColor(): Color = when {
    failed || needsBroker -> LDTheme.colors.warning
    delivered -> LDTheme.colors.accentTextSoft
    else -> LDTheme.colors.inactive
}

/** One per webhook, then MQTT when it is on. */
@Composable
private fun screenTimeDestinationRows(state: ScreenTimeUiState, data: ScreenTimeHomeData?): List<ScreenTimeDestinationRow> {
    val webhook = state.saved.webhook
    val mqtt = state.saved.mqtt
    val homeAssistant = stringResource(R.string.ld_home_assistant)
    val rows = webhook.destinations.map { destination ->
        val delivery = data?.webhookDeliveries?.get(destination.url)
        ScreenTimeDestinationRow(
            name = DestinationNames.name(destination, webhook.destinations, homeAssistant),
            status = screenTimeDeliveryText(delivery, data),
            route = LDRoute.WebhookDestination(LDSection.ScreenTime, destination.id),
            delivered = delivery?.success == true,
            failed = delivery?.success == false
        )
    }
    if (!mqtt.section.enabled) return rows
    val host = mqtt.activeBroker.host.trim()
    val delivery = data?.mqttDelivery
    return rows + ScreenTimeDestinationRow(
        name = if (host.isEmpty()) stringResource(R.string.mqtt_title) else stringResource(R.string.ld_screen_broker_named, host),
        status = if (host.isEmpty()) stringResource(R.string.mqtt_enabled_no_broker) else screenTimeDeliveryText(delivery, data),
        route = LDRoute.MqttDestination(LDSection.ScreenTime),
        delivered = delivery?.success == true,
        failed = delivery?.success == false,
        needsBroker = host.isEmpty()
    )
}

@Composable
private fun screenTimeDeliveryText(delivery: ScreenTimeDelivery?, data: ScreenTimeHomeData?): String {
    // Blank for the moment the log takes to read, so a row never says something untrue.
    if (data == null) return " "
    if (delivery == null) return stringResource(R.string.ld_screen_status_no_deliveries)
    val time = if (data.readAt - delivery.timestamp < DateUtils.MINUTE_IN_MILLIS) {
        stringResource(R.string.ld_screen_status_just_now)
    } else {
        DateUtils.getRelativeTimeSpanString(delivery.timestamp, data.readAt, DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE).toString()
    }
    return when {
        delivery.success -> time
        delivery.statusCode != null -> stringResource(R.string.ld_screen_status_http, delivery.statusCode, time)
        else -> stringResource(R.string.ld_screen_status_failed, time)
    }
}

/** Add destination and Sync Now, under each other at the font sizes where they no longer fit. */
@Composable
private fun ScreenTimeSyncActions(state: ScreenTimeUiState, viewModel: ScreenTimeViewModel, navigator: LDNavigator) {
    val add: @Composable () -> Unit = {
        LDTextButton(stringResource(R.string.ld_title_add_destination)) {
            navigator.push(LDRoute.AddDestination(LDSection.ScreenTime))
        }
    }
    val sync: @Composable () -> Unit = {
        LDButton(
            stringResource(if (state.isSyncBusy) R.string.sync_syncing else R.string.sync_now),
            size = LDButtonSize.Small,
            fullWidth = false,
            enabled = state.saved.hasDestination,
            busy = state.isSyncBusy,
            onClick = viewModel::syncNow
        )
    }
    // The text button brings 4 of its own, which the margin leaves room for.
    val margin = Modifier.fillMaxWidth().padding(start = LDTheme.textInset - 4.dp, end = LDTheme.textInset, top = 4.dp)
    if (ldLargeText()) {
        Column(margin, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            add()
            sync()
        }
    } else {
        Row(margin, verticalAlignment = Alignment.CenterVertically) {
            add()
            Spacer(Modifier.weight(1f))
            sync()
        }
    }
}

/** The secure storage's state and a refused removal: nothing else tells the user. */
@Composable
private fun ScreenTimeNoticeBanners(state: ScreenTimeUiState, viewModel: ScreenTimeViewModel) {
    LDRefusedBanner(state.refused != null, viewModel::retryRefused, viewModel::dismissRefused)
    if (state.secretsUnavailable) {
        LDBanner(stringResource(R.string.ld_screen_secrets_unavailable))
    }
    if (state.secretsNeedReentry) {
        LDBanner(stringResource(R.string.secrets_lost_banner)) {
            LDBannerAction(stringResource(R.string.common_close), onClick = viewModel::dismissSecretsLost)
        }
    }
}

/** Today, Top app, Last sync and the minutes per day. A figure without data says so, so the panel never stands empty. */
@Composable
private fun ScreenTimePanel(data: ScreenTimeHomeData?, modifier: Modifier = Modifier) {
    val topApp = data?.apps?.firstOrNull()
    val todayLabel = stringResource(R.string.dashboard_today)
    val topLabel = stringResource(R.string.dashboard_top_app)
    val syncLabel = stringResource(R.string.dashboard_last_sync)
    val cells: List<@Composable (Modifier) -> Unit> = listOf(
        { cell ->
            ScreenTimeFigure(data?.let { screenTimeDuration(it.totalMinutes) } ?: "-", todayLabel, cell)
        },
        { cell ->
            if (topApp != null) {
                val minutes = screenTimeDuration(topApp.totalTimeMs / 60_000)
                val spoken = stringResource(R.string.ld_key_value, topLabel, stringResource(R.string.ld_key_value, topApp.appName, minutes))
                Column(cell.clearAndSetSemantics { contentDescription = spoken }) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        ScreenTimeAppIcon(topApp.packageName, 22)
                        ScreenTimeFigureValue(minutes)
                    }
                    Text(topLabel, style = LDType.rowSubtitle, color = LDTheme.colors.muted)
                }
            } else {
                ScreenTimeFigure(if (data == null) "-" else stringResource(R.string.dashboard_none), topLabel, cell)
            }
        },
        { cell ->
            val sync = data?.lastSyncMillis
            ScreenTimeFigure(
                when {
                    data == null -> "-"
                    sync == null -> stringResource(R.string.common_never)
                    else -> DateUtils.formatSameDayTime(sync, data.readAt, DateFormat.SHORT, DateFormat.SHORT).toString()
                },
                syncLabel,
                cell
            )
        }
    )
    Column(modifier.fillMaxWidth().padding(start = LDTheme.textInset, end = LDTheme.textInset, top = 12.dp)) {
        if (ldLargeText()) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) { cells.forEach { it(Modifier.fillMaxWidth()) } }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) { cells.forEach { it(Modifier.weight(1f)) } }
        }
        val minutes = data?.minutesPerDay.orEmpty()
        if (minutes.size >= 2) {
            val label = stringResource(R.string.dashboard_minutes_per_day_last_days, minutes.size)
            val spoken = stringResource(R.string.ld_key_value, label, minutes.joinToString(", "))
            Column(Modifier.padding(top = 10.dp).clearAndSetSemantics { contentDescription = spoken }) {
                Text(label, style = LDType.rowSubtitle, color = LDTheme.colors.muted)
                SparkLine(minutes.map { it.toDouble() }, Modifier.padding(top = 4.dp), height = 28.dp)
            }
        }
    }
}

@Composable
private fun ScreenTimeFigure(value: String, label: String, modifier: Modifier) {
    Column(modifier.semantics(mergeDescendants = true) { }) {
        ScreenTimeFigureValue(value)
        Text(label, style = LDType.rowSubtitle, color = LDTheme.colors.muted)
    }
}

@Composable
private fun ScreenTimeFigureValue(value: String) {
    // On one line and smaller rather than a stray unit below, as a long duration in Arabic did.
    BasicText(
        value,
        style = LDType.sans(20.sp, FontWeight.Bold, 26.sp).copy(color = LDTheme.colors.text),
        maxLines = 1,
        softWrap = false,
        autoSize = TextAutoSize.StepBased(minFontSize = 12.sp, maxFontSize = 20.sp)
    )
}

@Composable
private fun ScreenTimeMostUsed(data: ScreenTimeHomeData?, firstIndex: Int) {
    if (data == null) return
    LDSectionLabel(stringResource(R.string.ld_screen_most_used))
    val apps = data.apps.take(MOST_USED_COUNT)
    if (apps.isEmpty()) {
        LDNote(stringResource(R.string.ld_screen_nothing_today), top = 0.dp)
        return
    }
    val longest = apps.first().totalTimeMs.coerceAtLeast(1)
    Column(
        Modifier.fillMaxWidth().padding(horizontal = LDTheme.textInset),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        apps.forEachIndexed { index, app ->
            ScreenTimeAppLine(app, app.totalTimeMs.toFloat() / longest, Modifier.ldStaggerRise(firstIndex + index))
        }
    }
}

@Composable
private fun ScreenTimeAppLine(app: AppUsageData, share: Float, modifier: Modifier) {
    val colors = LDTheme.colors
    Row(
        modifier.fillMaxWidth().semantics(mergeDescendants = true) { },
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ScreenTimeAppIcon(app.packageName, 28)
        Column(Modifier.weight(1f)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(app.appName, style = LDType.sans(15.sp, FontWeight.Medium, 20.sp), color = colors.text, modifier = Modifier.weight(1f))
                Text(screenTimeDuration(app.totalTimeMs / 60_000), style = LDType.sans(15.sp, FontWeight.Medium, 20.sp), color = colors.inactive)
            }
            Box(
                Modifier
                    .padding(top = 4.dp)
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(colors.progressTrack)
                    .clearAndSetSemantics { }
            ) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(share.coerceIn(0f, 1f)).clip(RoundedCornerShape(3.dp)).background(colors.accent))
            }
        }
    }
}

/** The app's launcher icon, or a plain tile when Android does not show it to us. The name next to it says what it is. */
@Composable
private fun ScreenTimeAppIcon(packageName: String, size: Int) {
    val context = LocalContext.current
    val shape = RoundedCornerShape((size / 4).dp)
    val bitmap = remember(packageName) {
        runCatching { context.packageManager.getApplicationIcon(packageName).toBitmap(96, 96).asImageBitmap() }.getOrNull()
    }
    if (bitmap != null) {
        Image(bitmap, contentDescription = null, modifier = Modifier.size(size.dp).clip(shape))
    } else {
        Box(Modifier.size(size.dp).clip(shape).background(LDTheme.colors.tile), contentAlignment = Alignment.Center) {
            Icon(LDIcons.apps, contentDescription = null, tint = LDTheme.colors.icon, modifier = Modifier.size((size * 0.6f).dp))
        }
    }
}

/** The four tiles, on both boards of this tab. */
@Composable
private fun ScreenTimeTiles(state: ScreenTimeUiState, navigator: LDNavigator) {
    LDTileGrid {
        LDTile(
            stringResource(R.string.ld_title_apps_to_send),
            LDIcons.apps,
            subtitle = screenTimeAppsSummary(state),
            onClick = { navigator.push(LDRoute.ScreenTimeApps) }
        )
        LDTile(
            stringResource(R.string.ld_title_when_to_send),
            LDIcons.whenToSend,
            subtitle = screenTimeScheduleSummary(state),
            onClick = { navigator.push(LDRoute.ScreenTimeWhen) }
        )
        LDTile(
            stringResource(R.string.ld_title_day_starts_at),
            LDIcons.calendar,
            subtitle = if (state.saved.useDayBoundary) {
                screenTimeHourText(state.saved.dayBoundaryHour)
            } else {
                stringResource(R.string.screentime_day_boundary_disabled)
            },
            onClick = { navigator.push(LDRoute.ScreenTimeDay) }
        )
        LDTile(
            stringResource(R.string.ld_title_more),
            LDIcons.more,
            subtitle = stringResource(R.string.ld_screen_more_summary),
            onClick = { navigator.push(LDRoute.ScreenTimeMore) }
        )
    }
}

@Composable
private fun screenTimeAppsSummary(state: ScreenTimeUiState): String {
    val filter = state.saved.appFilter
    val count = filter.packages.size
    return when (filter.mode) {
        AppFilterMode.ALL -> stringResource(R.string.screentime_apps_all)
        AppFilterMode.BLOCKLIST -> pluralStringResource(R.plurals.screentime_apps_all_except, count, count)
        AppFilterMode.ALLOWLIST -> pluralStringResource(R.plurals.screentime_apps_only, count, count)
    }
}

/** The cadence alone: days and quiet hours are for the page itself. */
@Composable
private fun screenTimeScheduleSummary(state: ScreenTimeUiState): String {
    val schedule = state.saved.schedule
    return when {
        schedule.wouldNeverRun() -> stringResource(R.string.ld_screen_when_never)
        schedule.mode == SyncMode.INTERVAL -> stringResource(R.string.schedule_summary_interval, schedule.intervalText.toIntOrNull() ?: 0)
        else -> stringResource(R.string.schedule_summary_times, schedule.times.joinToString(", ") { formatTime(it) })
    }
}

/** A stored hour as the locale writes a time; what is not an hour is shown as it is. */
internal fun screenTimeHourText(hour: String): String =
    hour.toIntOrNull()?.let { formatTime(LocalTime.of(it.coerceIn(0, 23), 0)) } ?: hour

/** "3h 42m", in the short form each language has for it. */
@Composable
internal fun screenTimeDuration(minutes: Long): String {
    val locale = LocalConfiguration.current.locales[0]
    return remember(minutes, locale) { formatScreenTimeDuration(minutes, locale) }
}

private fun formatScreenTimeDuration(minutes: Long, locale: Locale): String {
    val hours = minutes / 60
    val rest = minutes % 60
    val parts = buildList {
        if (hours > 0) add(Measure(hours, MeasureUnit.HOUR))
        if (rest > 0 || hours == 0L) add(Measure(rest, MeasureUnit.MINUTE))
    }
    return MeasureFormat.getInstance(locale, MeasureFormat.FormatWidth.NARROW).formatMeasures(*parts.toTypedArray())
}
