package com.owen282000.lifedashboard.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.owen282000.lifedashboard.R
import com.owen282000.lifedashboard.SyncMode
import com.owen282000.lifedashboard.viewmodel.ScheduleDraft
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * The sync schedule of one tab: interval or fixed times, an optional weekday filter and
 * optional quiet hours. Replaces the old single "Sync Interval" row; the interval field is
 * still the first thing in it, so an install that never opens this row behaves as before.
 */
@Composable
fun ScheduleRow(
    accent: Color,
    schedule: ScheduleDraft,
    expanded: Boolean,
    onToggle: () -> Unit,
    onChange: (ScheduleDraft) -> Unit
) {
    var picker by remember { mutableStateOf<TimeTarget?>(null) }

    ExpandableRow(
        icon = Icons.Outlined.Schedule,
        accent = accent,
        title = stringResource(R.string.schedule_title),
        subtitle = scheduleSummary(schedule),
        expanded = expanded,
        onToggle = onToggle
    ) {
        SegmentedFilter(
            options = listOf(
                stringResource(R.string.schedule_mode_interval),
                stringResource(R.string.schedule_mode_times)
            ),
            selectedIndex = if (schedule.mode == SyncMode.INTERVAL) 0 else 1,
            onSelect = { index ->
                onChange(schedule.copy(mode = if (index == 0) SyncMode.INTERVAL else SyncMode.TIMES))
            }
        )

        when (schedule.mode) {
            SyncMode.INTERVAL -> IntervalField(accent, schedule, onChange)
            SyncMode.TIMES -> TimesList(accent, schedule, onChange, onPick = { picker = TimeTarget.NewSyncTime })
        }

        GroupDivider()
        DayPicker(accent, schedule, onChange)

        GroupDivider()
        SwitchLine(
            title = stringResource(R.string.schedule_quiet_title),
            description = stringResource(R.string.schedule_quiet_subtitle),
            checked = schedule.quietEnabled,
            accent = accent,
            onCheckedChange = { on ->
                onChange(
                    if (on) schedule.copy(quietFrom = LocalTime.of(23, 0), quietTo = LocalTime.of(7, 0))
                    else schedule.copy(quietFrom = null, quietTo = null)
                )
            }
        )
        if (schedule.quietEnabled) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                TimeChip(
                    label = stringResource(R.string.schedule_quiet_from),
                    time = schedule.quietFrom,
                    accent = accent,
                    onClick = { picker = TimeTarget.QuietFrom }
                )
                TimeChip(
                    label = stringResource(R.string.schedule_quiet_to),
                    time = schedule.quietTo,
                    accent = accent,
                    onClick = { picker = TimeTarget.QuietTo }
                )
            }
        }

        if (schedule.wouldNeverRun()) {
            Text(
                stringResource(R.string.schedule_never_runs),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
    }

    picker?.let { target ->
        val initial = when (target) {
            TimeTarget.NewSyncTime -> LocalTime.of(8, 0)
            TimeTarget.QuietFrom -> schedule.quietFrom ?: LocalTime.of(23, 0)
            TimeTarget.QuietTo -> schedule.quietTo ?: LocalTime.of(7, 0)
        }
        TimePickerDialog(
            initial = initial,
            onDismiss = { picker = null },
            onPicked = { time ->
                onChange(
                    when (target) {
                        TimeTarget.NewSyncTime -> schedule.copy(times = (schedule.times + time).distinct().sorted())
                        TimeTarget.QuietFrom -> schedule.copy(quietFrom = time)
                        TimeTarget.QuietTo -> schedule.copy(quietTo = time)
                    }
                )
                picker = null
            }
        )
    }
}

private enum class TimeTarget { NewSyncTime, QuietFrom, QuietTo }

@Composable
private fun IntervalField(accent: Color, schedule: ScheduleDraft, onChange: (ScheduleDraft) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.sync_interval_title), style = MaterialTheme.typography.bodyMedium)
            Text(
                stringResource(R.string.sync_interval_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        FilledField(
            value = schedule.intervalText,
            onValueChange = { onChange(schedule.copy(intervalText = it)) },
            accent = accent,
            keyboardType = KeyboardType.Number,
            modifier = Modifier.width(88.dp)
        )
    }
}

@Composable
private fun TimesList(
    accent: Color,
    schedule: ScheduleDraft,
    onChange: (ScheduleDraft) -> Unit,
    onPick: () -> Unit
) {
    Text(stringResource(R.string.schedule_times_title), style = MaterialTheme.typography.bodyMedium)
    if (schedule.times.isEmpty()) {
        Text(
            stringResource(R.string.schedule_no_times),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        schedule.times.forEach { time ->
            Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                Row(
                    modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(formatTime(time), fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    TextButton(
                        onClick = { onChange(schedule.copy(times = schedule.times - time)) },
                        contentPadding = PaddingValues(4.dp)
                    ) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = stringResource(R.string.schedule_remove_time, formatTime(time)),
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
        Surface(onClick = onPick, shape = RoundedCornerShape(10.dp), color = accent.copy(alpha = 0.14f)) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp), tint = accent)
                Spacer(modifier = Modifier.width(6.dp))
                Text(stringResource(R.string.schedule_add_time), fontSize = 14.sp, color = accent, fontWeight = FontWeight.Medium)
            }
        }
    }
}

@Composable
private fun DayPicker(accent: Color, schedule: ScheduleDraft, onChange: (ScheduleDraft) -> Unit) {
    Text(stringResource(R.string.schedule_days_title), style = MaterialTheme.typography.bodyMedium)
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
        DayOfWeek.entries.forEach { day ->
            val selected = day in schedule.days
            Surface(
                onClick = {
                    val days = if (selected) schedule.days - day else schedule.days + day
                    onChange(schedule.copy(days = days))
                },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(9.dp),
                color = if (selected) accent.copy(alpha = 0.18f) else MaterialTheme.colorScheme.surfaceVariant
            ) {
                Box(modifier = Modifier.padding(vertical = 9.dp), contentAlignment = Alignment.Center) {
                    Text(
                        dayLabel(day),
                        fontSize = 12.sp,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (selected) accent else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/** A tappable time, shown as its own small card. Shared with the Screen Time day boundary. */
@Composable
fun TimeChip(label: String, time: LocalTime?, accent: Color, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(time?.let { formatTime(it) } ?: "--:--", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = accent)
        }
    }
}

/**
 * The clock dialog, in the device's own 12 or 24 hour format. [confirmLabel] differs by caller:
 * adding a sync time reads as "Add", choosing the day boundary reads as "Set".
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun TimePickerDialog(
    initial: LocalTime,
    onDismiss: () -> Unit,
    onPicked: (LocalTime) -> Unit,
    confirmLabel: String = stringResource(R.string.common_add)
) {
    val is24Hour = android.text.format.DateFormat.is24HourFormat(LocalContext.current)
    val state = rememberTimePickerState(initialHour = initial.hour, initialMinute = initial.minute, is24Hour = is24Hour)
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { onPicked(LocalTime.of(state.hour, state.minute)) }) {
                Text(confirmLabel)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
        text = { TimePicker(state = state) }
    )
}

/**
 * The status line under the save button: when this tab syncs and where it sends. Replaces the
 * old "Syncing every X min to N webhooks", which assumed an interval and ignored MQTT.
 */
@Composable
fun ScheduleStatusLine(schedule: ScheduleDraft, webhookCount: Int, mqttEnabled: Boolean, modifier: Modifier = Modifier) {
    val cadence = scheduleSummary(schedule)
    val text = when {
        webhookCount > 0 && mqttEnabled -> stringResource(
            if (webhookCount == 1) R.string.schedule_status_to_both else R.string.schedule_status_to_both_plural,
            cadence, webhookCount
        )
        webhookCount > 0 -> stringResource(
            if (webhookCount == 1) R.string.schedule_status_to_webhooks else R.string.schedule_status_to_webhooks_plural,
            cadence, webhookCount
        )
        mqttEnabled -> stringResource(R.string.schedule_status_to_mqtt, cadence)
        else -> stringResource(R.string.schedule_status_nowhere, cadence)
    }
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
    )
}

/** The one-line summary on the collapsed row: mode, then the filters that are on. */
@Composable
private fun scheduleSummary(schedule: ScheduleDraft): String {
    val parts = mutableListOf<String>()
    parts += when (schedule.mode) {
        SyncMode.INTERVAL -> stringResource(
            R.string.schedule_summary_interval,
            schedule.intervalText.toIntOrNull() ?: 0
        )
        SyncMode.TIMES -> if (schedule.times.isEmpty()) stringResource(R.string.schedule_summary_no_times)
        else stringResource(R.string.schedule_summary_times, schedule.times.joinToString(", ") { formatTime(it) })
    }
    if (schedule.days.size < DayOfWeek.entries.size) {
        // dayLabel is @Composable, so the labels are resolved first and joined afterwards.
        val labels = DayOfWeek.entries.filter { it in schedule.days }.map { dayLabel(it) }
        parts += labels.joinToString(" ")
    }
    schedule.quietWindow?.let {
        parts += stringResource(R.string.schedule_summary_quiet, formatTime(it.from), formatTime(it.to))
    }
    return parts.joinToString(" · ")
}

@Composable
private fun dayLabel(day: DayOfWeek): String = stringResource(
    when (day) {
        DayOfWeek.MONDAY -> R.string.schedule_day_mon
        DayOfWeek.TUESDAY -> R.string.schedule_day_tue
        DayOfWeek.WEDNESDAY -> R.string.schedule_day_wed
        DayOfWeek.THURSDAY -> R.string.schedule_day_thu
        DayOfWeek.FRIDAY -> R.string.schedule_day_fri
        DayOfWeek.SATURDAY -> R.string.schedule_day_sat
        DayOfWeek.SUNDAY -> R.string.schedule_day_sun
    }
)

private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)

/** A time as the locale writes it, so 16:00 and 4:00 PM both read naturally. */
fun formatTime(time: LocalTime): String = time.format(TIME_FORMAT)
