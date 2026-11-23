package com.owen282000.lifedashboard.screens.ld

import android.icu.text.MeasureFormat
import android.icu.util.Measure
import android.icu.util.MeasureUnit
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.owen282000.lifedashboard.BackgroundRun
import com.owen282000.lifedashboard.LogType
import com.owen282000.lifedashboard.R
import com.owen282000.lifedashboard.ui.ld.LDChevron
import com.owen282000.lifedashboard.ui.ld.LDHairline
import com.owen282000.lifedashboard.ui.ld.LDTheme
import com.owen282000.lifedashboard.ui.ld.LDType
import com.owen282000.lifedashboard.ui.ld.ldKeepingSeparators
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/**
 * Background runs in Logs, folded: every scheduled sync Android ran in the background, and what
 * came of it. The rows below show what was sent; this shows the runs that sent nothing, the ones
 * Android stopped, and whether Android ran the app at all. Shown also before anything was sent,
 * since an empty list is the answer to why nothing arrives.
 */
@Composable
internal fun BackgroundRunsSection(runs: List<BackgroundRun>, formats: LogFormats) {
    val colors = LDTheme.colors
    var expanded by rememberSaveable { mutableStateOf(false) }
    val clickLabel = stringResource(if (expanded) R.string.ld_logs_hide_details else R.string.ld_logs_show_details)
    // The chevron points along the reading direction, so a quarter turn down is the other way round right to left.
    val down = if (LocalLayoutDirection.current == LayoutDirection.Rtl) -90f else 90f

    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(role = Role.Button, onClickLabel = clickLabel) { expanded = !expanded }
                .semantics(mergeDescendants = true) { heading() }
                .heightIn(min = 48.dp)
                .padding(start = LDTheme.textInset, end = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                stringResource(R.string.ld_logs_runs_title),
                style = LDType.sectionLabel,
                color = colors.text,
                modifier = Modifier.weight(1f)
            )
            runs.firstOrNull()?.let { newest ->
                Text(runMoment(newest.last, formats), style = LDType.monoValue, color = colors.muted, maxLines = 1, softWrap = false)
            }
            LDChevron(Modifier.rotate(if (expanded) -down else down))
        }
        if (expanded) {
            Column(
                Modifier.fillMaxWidth().padding(start = LDTheme.textInset, end = LDTheme.textInset, bottom = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(stringResource(R.string.ld_logs_runs_note), style = LDType.noteSmall, color = colors.secondary)
                runs.forEach { BackgroundRunLine(it, formats) }
            }
        }
        LDHairline()
    }
}

/** Time, source, outcome and duration; a stop with its reason, a merged entry with its count. */
@Composable
private fun BackgroundRunLine(run: BackgroundRun, formats: LogFormats) {
    val colors = LDTheme.colors
    val stopped = run.outcomeType == BackgroundRun.Outcome.STOPPED
    val summary = listOfNotNull(
        runSource(run),
        runOutcome(run),
        run.durationSeconds?.let { runDuration(it) }
    ).joinToString(" · ")

    Row(
        Modifier.fillMaxWidth().semantics(mergeDescendants = true) { },
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(runMoment(run.last, formats), style = LDType.monoValue, color = colors.muted, maxLines = 1, softWrap = false)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                summary.ldKeepingSeparators,
                style = LDType.mono(12.sp, bold = true, lineHeight = 17.sp),
                color = if (stopped) colors.warningText else colors.text
            )
            if (stopped) {
                Text(stringResource(stopReasonLabel(run.stopReasonType)), style = LDType.mono(12.sp, lineHeight = 17.sp), color = colors.warningText)
            }
            if (run.count > 1) {
                Text(
                    pluralStringResource(R.plurals.ld_logs_runs_times_since, run.count, run.count, runMoment(run.first, formats)),
                    style = LDType.mono(12.sp, lineHeight = 17.sp),
                    color = colors.secondary
                )
            }
        }
    }
}

/** A time for today, as the rows of Logs show their day above them, and the day as well before that. */
private fun runMoment(millis: Long, formats: LogFormats): String {
    val zone = ZoneId.systemDefault()
    val today = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate() == LocalDate.now(zone)
    return if (today) formats.clock(millis) else formats.dateTime(millis)
}

/** A source this version does not know is shown as it was stored. */
@Composable
private fun runSource(run: BackgroundRun): String = when (run.sourceType) {
    LogType.HEALTH_CONNECT -> stringResource(R.string.logs_filter_health)
    LogType.SCREEN_TIME -> stringResource(R.string.logs_filter_screen_time)
    null -> run.source
}

@Composable
private fun runOutcome(run: BackgroundRun): String = when (run.outcomeType) {
    BackgroundRun.Outcome.SYNCED -> stringResource(R.string.ld_logs_runs_synced)
    BackgroundRun.Outcome.NOTHING_NEW -> stringResource(R.string.sync_no_new_data)
    BackgroundRun.Outcome.FAILED -> stringResource(R.string.logs_failed)
    BackgroundRun.Outcome.STOPPED -> stringResource(R.string.ld_logs_runs_stopped)
    null -> run.outcome
}

private fun stopReasonLabel(reason: BackgroundRun.StopReason?): Int = when (reason) {
    BackgroundRun.StopReason.BATTERY -> R.string.ld_logs_runs_reason_battery
    BackgroundRun.StopReason.RESTRICTED -> R.string.ld_logs_runs_reason_restricted
    BackgroundRun.StopReason.QUOTA -> R.string.ld_logs_runs_reason_quota
    BackgroundRun.StopReason.CONSTRAINTS -> R.string.ld_logs_runs_reason_constraints
    BackgroundRun.StopReason.TIME_LIMIT -> R.string.ld_logs_runs_reason_time_limit
    BackgroundRun.StopReason.OTHER, null -> R.string.ld_logs_runs_reason_other
}

/** "2.4s" or "1m 12s", in the short form each language has for it. */
@Composable
private fun runDuration(seconds: Double): String = formatRunDuration(seconds, LocalConfiguration.current.locales[0])

private fun formatRunDuration(seconds: Double, locale: Locale): String {
    val format = MeasureFormat.getInstance(locale, MeasureFormat.FormatWidth.NARROW)
    if (seconds < 10) return format.format(Measure(seconds, MeasureUnit.SECOND))
    val whole = Math.round(seconds)
    if (whole < 60) return format.format(Measure(whole, MeasureUnit.SECOND))
    return format.formatMeasures(Measure(whole / 60, MeasureUnit.MINUTE), Measure(whole % 60, MeasureUnit.SECOND))
}
