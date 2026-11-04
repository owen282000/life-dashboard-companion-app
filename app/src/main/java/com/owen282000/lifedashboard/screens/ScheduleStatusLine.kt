package com.owen282000.lifedashboard.screens

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.owen282000.lifedashboard.R
import com.owen282000.lifedashboard.SyncMode
import com.owen282000.lifedashboard.ui.ld.LDTheme
import com.owen282000.lifedashboard.ui.ld.LDType
import com.owen282000.lifedashboard.viewmodel.ScheduleDraft
import java.time.DayOfWeek

/**
 * The line on the Screen Time tab that says when it syncs and where it sends.
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
    Text(text, style = LDType.rowSubtitle, color = LDTheme.colors.muted, modifier = modifier)
}

/** The cadence, then the filters that are on. */
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
