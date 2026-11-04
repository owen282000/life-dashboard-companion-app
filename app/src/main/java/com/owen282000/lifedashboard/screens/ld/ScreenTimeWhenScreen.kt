package com.owen282000.lifedashboard.screens.ld

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.owen282000.lifedashboard.R
import com.owen282000.lifedashboard.SyncMode
import com.owen282000.lifedashboard.screens.TimePickerDialog
import com.owen282000.lifedashboard.screens.formatTime
import com.owen282000.lifedashboard.screens.text
import com.owen282000.lifedashboard.ui.ld.LDBanner
import com.owen282000.lifedashboard.ui.ld.LDButton
import com.owen282000.lifedashboard.ui.ld.LDButtonKind
import com.owen282000.lifedashboard.ui.ld.LDButtonSize
import com.owen282000.lifedashboard.ui.ld.LDCard
import com.owen282000.lifedashboard.ui.ld.LDChips
import com.owen282000.lifedashboard.ui.ld.LDDayChip
import com.owen282000.lifedashboard.ui.ld.LDDayChips
import com.owen282000.lifedashboard.ui.ld.LDFieldRow
import com.owen282000.lifedashboard.ui.ld.LDGap
import com.owen282000.lifedashboard.ui.ld.LDHeading
import com.owen282000.lifedashboard.ui.ld.LDNote
import com.owen282000.lifedashboard.ui.ld.LDNoteSize
import com.owen282000.lifedashboard.ui.ld.LDRemovableChip
import com.owen282000.lifedashboard.ui.ld.LDRow
import com.owen282000.lifedashboard.ui.ld.LDScreen
import com.owen282000.lifedashboard.ui.ld.LDSectionLabel
import com.owen282000.lifedashboard.ui.ld.LDSegment
import com.owen282000.lifedashboard.ui.ld.LDSegmented
import com.owen282000.lifedashboard.ui.ld.LDTheme
import com.owen282000.lifedashboard.ui.ld.LDToggleRow
import com.owen282000.lifedashboard.ui.ld.LDTopBarAction
import com.owen282000.lifedashboard.ui.ld.rememberLDDraft
import com.owen282000.lifedashboard.viewmodel.ScheduleDraft
import com.owen282000.lifedashboard.viewmodel.SettingsRules
import com.owen282000.lifedashboard.viewmodel.UiMessage
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.format.TextStyle

private enum class ScreenTimeClock { NewTime, QuietFrom, QuietUntil }

/**
 * Screen Time's own schedule. Mode, times, days and quiet hours apply at once. The interval is typed,
 * so it waits for Save, and Back discards it.
 */
@Composable
fun ScreenTimeWhenScreen() {
    val viewModel = ldScreenTimeViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val resources = LocalResources.current
    val schedule = state.saved.schedule
    var interval by rememberLDDraft { schedule.intervalText }
    var clock by rememberSaveable { mutableStateOf<ScreenTimeClock?>(null) }
    val intervalOk = SettingsRules.intervalOrNull(interval) != null
    val apply = viewModel::setSchedule
    val saveInterval = {
        viewModel.saveInterval(interval)
        interval = viewModel.state.value.saved.schedule.intervalText
    }

    LDScreen(
        title = stringResource(R.string.main_title_screen_time),
        actions = {
            if (schedule.mode == SyncMode.INTERVAL) {
                LDTopBarAction(stringResource(R.string.ld_save), enabled = intervalOk && interval != schedule.intervalText, onClick = saveInterval)
            }
        }
    ) {
        LDHeading(screenTimeCadenceHeading(schedule))
        LDGap(14.dp)
        LDSegmented(
            schedule.mode,
            listOf(
                LDSegment(SyncMode.INTERVAL, stringResource(R.string.schedule_mode_interval)),
                LDSegment(SyncMode.TIMES, stringResource(R.string.ld_when_fixed_times))
            ),
            onSelect = { apply(schedule.copy(mode = it)) },
            label = stringResource(R.string.schedule_title)
        )
        when (schedule.mode) {
            SyncMode.INTERVAL -> {
                LDSectionLabel(stringResource(R.string.sync_interval_title))
                LDCard {
                    LDFieldRow(
                        stringResource(R.string.sync_interval_subtitle),
                        interval,
                        onValueChange = { interval = it },
                        isCode = false,
                        error = if (intervalOk) null else UiMessage.IntervalTooShort.text(resources),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        onDone = { if (intervalOk) saveInterval() }
                    )
                }
                LDNote(stringResource(R.string.ld_screen_when_note), size = LDNoteSize.Small)
            }
            SyncMode.TIMES -> {
                LDSectionLabel(stringResource(R.string.schedule_times_title))
                if (schedule.times.isEmpty()) {
                    LDNote(stringResource(R.string.schedule_no_times), top = 0.dp)
                } else {
                    LDChips {
                        schedule.times.forEach { time ->
                            LDRemovableChip(formatTime(time)) { apply(schedule.copy(times = schedule.times - time)) }
                        }
                    }
                }
                LDGap(8.dp)
                LDButton(stringResource(R.string.schedule_add_time), kind = LDButtonKind.Ghost, size = LDButtonSize.Medium) {
                    clock = ScreenTimeClock.NewTime
                }
                LDNote(stringResource(R.string.ld_screen_when_note), size = LDNoteSize.Small)
            }
        }
        LDSectionLabel(stringResource(R.string.schedule_days_title))
        val locale = LocalConfiguration.current.locales[0]
        LDDayChips(
            DayOfWeek.entries.map { day ->
                val selected = day in schedule.days
                LDDayChip(day.getDisplayName(TextStyle.NARROW, locale), day.getDisplayName(TextStyle.FULL, locale), selected) {
                    apply(schedule.copy(days = if (selected) schedule.days - day else schedule.days + day))
                }
            }
        )
        LDSectionLabel(stringResource(R.string.ld_screen_when_limits))
        LDCard {
            LDToggleRow(
                stringResource(R.string.schedule_quiet_title),
                schedule.quietEnabled,
                onCheckedChange = { on ->
                    apply(
                        if (on) {
                            schedule.copy(quietFrom = LocalTime.of(23, 0), quietTo = LocalTime.of(7, 0))
                        } else {
                            schedule.copy(quietFrom = null, quietTo = null)
                        }
                    )
                },
                subtitle = stringResource(R.string.schedule_quiet_subtitle)
            )
            val quiet = schedule.quietWindow
            if (quiet != null) {
                LDRow(
                    stringResource(R.string.schedule_quiet_from),
                    value = formatTime(quiet.from),
                    valueColor = LDTheme.colors.accentTextSoft,
                    showChevron = false,
                    onClick = { clock = ScreenTimeClock.QuietFrom }
                )
                LDRow(
                    stringResource(R.string.schedule_quiet_to),
                    value = formatTime(quiet.to),
                    valueColor = LDTheme.colors.accentTextSoft,
                    showChevron = false,
                    onClick = { clock = ScreenTimeClock.QuietUntil }
                )
            }
        }
        if (schedule.wouldNeverRun()) {
            LDBanner(stringResource(R.string.ld_screen_when_never), message = stringResource(R.string.schedule_never_runs))
        }
    }

    clock?.let { target ->
        TimePickerDialog(
            initial = when (target) {
                ScreenTimeClock.NewTime -> LocalTime.of(8, 0)
                ScreenTimeClock.QuietFrom -> schedule.quietFrom ?: LocalTime.of(23, 0)
                ScreenTimeClock.QuietUntil -> schedule.quietTo ?: LocalTime.of(7, 0)
            },
            onDismiss = { clock = null },
            onPicked = { time ->
                apply(
                    when (target) {
                        ScreenTimeClock.NewTime -> schedule.copy(times = (schedule.times + time).distinct().sorted())
                        ScreenTimeClock.QuietFrom -> schedule.copy(quietFrom = time)
                        ScreenTimeClock.QuietUntil -> schedule.copy(quietTo = time)
                    }
                )
                clock = null
            },
            confirmLabel = if (target == ScreenTimeClock.NewTime) stringResource(R.string.common_add) else stringResource(R.string.common_set)
        )
    }
}

/** What the schedule does, as the heading says it. Times inside the quiet hours are skipped, so they do not count. */
@Composable
private fun screenTimeCadenceHeading(schedule: ScheduleDraft): String {
    if (schedule.wouldNeverRun()) return stringResource(R.string.ld_screen_when_never)
    val quiet = schedule.quietWindow
    return when (schedule.mode) {
        SyncMode.INTERVAL -> {
            val minutes = schedule.intervalText.toIntOrNull() ?: 0
            when {
                minutes == 60 -> stringResource(R.string.ld_screen_when_every_hour)
                minutes > 0 && minutes % 60 == 0 -> pluralStringResource(R.plurals.ld_screen_when_every_hours, minutes / 60, minutes / 60)
                else -> pluralStringResource(R.plurals.ld_screen_when_every_minutes, minutes, minutes)
            }
        }
        SyncMode.TIMES -> {
            val running = schedule.times.distinct().count { quiet?.contains(it) != true }
            pluralStringResource(R.plurals.ld_screen_when_times_a_day, running, running)
        }
    }
}
