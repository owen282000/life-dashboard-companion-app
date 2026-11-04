package com.owen282000.lifedashboard.screens.ld

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.owen282000.lifedashboard.BackfillJob
import com.owen282000.lifedashboard.BackfillJobStore
import com.owen282000.lifedashboard.BackfillTexts
import com.owen282000.lifedashboard.HealthConnectManager
import com.owen282000.lifedashboard.PartialDelivery
import com.owen282000.lifedashboard.R
import com.owen282000.lifedashboard.screens.formatCount
import com.owen282000.lifedashboard.screens.text
import com.owen282000.lifedashboard.ui.ld.LDBackfillStage
import com.owen282000.lifedashboard.ui.ld.LDBanner
import com.owen282000.lifedashboard.ui.ld.LDBannerAction
import com.owen282000.lifedashboard.ui.ld.LDButton
import com.owen282000.lifedashboard.ui.ld.LDButtonKind
import com.owen282000.lifedashboard.ui.ld.LDCard
import com.owen282000.lifedashboard.ui.ld.LDGap
import com.owen282000.lifedashboard.ui.ld.LDHeading
import com.owen282000.lifedashboard.ui.ld.LDNote
import com.owen282000.lifedashboard.ui.ld.LDNoteSize
import com.owen282000.lifedashboard.ui.ld.LDProgressBar
import com.owen282000.lifedashboard.ui.ld.LDPulseVariant
import com.owen282000.lifedashboard.ui.ld.LDRoute
import com.owen282000.lifedashboard.ui.ld.LDRow
import com.owen282000.lifedashboard.ui.ld.LDScreen
import com.owen282000.lifedashboard.ui.ld.LDTheme
import com.owen282000.lifedashboard.ui.ld.LocalLDEntryId
import com.owen282000.lifedashboard.ui.ld.LocalLDNavigator
import com.owen282000.lifedashboard.ui.ld.PulseLine
import com.owen282000.lifedashboard.ui.ld.ldStaggerRise
import com.owen282000.lifedashboard.viewmodel.HealthConnectViewModel
import com.owen282000.lifedashboard.viewmodel.HealthUiState
import com.owen282000.lifedashboard.viewmodel.UiMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** How long the page waits for the work's first report before it says nothing is going on. */
private const val STARTING_MILLIS = 1500L

/** A year: the length the page starts on, unless a stopped backfill can continue. */
private const val DEFAULT_LENGTH_DAYS = 365

/** Choosing a length and following the backfill are two pages: Start replaces the first with the second. */
@Composable
fun BackfillScreen(stage: LDBackfillStage) {
    when (stage) {
        LDBackfillStage.Choose -> BackfillChoosePage()
        LDBackfillStage.Running -> BackfillRunPage()
    }
}

// ==================== Choose a length ====================

/**
 * History goes to webhooks only, so without one nothing can start. The oldest record is looked up
 * when the page opens and again after history access is granted, see
 * [HealthUiState.backfillEarliest]; until then every length is listed, and the fixed ones that
 * reach back past it are left out after.
 */
@Composable
private fun BackfillChoosePage() {
    val viewModel = ldHealthViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val navigator = LocalLDNavigator.current
    val hasWebhook = state.saved.webhook.urls.isNotEmpty()
    LaunchedEffect(hasWebhook) {
        if (hasWebhook) viewModel.openBackfillDialog()
    }
    DisposableEffect(Unit) {
        onDispose { viewModel.dismissBackfillDialog() }
    }
    LDScreen(title = stringResource(R.string.ld_title_backfill)) {
        LDHeading(stringResource(R.string.ld_backfill_heading))
        LDNote(stringResource(R.string.ld_backfill_intro), top = 6.dp)
        val progress = state.backfillProgress
        when {
            progress != null -> LDBanner(stringResource(R.string.health_backfill_progress, progress.first, progress.second)) {
                LDBannerAction(stringResource(R.string.ld_backfill_view)) {
                    navigator.replaceTop(LDRoute.Backfill(LDBackfillStage.Running))
                }
            }
            state.saved.enabledTypes.isEmpty() -> LDBanner(stringResource(R.string.health_backfill_failed_no_types))
            !hasWebhook -> LDBanner(stringResource(R.string.health_backfill_needs_webhook)) {
                LDBannerAction(stringResource(R.string.ld_title_add_destination)) {
                    navigator.push(LDRoute.AddDestination())
                }
            }
            else -> BackfillChoice(state, viewModel)
        }
    }
}

@Composable
private fun BackfillChoice(state: HealthUiState, viewModel: HealthConnectViewModel) {
    val navigator = LocalLDNavigator.current
    val resources = LocalResources.current
    val locale = LocalConfiguration.current.locales[0]
    // Without READ_HEALTH_DATA_HISTORY Health Connect only exposes the 30 days before the first
    // permission grant, so a longer backfill would silently return recent data only (#39).
    val hasHistoryAccess = HealthConnectManager.HISTORY_PERMISSION in state.grantedPermissions
    val now = remember { System.currentTimeMillis() }
    val earliest = state.backfillEarliest.takeIf { hasHistoryAccess }
    val stopped = state.stoppedBackfill
    val lengths = BackfillJob.lengthsFor(earliest, now, keep = stopped?.days)
    var picked by rememberSaveable { mutableStateOf<Int?>(null) }
    val chosen = listOfNotNull(picked, stopped?.days, DEFAULT_LENGTH_DAYS).firstOrNull { it in lengths } ?: BackfillJob.ALL_HISTORY

    if (stopped != null) {
        LDNote(stringResource(R.string.health_backfill_continues, BackfillTexts.length(resources, stopped.days), stopped.nextWindow, stopped.windowCount))
    }
    LDGap(12.dp)
    LDCard(Modifier.ldStaggerRise(0)) {
        lengths.forEach { days ->
            val isChosen = days == chosen
            val isAll = days == BackfillJob.ALL_HISTORY
            LDRow(
                BackfillTexts.length(resources, days),
                modifier = Modifier.semantics { selected = isChosen },
                subtitle = when {
                    !isAll -> null
                    earliest != null -> {
                        val since = DateTimeFormatter.ofPattern("LLLL yyyy", locale).format(Instant.ofEpochMilli(earliest).atZone(ZoneId.systemDefault()))
                        val windows = BackfillJob.allHistoryWindows(earliest, now)
                        pluralStringResource(R.plurals.health_backfill_length_all_since, windows, since, windows)
                    }
                    else -> stringResource(R.string.health_backfill_length_all_any)
                },
                value = if (isAll) {
                    stringResource(R.string.ld_backfill_everything)
                } else {
                    BackfillJob.windowsFor(days).let { pluralStringResource(R.plurals.ld_backfill_chunks, it, it) }
                },
                valueColor = if (isChosen) LDTheme.colors.accentTextSoft else LDTheme.colors.muted,
                showChevron = false,
                onClick = { picked = days }
            )
        }
    }
    LDNote(stringResource(R.string.ld_backfill_goes_to, PartialDelivery.hosts(state.saved.webhook.urls)))
    LDNote(stringResource(R.string.ld_backfill_mobile_data), top = 2.dp)
    // Until the integration takes in older days, say so before someone picks All.
    LDNote(stringResource(R.string.ld_backfill_integration_note), color = LDTheme.colors.warningText)
    if (!hasHistoryAccess) {
        LDNote(stringResource(R.string.health_backfill_history_permission_warning), color = LDTheme.colors.warningText)
        LDGap(4.dp)
        LDButton(stringResource(R.string.health_backfill_grant_history), kind = LDButtonKind.Ghost, onClick = viewModel::requestHistoryPermission)
    }
    LDGap(12.dp)
    LDButton(stringResource(R.string.ld_backfill_start, BackfillTexts.length(resources, chosen))) {
        viewModel.backfill(chosen)
        navigator.replaceTop(LDRoute.Backfill(LDBackfillStage.Running))
    }
}

// ==================== Running ====================

/**
 * Follows the WorkManager job, also after the app was left. Its reports say how far it got but not
 * what it sent, so the records come from the job as it is stored after each payload. The job stops
 * and goes, there is no pause in it: Android and the read quota pause it by themselves. A failed
 * job is kept, which is what Continue picks up.
 */
@Composable
private fun BackfillRunPage() {
    val viewModel = ldHealthViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val navigator = LocalLDNavigator.current
    val page = LocalLDEntryId.current
    val context = LocalContext.current
    val resources = LocalResources.current
    val progress = state.backfillProgress
    val failed = state.syncMessage as? UiMessage.BackfillFailed
    val complete = state.syncMessage as? UiMessage.BackfillComplete

    var attempt by remember { mutableIntStateOf(0) }
    var starting by remember { mutableStateOf(true) }
    LaunchedEffect(attempt) {
        starting = true
        delay(STARTING_MILLIS)
        starting = false
    }
    val readStopped = failed != null && state.saved.webhook.urls.isNotEmpty()
    LaunchedEffect(readStopped) {
        if (readStopped) viewModel.openBackfillDialog()
    }
    DisposableEffect(Unit) {
        onDispose { viewModel.dismissBackfillDialog() }
    }
    val records by produceState<Int?>(null, progress?.first, failed) {
        value = withContext(Dispatchers.IO) { BackfillJobStore.load(context)?.recordsSent }
    }

    LDScreen(title = stringResource(R.string.ld_title_backfill)) {
        when {
            progress != null -> {
                val (done, total) = progress
                LDHeading(
                    when {
                        state.backfillWaiting -> stringResource(R.string.ld_backfill_waiting_heading)
                        state.backfillPaused -> stringResource(R.string.ld_backfill_paused_heading, done, total)
                        else -> stringResource(R.string.ld_backfill_running_heading, done, total)
                    }
                )
                PulseLine(variant = if (state.backfillWaiting || state.backfillPaused) LDPulseVariant.Flat else LDPulseVariant.Normal)
                BackfillBar(done, total)
                LDNote(
                    records?.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.ld_backfill_records_leave, it, formatCount(it)) }
                        ?: stringResource(R.string.ld_backfill_can_leave),
                    top = 10.dp
                )
                when {
                    state.backfillWaiting -> LDNote(stringResource(R.string.health_backfill_waiting), top = 2.dp)
                    state.backfillPaused -> LDNote(stringResource(R.string.health_backfill_paused, done, total), top = 2.dp)
                    state.backfillRestarted -> LDNote(stringResource(R.string.health_backfill_restarted, done, total), top = 2.dp)
                }
                LDGap(16.dp)
                LDButton(stringResource(R.string.ld_backfill_stop_discard), kind = LDButtonKind.Danger) {
                    viewModel.cancelBackfill()
                    navigator.pop(page)
                }
                LDNote(stringResource(R.string.ld_backfill_stop_hint), size = LDNoteSize.Small)
            }
            failed != null -> {
                val stopped = state.stoppedBackfill
                LDHeading(stringResource(R.string.ld_backfill_failed_heading))
                PulseLine(variant = LDPulseVariant.Warning)
                if (stopped != null) BackfillBar(stopped.nextWindow, stopped.windowCount)
                LDNote(failed.text(resources), color = LDTheme.colors.warning, top = 10.dp)
                records?.takeIf { it > 0 }?.let {
                    LDNote(pluralStringResource(R.plurals.ld_backfill_records_sent, it, formatCount(it)), top = 2.dp)
                }
                LDGap(16.dp)
                if (stopped != null) {
                    LDButton(stringResource(R.string.ld_backfill_continue)) {
                        attempt++
                        viewModel.backfill(stopped.days)
                    }
                    LDGap(8.dp)
                }
                LDButton(stringResource(R.string.ld_backfill_choose_length), kind = LDButtonKind.Ghost) {
                    navigator.replaceTop(LDRoute.Backfill())
                }
            }
            complete != null -> {
                LDHeading(stringResource(R.string.ld_backfill_done_heading))
                PulseLine()
                BackfillBar(1, 1)
                LDNote(
                    pluralStringResource(R.plurals.ld_backfill_records_sent, complete.count, formatCount(complete.count)),
                    color = LDTheme.colors.accentTextSoft,
                    top = 10.dp
                )
            }
            starting -> {
                LDHeading(stringResource(R.string.ld_backfill_starting_heading))
                PulseLine(variant = LDPulseVariant.Flat)
                BackfillBar(0, 1)
                LDNote(stringResource(R.string.ld_backfill_can_leave), top = 10.dp)
            }
            // A job that finished or was stopped before this view model looked, or that never started.
            else -> {
                LDHeading(stringResource(R.string.ld_backfill_heading))
                LDGap(16.dp)
                LDButton(stringResource(R.string.ld_what_send_history)) {
                    navigator.replaceTop(LDRoute.Backfill())
                }
            }
        }
    }
}

@Composable
private fun BackfillBar(done: Int, total: Int) {
    val label = stringResource(R.string.ld_backfill_progress_a11y, done, total)
    LDProgressBar(done.toFloat() / total.coerceAtLeast(1), Modifier.semantics { contentDescription = label })
}
