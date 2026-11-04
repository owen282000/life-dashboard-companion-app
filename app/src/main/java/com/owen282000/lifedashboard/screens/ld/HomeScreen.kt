package com.owen282000.lifedashboard.screens.ld

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.format.DateUtils
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.owen282000.lifedashboard.HealthDataType
import com.owen282000.lifedashboard.LogType
import com.owen282000.lifedashboard.PendingSyncStore
import com.owen282000.lifedashboard.R
import com.owen282000.lifedashboard.ReceiveSummary
import com.owen282000.lifedashboard.SyncMode
import com.owen282000.lifedashboard.appPreferences
import com.owen282000.lifedashboard.screens.formatTime
import com.owen282000.lifedashboard.ui.ld.LDBackfillStage
import com.owen282000.lifedashboard.ui.ld.LDBanner
import com.owen282000.lifedashboard.ui.ld.LDButton
import com.owen282000.lifedashboard.ui.ld.LDButtonKind
import com.owen282000.lifedashboard.ui.ld.LDButtonSize
import com.owen282000.lifedashboard.ui.ld.LDDestinationRow
import com.owen282000.lifedashboard.ui.ld.LDGap
import com.owen282000.lifedashboard.ui.ld.LDHeading
import com.owen282000.lifedashboard.ui.ld.LDIcons
import com.owen282000.lifedashboard.ui.ld.LDNote
import com.owen282000.lifedashboard.ui.ld.LDNoteSize
import com.owen282000.lifedashboard.ui.ld.LDPulseVariant
import com.owen282000.lifedashboard.ui.ld.LDRefusedBanner
import com.owen282000.lifedashboard.ui.ld.LDRoute
import com.owen282000.lifedashboard.ui.ld.LDScreen
import com.owen282000.lifedashboard.ui.ld.LDSection
import com.owen282000.lifedashboard.ui.ld.LDTextButton
import com.owen282000.lifedashboard.ui.ld.LDTheme
import com.owen282000.lifedashboard.ui.ld.LDTile
import com.owen282000.lifedashboard.ui.ld.LDTileGrid
import com.owen282000.lifedashboard.ui.ld.LDType
import com.owen282000.lifedashboard.ui.ld.LocalLDNavigator
import com.owen282000.lifedashboard.ui.ld.LocalLDPairing
import com.owen282000.lifedashboard.ui.ld.PulseLine
import com.owen282000.lifedashboard.ui.ld.ldKeepingSeparators
import com.owen282000.lifedashboard.ui.ld.ldLargeText
import com.owen282000.lifedashboard.ui.ld.ldStaggerRise
import com.owen282000.lifedashboard.ui.ld.rememberLDDraft
import com.owen282000.lifedashboard.viewmodel.HcAvailability
import com.owen282000.lifedashboard.viewmodel.HealthConnectViewModel
import com.owen282000.lifedashboard.viewmodel.HealthUiState
import com.owen282000.lifedashboard.viewmodel.ScheduleDraft
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

private const val HEALTH_CONNECT_STORE_URL = "https://play.google.com/store/apps/details?id=com.google.android.apps.healthdata"

/** So "2 min ago" moves on, and Today follows what other apps wrote and the new day. A sync does not wait for this. */
private const val REREAD_MILLIS = 60_000L

/** How many Receive types still fit a tile by name. */
private const val RECEIVE_NAMES_ON_TILE = 2

/** The Health tab: where the data goes, whether it arrives, and what the user sends. */
@Composable
fun HomeScreen() {
    val viewModel = ldHealthViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    if (state.availability == HcAvailability.AVAILABLE) {
        HomeContent(state, viewModel)
    } else {
        HomeUnavailable(state.availability)
    }
}

/** Android 8, a work profile, or Health Connect missing or too old. About, Backup and Appearance behind More do not need it. */
@Composable
private fun HomeUnavailable(availability: HcAvailability) {
    val context = LocalContext.current
    LDScreen(pinned = { LDTileGrid { MoreTile() } }) {
        LDHeading(
            stringResource(
                when (availability) {
                    HcAvailability.NOT_INSTALLED -> R.string.health_connect_not_installed
                    HcAvailability.NEEDS_UPDATE -> R.string.health_connect_needs_update
                    else -> R.string.health_connect_not_available
                }
            )
        )
        PulseLine(variant = LDPulseVariant.Flat)
        LDNote(
            stringResource(
                when (availability) {
                    HcAvailability.NOT_INSTALLED -> R.string.ld_home_hc_install_note
                    HcAvailability.NEEDS_UPDATE -> R.string.ld_home_hc_update_note
                    else -> R.string.ld_home_hc_unavailable_note
                }
            ),
            top = 4.dp
        )
        // Android 8 or a work profile: the Play Store cannot help there.
        val action = when (availability) {
            HcAvailability.NOT_INSTALLED -> R.string.health_install
            HcAvailability.NEEDS_UPDATE -> R.string.health_update
            else -> null
        }
        if (action != null) {
            LDGap(16.dp)
            LDButton(stringResource(action)) { openHealthConnectInStore(context) }
        }
    }
}

/** A phone without the Play Store and without a browser has nothing to open the link. */
private fun openHealthConnectInStore(context: Context) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(HEALTH_CONNECT_STORE_URL)))
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, context.getString(R.string.health_could_not_open), Toast.LENGTH_SHORT).show()
    }
}

/** One row of the list: a webhook, or MQTT when it is on. */
private data class HomeRow(
    val key: String,
    val name: String,
    val route: LDRoute,
    val delivery: HomeDelivery?,
    val hasNoBroker: Boolean = false
) {
    val hasProblem: Boolean get() = hasNoBroker || delivery?.success == false
}

@Composable
private fun HomeContent(state: HealthUiState, viewModel: HealthConnectViewModel) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val navigator = LocalLDNavigator.current
    val saved = state.saved
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val urls = saved.webhook.urls
    // Kept while another page covers this one, so coming back does not blank the heading first.
    var deliveries by rememberLDDraft { HomeDeliveries() }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(lifecycle, urls, state.refreshKey) {
        val preferences = context.appPreferences()
        val dropped = setOf(
            resources.getString(R.string.outbox_replaced_log),
            resources.getString(R.string.outbox_dropped_log, PendingSyncStore.MAX_HEALTH_ITEMS)
        )
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                deliveries = withContext(Dispatchers.IO) {
                    HomeDeliveries.from(preferences.getWebhookLogsWithoutPayloads(LogType.HEALTH_CONNECT), urls, dropped)
                }
                now = System.currentTimeMillis()
                viewModel.refreshToday()
                delay(REREAD_MILLIS)
            }
        }
    }
    LaunchedEffect(state.refreshKey, saved.enabledTypes, state.grantedPermissions) { viewModel.refreshToday() }

    val rows = homeRows(state, deliveries)
    val zone = remember { ZoneId.systemDefault() }
    val schedule = saved.schedule.toSchedule()
    val overdueSince = deliveries.lastDelivered?.takeIf {
        SyncHealthCheck.isOverdue(it, now, schedule, saved.hasDestination, zone)
    }
    // A phone that only receives has picked what it wants: every sync is still its round trip.
    val nothingSelected = saved.enabledTypes.isEmpty() && !state.receive.enabled
    val home = HomeState.of(
        hasDestination = rows.isNotEmpty(),
        nothingSelected = nothingSelected,
        // A phone that only receives reads nothing: its write access is all the access it needs.
        accessMissing = state.hasPermissions == false && (saved.enabledTypes.isNotEmpty() || state.grantedPermissions.isEmpty()),
        failing = rows.filter { it.hasProblem }.map { it.name },
        overdue = overdueSince != null,
        isLoaded = deliveries.isLoaded,
        hasDelivery = rows.any { it.delivery != null }
    )
    val battery = rememberLDBatteryHint()

    LDScreen(pinned = { LDTileGrid { HomeTiles(state) } }) {
        LDHeading(heading(home))
        PulseLine(variant = pulse(home))
        when (home) {
            HomeState.NoDestination -> FirstStart()
            else -> {
                if (home == HomeState.NoAccess) {
                    LDNote(stringResource(R.string.ld_home_access_note), top = 4.dp)
                    LDGap(12.dp)
                    LDButton(stringResource(R.string.common_grant), onClick = viewModel::requestAccess)
                    LDGap(4.dp)
                }
                rows.forEachIndexed { index, row ->
                    key(row.key) {
                        val status = rowStatus(row, home, deliveries.isLoaded, now)
                        LDDestinationRow(
                            row.name,
                            status.first,
                            Modifier.ldStaggerRise(index),
                            statusColor = status.second,
                            onClick = { navigator.push(row.route) }
                        )
                    }
                }
                AddAndSync(state, showSync = home != HomeState.NothingSelected, viewModel)
            }
        }
        LDSyncMessageLine(state.syncMessage)
        BackfillLine(state, viewModel)

        LDSecretsBanners(state.secretsUnavailable, state.secretsNeedReentry, viewModel::dismissSecretsLost)
        LDRefusedBanner(state.refused != null, viewModel::retryRefused, viewModel::dismissRefused)
        if (saved.hasDestination) {
            val since = overdueSince?.let { stringResource(R.string.ld_home_nothing_since, formatDay(it, zone)) }
            LDBatteryCard(battery, since)
            // With the card showing, the card says since when; the banner would say it twice.
            if (overdueSince != null && !battery.showsCard) {
                LDBanner(
                    stringResource(
                        if (battery.optimized) R.string.ld_home_overdue_battery else R.string.ld_home_overdue_logs,
                        formatDay(overdueSince, zone)
                    )
                )
            }
            if (battery.showsCard || overdueSince != null) LDGap(8.dp)
        }

        if (home != HomeState.NoDestination && home != HomeState.NoAccess) {
            TodayPanel(state.today, nothingSelected = saved.enabledTypes.isEmpty(), firstIndex = rows.size)
        }
        if (saved.hasDestination) ScheduleHoldLine(saved.schedule, now)
    }
}

@Composable
private fun homeRows(state: HealthUiState, deliveries: HomeDeliveries): List<HomeRow> {
    val homeAssistant = stringResource(R.string.ld_home_assistant)
    val section = state.saved.webhook.destinations
    val rows = section.map { destination ->
        HomeRow(
            key = destination.id,
            name = DestinationNames.name(destination, section, homeAssistant),
            route = LDRoute.WebhookDestination(LDSection.Health, destination.id),
            delivery = deliveries.webhooks[destination.url]
        )
    }
    val mqtt = state.saved.mqtt
    if (!mqtt.section.enabled) return rows
    val host = mqtt.activeBroker.host.trim()
    return rows + HomeRow(
        key = "mqtt",
        name = if (host.isEmpty()) stringResource(R.string.mqtt_title) else stringResource(R.string.ld_home_broker, host),
        route = LDRoute.MqttDestination(LDSection.Health),
        delivery = deliveries.mqtt,
        hasNoBroker = host.isEmpty()
    )
}

@Composable
private fun heading(state: HomeState): String = when (state) {
    // One line of nothing, so the screen does not jump for the moment the log takes to read.
    HomeState.Loading -> " "
    HomeState.NoDestination -> stringResource(R.string.onboarding_destination_title)
    HomeState.NothingSelected -> stringResource(R.string.ld_home_heading_nothing_selected)
    HomeState.NoAccess -> stringResource(R.string.health_access_not_granted)
    is HomeState.Problem ->
        if (state.names.size == 1) stringResource(R.string.ld_home_heading_problem_one, state.names.single())
        else pluralStringResource(R.plurals.ld_home_heading_problem_many, state.names.size, state.names.size)
    HomeState.Overdue -> stringResource(R.string.ld_home_heading_overdue)
    HomeState.Waiting -> stringResource(R.string.ld_home_heading_waiting)
    HomeState.Arriving -> stringResource(R.string.ld_home_heading_arriving)
}

private fun pulse(state: HomeState): LDPulseVariant = when (state) {
    HomeState.Arriving -> LDPulseVariant.Normal
    is HomeState.Problem, HomeState.Overdue, HomeState.NoAccess -> LDPulseVariant.Warning
    else -> LDPulseVariant.Flat
}

@Composable
private fun FirstStart() {
    val navigator = LocalLDNavigator.current
    val pairing = LocalLDPairing.current
    LDGap(4.dp)
    LDButton(stringResource(R.string.webhook_scan_lead), onClick = pairing::startScan)
    LDGap(10.dp)
    LDButton(stringResource(R.string.ld_home_own_server), kind = LDButtonKind.Ghost) {
        navigator.push(LDRoute.AddDestination(LDSection.Health))
    }
    LDNote(stringResource(R.string.ld_home_pair_note))
}

@Composable
private fun rowStatus(row: HomeRow, home: HomeState, isLoaded: Boolean, now: Long): Pair<String, Color> {
    val colors = LDTheme.colors
    if (row.hasNoBroker) return stringResource(R.string.mqtt_enabled_no_broker) to colors.warning
    if (home == HomeState.NothingSelected) return stringResource(R.string.ld_home_connected) to colors.inactive
    val delivery = row.delivery
        // Blank for the moment the log takes to read, so a row never says something untrue.
        ?: return (if (isLoaded) stringResource(R.string.ld_home_no_deliveries) else " ") to colors.inactive
    val time = shortTimeAgo(delivery.timestamp, now)
    return when {
        delivery.success -> time to colors.accentTextSoft
        delivery.statusCode != null -> stringResource(R.string.ld_home_failed_http, delivery.statusCode, time) to colors.warning
        else -> stringResource(R.string.ld_home_failed_time, time) to colors.warning
    }
}

/** "4 min ago", or the words for under a minute, in the phone's language. */
@Composable
private fun shortTimeAgo(timestamp: Long, now: Long): String =
    if (now - timestamp < DateUtils.MINUTE_IN_MILLIS) {
        stringResource(R.string.ld_just_now)
    } else {
        DateUtils.getRelativeTimeSpanString(timestamp, now, DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE).toString()
    }

private fun formatDay(millis: Long, zone: ZoneId): String =
    DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).format(Instant.ofEpochMilli(millis).atZone(zone))

/** Add destination and Sync Now, under each other at the largest font sizes, where they no longer fit. */
@Composable
private fun AddAndSync(state: HealthUiState, showSync: Boolean, viewModel: HealthConnectViewModel) {
    val navigator = LocalLDNavigator.current
    val add: @Composable () -> Unit = {
        LDTextButton(stringResource(R.string.ld_title_add_destination)) { navigator.push(LDRoute.AddDestination(LDSection.Health)) }
    }
    val sync: @Composable () -> Unit = {
        if (showSync) {
            LDButton(
                stringResource(if (state.isSyncBusy) R.string.sync_syncing else R.string.sync_now),
                size = LDButtonSize.Small,
                fullWidth = false,
                enabled = state.canSync,
                busy = state.isSyncBusy,
                onClick = viewModel::syncNow
            )
        }
    }
    // Add destination's own edge of 4 lines its text up with the rows.
    val edge = Modifier.fillMaxWidth().padding(start = LDTheme.textInset - 4.dp, end = LDTheme.textInset, top = 4.dp)
    if (ldLargeText()) {
        Column(edge, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            add()
            sync()
        }
    } else {
        Row(edge, horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            add()
            sync()
        }
    }
}

/**
 * The backfill is a WorkManager job (P2-14): this line follows it after the screen was left and
 * opened again, opens its page, and Stop cancels it.
 */
@Composable
private fun BackfillLine(state: HealthUiState, viewModel: HealthConnectViewModel) {
    val progress = state.backfillProgress ?: return
    val navigator = LocalLDNavigator.current
    val (done, total) = progress
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 4.dp)
            .heightIn(min = 48.dp)
            .padding(start = LDTheme.textInset, end = LDTheme.textInset - 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            when {
                state.backfillWaiting -> stringResource(R.string.health_backfill_waiting)
                state.backfillPaused -> stringResource(R.string.health_backfill_paused, done, total)
                state.backfillRestarted -> stringResource(R.string.health_backfill_restarted, done, total)
                else -> stringResource(R.string.health_backfill_progress, done, total)
            },
            style = LDType.note,
            color = LDTheme.colors.accentTextSoft,
            modifier = Modifier
                .weight(1f)
                .clickable(role = Role.Button) { navigator.push(LDRoute.Backfill(LDBackfillStage.Running)) }
                .semantics(mergeDescendants = true) { }
        )
        LDTextButton(stringResource(R.string.health_backfill_stop), onClick = viewModel::cancelBackfill)
    }
}

/**
 * Only while automatic syncs are held. The plain cadence is on the When to send tile and the
 * addresses are the rows, and one more line would push the tiles under the tab bar.
 */
@Composable
private fun ScheduleHoldLine(schedule: ScheduleDraft, now: Long) {
    val moment = LocalDateTime.ofInstant(Instant.ofEpochMilli(now), ZoneId.systemDefault())
    val text = when (val hold = SyncHold.of(schedule.toSchedule(), moment)) {
        null -> return
        SyncHold.Never -> stringResource(R.string.ld_home_hold_never)
        SyncHold.DayOff -> stringResource(R.string.ld_home_hold_day_off)
        is SyncHold.Quiet -> stringResource(R.string.ld_home_hold_quiet, formatTime(hold.until))
    }
    LDNote(text.ldKeepingSeparators, color = LDTheme.colors.muted, top = 12.dp, size = LDNoteSize.Small)
}

@Composable
private fun HomeTiles(state: HealthUiState) {
    val navigator = LocalLDNavigator.current
    LDTile(stringResource(R.string.ld_title_what_to_send), LDIcons.whatToSend, subtitle = whatSummary(state)) { navigator.push(LDRoute.WhatToSend) }
    LDTile(stringResource(R.string.ld_title_when_to_send), LDIcons.whenToSend, subtitle = whenSummary(state.saved.schedule)) { navigator.push(LDRoute.WhenToSend) }
    LDTile(stringResource(R.string.receive_title), LDIcons.receive, subtitle = receiveSummary(state)) { navigator.push(LDRoute.Receive) }
    MoreTile()
}

@Composable
private fun MoreTile() {
    val navigator = LocalLDNavigator.current
    LDTile(stringResource(R.string.ld_title_more), LDIcons.more, subtitle = stringResource(R.string.ld_home_more_subtitle)) { navigator.push(LDRoute.More) }
}

@Composable
private fun whatSummary(state: HealthUiState): String {
    val count = state.saved.enabledTypes.size
    return if (count == 0) stringResource(R.string.ld_home_none_yet)
    else stringResource(R.string.ld_home_types_of, count, HealthDataType.entries.size)
}

@Composable
private fun whenSummary(schedule: ScheduleDraft): String = when {
    schedule.mode == SyncMode.TIMES && schedule.times.isEmpty() -> stringResource(R.string.schedule_summary_no_times)
    schedule.wouldNeverRun() -> stringResource(R.string.receive_off)
    schedule.mode == SyncMode.INTERVAL -> stringResource(R.string.schedule_summary_interval, schedule.intervalText.toIntOrNull() ?: 0)
    schedule.times.distinct().size == 1 -> stringResource(R.string.ld_home_once_a_day)
    else -> schedule.times.distinct().size.let { pluralStringResource(R.plurals.ld_home_times_a_day, it, it) }
}

@Composable
private fun receiveSummary(state: HealthUiState): String =
    when (val summary = ReceiveSummary.of(state.receive, state.receiveStatus, state.grantedPermissions, state.receiveAvailable)) {
        ReceiveSummary.Off -> stringResource(R.string.receive_off)
        ReceiveSummary.NeedsPairing -> stringResource(R.string.receive_summary_needs_pairing)
        ReceiveSummary.IntegrationOutdated -> stringResource(R.string.receive_summary_update_integration)
        ReceiveSummary.AwaitingTypes -> stringResource(R.string.ld_home_receive_waiting)
        ReceiveSummary.NothingMapped -> stringResource(R.string.receive_summary_nothing_mapped)
        ReceiveSummary.ChooseType -> stringResource(R.string.ld_home_receive_choose)
        is ReceiveSummary.PermissionMissing -> stringResource(R.string.ld_home_receive_permission)
        is ReceiveSummary.Receiving -> {
            val names = summary.types.map { stringResource(it.dataType.displayNameRes) }
            if (names.size <= RECEIVE_NAMES_ON_TILE) names.joinToString(", ")
            else pluralStringResource(R.plurals.ld_home_receive_types, names.size, names.size)
        }
    }
