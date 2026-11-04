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

private enum class TimeTarget { NewSyncTime, QuietFrom, QuietTo }

/**
 * The health sync schedule: an interval or fixed times, a weekday filter and quiet hours. All of it
 * applies at once, apart from the interval: half a number must not reach the store, so that field
 * has a draft and Save, and Back discards it.
 */
@Composable
fun WhenToSendScreen() {
    val viewModel = ldHealthViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val resources = LocalResources.current
    val locale = LocalConfiguration.current.locales[0]
    val schedule = state.saved.schedule
    val apply: (ScheduleDraft) -> Unit = viewModel::setSchedule

    var interval by rememberLDDraft { schedule.intervalText }
    var picker by rememberSaveable { mutableStateOf<TimeTarget?>(null) }
    val intervalChanged = interval != schedule.intervalText
    // "060" is stored as 60: the field follows, so Save goes dim again.
    val saveInterval = { if (viewModel.saveInterval(interval) == null) interval = interval.toInt().toString() }

    LDScreen(
        title = stringResource(R.string.ld_title_when_to_send),
        actions = {
            if (schedule.mode == SyncMode.INTERVAL) {
                LDTopBarAction(stringResource(R.string.ld_save), enabled = intervalChanged && SettingsRules.intervalOrNull(interval) != null, onClick = saveInterval)
            }
        }
    ) {
        LDHeading(cadenceHeading(schedule))
        LDGap(14.dp)
        LDSegmented(
            selected = schedule.mode,
            segments = listOf(
                LDSegment(SyncMode.INTERVAL, stringResource(R.string.schedule_mode_interval)),
                LDSegment(SyncMode.TIMES, stringResource(R.string.ld_when_fixed_times))
            ),
            onSelect = { apply(schedule.copy(mode = it)) },
            label = stringResource(R.string.ld_when_mode)
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
                        error = UiMessage.IntervalTooShort.text(resources).takeIf { SettingsRules.intervalOrNull(interval) == null },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        onDone = saveInterval
                    )
                }
                LDNote(stringResource(R.string.ld_when_minimum, SettingsRules.MIN_INTERVAL_MINUTES), size = LDNoteSize.Small)
            }
            SyncMode.TIMES -> {
                LDSectionLabel(stringResource(R.string.schedule_times_title))
                val times = schedule.times.distinct().sorted()
                if (times.isEmpty()) {
                    LDNote(stringResource(R.string.schedule_no_times), top = 0.dp)
                } else {
                    LDChips {
                        times.forEach { time ->
                            LDRemovableChip(formatTime(time)) { apply(schedule.copy(times = times - time)) }
                        }
                    }
                }
                LDGap(8.dp)
                LDButton(stringResource(R.string.schedule_add_time), kind = LDButtonKind.Ghost, size = LDButtonSize.Medium) {
                    picker = TimeTarget.NewSyncTime
                }
            }
        }

        LDSectionLabel(stringResource(R.string.schedule_days_title))
        LDDayChips(
            DayOfWeek.entries.map { day ->
                val isOn = day in schedule.days
                LDDayChip(
                    letter = day.getDisplayName(TextStyle.NARROW, locale),
                    fullName = day.getDisplayName(TextStyle.FULL, locale),
                    selected = isOn,
                    onClick = { apply(schedule.copy(days = if (isOn) schedule.days - day else schedule.days + day)) }
                )
            }
        )

        LDSectionLabel(stringResource(R.string.ld_when_limits))
        LDCard {
            LDToggleRow(
                stringResource(R.string.schedule_quiet_title),
                checked = schedule.quietEnabled,
                onCheckedChange = { on ->
                    apply(
                        if (on) schedule.copy(quietFrom = LocalTime.of(23, 0), quietTo = LocalTime.of(7, 0))
                        else schedule.copy(quietFrom = null, quietTo = null)
                    )
                },
                subtitle = stringResource(R.string.schedule_quiet_subtitle)
            )
            schedule.quietWindow?.let { window ->
                LDRow(
                    stringResource(R.string.schedule_quiet_from),
                    value = formatTime(window.from),
                    valueColor = LDTheme.colors.accentTextSoft,
                    showChevron = false,
                    onClick = { picker = TimeTarget.QuietFrom }
                )
                LDRow(
                    stringResource(R.string.schedule_quiet_to),
                    value = formatTime(window.to),
                    valueColor = LDTheme.colors.accentTextSoft,
                    showChevron = false,
                    onClick = { picker = TimeTarget.QuietTo }
                )
            }
        }

        if (schedule.wouldNeverRun()) {
            LDBanner(stringResource(R.string.ld_when_never), message = stringResource(R.string.schedule_never_runs))
        }
    }

    picker?.let { target ->
        TimePickerDialog(
            initial = when (target) {
                TimeTarget.NewSyncTime -> LocalTime.of(8, 0)
                TimeTarget.QuietFrom -> schedule.quietFrom ?: LocalTime.of(23, 0)
                TimeTarget.QuietTo -> schedule.quietTo ?: LocalTime.of(7, 0)
            },
            onDismiss = { picker = null },
            onPicked = { time ->
                apply(
                    when (target) {
                        TimeTarget.NewSyncTime -> schedule.copy(times = (schedule.times + time).distinct().sorted())
                        TimeTarget.QuietFrom -> schedule.copy(quietFrom = time)
                        TimeTarget.QuietTo -> schedule.copy(quietTo = time)
                    }
                )
                picker = null
            },
            confirmLabel = stringResource(if (target == TimeTarget.NewSyncTime) R.string.common_add else R.string.common_set)
        )
    }
}

/** What the stored schedule does, in words: only the times outside the quiet window count, the others are skipped. */
@Composable
private fun cadenceHeading(schedule: ScheduleDraft): String {
    if (schedule.wouldNeverRun()) return stringResource(R.string.ld_when_never)
    return when (schedule.mode) {
        SyncMode.INTERVAL -> stringResource(R.string.ld_when_every, schedule.toSchedule().intervalMinutes)
        SyncMode.TIMES -> {
            val running = schedule.times.distinct().count { schedule.quietWindow?.contains(it) != true }
            pluralStringResource(R.plurals.ld_when_times, running, running)
        }
    }
}
