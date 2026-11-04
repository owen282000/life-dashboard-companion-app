package com.owen282000.lifedashboard.screens.ld

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.owen282000.lifedashboard.R
import com.owen282000.lifedashboard.TodayKind
import com.owen282000.lifedashboard.TodayStat
import com.owen282000.lifedashboard.TodayState
import com.owen282000.lifedashboard.screens.formatCount
import com.owen282000.lifedashboard.ui.ld.LDButton
import com.owen282000.lifedashboard.ui.ld.LDGap
import com.owen282000.lifedashboard.ui.ld.LDRoute
import com.owen282000.lifedashboard.ui.ld.LDTheme
import com.owen282000.lifedashboard.ui.ld.LDType
import com.owen282000.lifedashboard.ui.ld.LocalLDNavigator
import com.owen282000.lifedashboard.ui.ld.SparkLine
import com.owen282000.lifedashboard.ui.ld.ldLargeText
import com.owen282000.lifedashboard.ui.ld.ldStaggerRise
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.roundToInt

private const val POUNDS_PER_KILOGRAM = 2.20462

/**
 * What the user sends, at a glance: steps, sleep, resting heart rate and weight, as far as those are
 * selected and have data. One figure is drawn large; none means the records sent today; no types at
 * all, the way to pick them. Part of Home's scrolling, so it has no LDScreen of its own. [firstIndex]
 * is where its figures join the stagger of the screen.
 */
@Composable
fun TodayPanel(today: TodayState, nothingSelected: Boolean, firstIndex: Int = 0) {
    val navigator = LocalLDNavigator.current
    val open = { navigator.push(LDRoute.WhatToSend) }
    if (nothingSelected) {
        LDGap(10.dp)
        LDButton(stringResource(R.string.ld_today_pick), onClick = open)
        return
    }
    if (!today.hasLoaded) return
    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = LDTheme.textInset, end = LDTheme.textInset, top = 12.dp)
    ) {
        Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.dashboard_today),
                style = LDType.sectionLabel,
                color = LDTheme.colors.muted,
                modifier = Modifier.semantics { heading() }
            )
            Spacer(Modifier.weight(1f).widthIn(min = 8.dp))
            if (today.stats.isNotEmpty()) {
                Text(stringResource(R.string.ld_today_lines), style = LDType.sans(12.sp), color = LDTheme.colors.muted)
            }
        }
        when (today.stats.size) {
            0 -> RecordsToday(today.recordsToday, firstIndex, open)
            1 -> LargeStat(today.stats.single(), firstIndex, open)
            else -> StatGrid(today.stats, firstIndex, open)
        }
    }
}

@Composable
private fun StatGrid(stats: List<TodayStat>, firstIndex: Int, onClick: () -> Unit) {
    val columns = if (ldLargeText()) 1 else 2
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        stats.chunked(columns).forEachIndexed { rowIndex, row ->
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                row.forEachIndexed { cellIndex, stat ->
                    StatFigure(
                        stat,
                        valueSize = 26.sp,
                        valueLine = 28.sp,
                        tracking = (-0.4).sp,
                        sparkHeight = 20.dp,
                        sparkTop = 4.dp,
                        onClick = onClick,
                        modifier = Modifier.weight(1f).ldStaggerRise(firstIndex + rowIndex * columns + cellIndex)
                    )
                }
                if (columns == 2 && row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun LargeStat(stat: TodayStat, firstIndex: Int, onClick: () -> Unit) {
    StatFigure(
        stat,
        valueSize = 44.sp,
        valueLine = 48.sp,
        tracking = (-0.6).sp,
        sparkHeight = 44.dp,
        sparkTop = 8.dp,
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().ldStaggerRise(firstIndex)
    )
}

@Composable
private fun StatFigure(
    stat: TodayStat,
    valueSize: TextUnit,
    valueLine: TextUnit,
    tracking: TextUnit,
    sparkHeight: Dp,
    sparkTop: Dp,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val figure = formatFigure(stat, stat.latest.value)
    val caption = caption(stat.kind)
    val spokenValue = if (stat.kind == TodayKind.WEIGHT) stringResource(R.string.ld_today_weight_value, figure, caption) else figure
    // The line in words, which TalkBack cannot read off the drawing; "kg" alone says nothing without the panel.
    val spoken = stringResource(
        R.string.ld_today_spoken,
        stringResource(stat.kind.dataType.displayNameRes),
        spokenValue,
        stringResource(
            R.string.ld_today_trend,
            formatFigure(stat, stat.values.min()),
            formatFigure(stat, stat.values.max()),
            figure
        )
    )
    Column(
        modifier
            .clickable(role = Role.Button, onClick = onClick)
            .clearAndSetSemantics { contentDescription = spoken }
    ) {
        // At full width: laid out at its own measured width, a tightened Arabic-Indic number lost its last digit to a next line.
        Text(figure, Modifier.fillMaxWidth(), style = LDType.sans(valueSize, FontWeight.Bold, valueLine, tracking), color = LDTheme.colors.text)
        Text(caption, style = LDType.rowSubtitle, color = LDTheme.colors.muted)
        SparkLine(stat.values, Modifier.padding(top = sparkTop), height = sparkHeight)
    }
}

@Composable
private fun RecordsToday(count: Int, firstIndex: Int, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .ldStaggerRise(firstIndex)
    ) {
        Text(formatCount(count), Modifier.fillMaxWidth(), style = LDType.sans(44.sp, FontWeight.Bold, 48.sp, (-0.6).sp), color = LDTheme.colors.text)
        Text(stringResource(if (count == 1) R.string.ld_today_record else R.string.dashboard_records), style = LDType.rowSubtitle, color = LDTheme.colors.muted)
    }
}

@Composable
private fun caption(kind: TodayKind): String = stringResource(
    when (kind) {
        TodayKind.STEPS -> R.string.data_type_steps
        TodayKind.SLEEP -> R.string.data_type_sleep
        TodayKind.RESTING_HEART_RATE -> R.string.ld_today_rest_bpm
        TodayKind.WEIGHT -> if (weighsInPounds()) R.string.ld_today_lb else R.string.ld_today_kg
    }
)

@Composable
private fun formatFigure(stat: TodayStat, value: Double): String = when (stat.kind) {
    TodayKind.STEPS, TodayKind.RESTING_HEART_RATE -> formatCount(value.roundToInt())
    TodayKind.SLEEP -> {
        val minutes = value.roundToInt()
        // Both parts through the formatter, so an Arabic phone gets them in the same digits.
        val rest = NumberFormat.getIntegerInstance().apply { minimumIntegerDigits = 2 }.format(minutes % 60)
        stringResource(R.string.ld_today_sleep_value, formatCount(minutes / 60), rest)
    }
    TodayKind.WEIGHT -> {
        val shown = if (weighsInPounds()) value * POUNDS_PER_KILOGRAM else value
        NumberFormat.getNumberInstance().apply {
            minimumFractionDigits = 1
            maximumFractionDigits = 1
        }.format(shown)
    }
}

/** The countries of ICU's US measurement system. LocaleData has it only from Android 9, and the app starts at 8. */
private fun weighsInPounds(): Boolean = Locale.getDefault().country in setOf("US", "LR", "MM")
