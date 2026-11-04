package com.owen282000.lifedashboard.screens.ld

import android.content.Context
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.owen282000.lifedashboard.HealthConnectManager
import com.owen282000.lifedashboard.R
import com.owen282000.lifedashboard.SourceUrlChoice
import com.owen282000.lifedashboard.WriteBackPayload
import com.owen282000.lifedashboard.ui.ld.LDChevron
import com.owen282000.lifedashboard.ui.ld.LDHeading
import com.owen282000.lifedashboard.ui.ld.LDIcons
import com.owen282000.lifedashboard.ui.ld.LDNote
import com.owen282000.lifedashboard.ui.ld.LDRoute
import com.owen282000.lifedashboard.ui.ld.LDScreen
import com.owen282000.lifedashboard.ui.ld.LDSection
import com.owen282000.lifedashboard.ui.ld.LDTheme
import com.owen282000.lifedashboard.ui.ld.LDTile
import com.owen282000.lifedashboard.ui.ld.LDTileGrid
import com.owen282000.lifedashboard.ui.ld.LDType
import com.owen282000.lifedashboard.ui.ld.LocalLDNavigator
import com.owen282000.lifedashboard.ui.ld.ldLargeText
import com.owen282000.lifedashboard.ui.ld.ldLeftToRight
import com.owen282000.lifedashboard.ui.ld.ldStaggerRise
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

private const val HOME_ASSISTANT = "Home Assistant"
private const val LIFE_DASHBOARD_STACK = "Life Dashboard Stack"
private const val TAPS_TO_BEAT = 7

@Composable
fun AboutHomeScreen() {
    val navigator = LocalLDNavigator.current
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val state by ldHealthViewModel().state.collectAsStateWithLifecycle()
    val version = ldVersionName()

    // Easter eggs: tap the line 7 times to make it beat, long-press the version for the stats.
    var taps by remember { mutableIntStateOf(0) }
    var isBeating by remember { mutableStateOf(false) }
    var bpm by remember { mutableLongStateOf(72L) }
    val heartScale = remember { Animatable(1f) }
    val moves = LDTheme.motionEnabled

    LifecycleResumeEffect(Unit) {
        onPauseOrDispose { isBeating = false }
    }
    LaunchedEffect(isBeating) {
        if (!isBeating) {
            heartScale.snapTo(1f)
            return@LaunchedEffect
        }
        // The heart beats at your pace: the most recent heart rate measurement.
        latestHeartRate(context)?.let { bpm = it.coerceIn(30, 200) }
        // With the animator scale at 0 the heart stays still; the haptics still beat.
        suspend fun to(target: Float, millis: Int) {
            if (moves) heartScale.animateTo(target, tween(millis)) else delay(millis.toLong())
        }
        while (true) {
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            to(1.18f, 120)
            to(1f, 110)
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            to(1.10f, 100)
            to(1f, 100)
            delay((60_000L / bpm - 430).coerceAtLeast(50))
        }
    }

    // The first address that is Home Assistant's own, decided by the address as Receive does.
    val homeAssistant = state.saved.webhook.destinations.firstOrNull { WriteBackPayload.sourceUrlChoice(listOf(it.url)) != SourceUrlChoice.None }

    LDScreen(
        title = stringResource(R.string.about_title),
        pinned = {
            LDTileGrid {
                LDTile(stringResource(R.string.ld_title_help), LDIcons.help, subtitle = stringResource(R.string.ld_about_help_sub)) {
                    navigator.push(LDRoute.AboutHelp)
                }
                LDTile(stringResource(R.string.ld_title_support), LDIcons.support, subtitle = stringResource(R.string.ld_about_support_sub)) {
                    navigator.push(LDRoute.AboutSupport)
                }
                LDTile(stringResource(R.string.ld_title_privacy), LDIcons.privacy, subtitle = stringResource(R.string.ld_about_privacy_sub)) {
                    navigator.push(LDRoute.AboutPrivacy)
                }
                LDTile(stringResource(R.string.ld_title_open_source), LDIcons.openSource, subtitle = stringResource(R.string.ld_about_source_sub)) {
                    navigator.push(LDRoute.AboutOpenSource)
                }
            }
        }
    ) {
        LDHeading(stringResource(R.string.ld_about_heading))
        FanWithRows(
            fanWidth = if (ldLargeText()) 96.dp else FanBoard.WIDTH.dp,
            fan = {
                AboutFan(isBeating, { heartScale.value }) {
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    taps += 1
                    if (taps >= TAPS_TO_BEAT) {
                        taps = 0
                        isBeating = !isBeating
                    }
                }
            }
        ) {
            FanRow(
                HOME_ASSISTANT,
                stringResource(if (homeAssistant != null) R.string.ld_about_connected else R.string.ld_about_pair_code),
                if (homeAssistant != null) LDTheme.colors.accentTextSoft else LDTheme.colors.muted,
                Modifier.ldStaggerRise(0)
            ) {
                if (homeAssistant != null) {
                    navigator.push(LDRoute.WebhookDestination(LDSection.Health, homeAssistant.id))
                } else {
                    navigator.push(LDRoute.AddDestination(LDSection.Health))
                }
            }
            FanRow(LIFE_DASHBOARD_STACK, stringResource(R.string.ld_about_set_up_stack), LDTheme.colors.accentTextSoft, Modifier.ldStaggerRise(1)) {
                context.openLink(AboutLinks.STACK)
            }
            FanRow(stringResource(R.string.ld_about_own_server), stringResource(R.string.ld_about_own_server_sub), LDTheme.colors.accentTextSoft, Modifier.ldStaggerRise(2)) {
                navigator.push(LDRoute.AddDestination(LDSection.Health))
            }
        }
        LDNote(stringResource(R.string.ld_about_no_cloud))
        VersionLine(version, if (isBeating) bpm else null) {
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            navigator.push(LDRoute.AboutNerd)
        }
    }
}

/** Null when there is none, or no Health Connect to ask: then it beats at the resting pace it started with. */
private suspend fun latestHeartRate(context: Context): Long? = try {
    HealthConnectManager(context).latestHeartRateBpm()
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    null
}

/** 48 high for the long press. The isolates keep the version one left-to-right run inside a right-to-left sentence. */
@Composable
private fun VersionLine(version: String, bpm: Long?, onOpenNerdStats: () -> Unit) {
    val action = stringResource(R.string.ld_about_show_nerd)
    Box(
        Modifier
            .padding(top = 4.dp)
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .pointerInput(Unit) { detectTapGestures(onLongPress = { onOpenNerdStats() }) }
            .semantics(mergeDescendants = true) {
                onLongClick(label = action) {
                    onOpenNerdStats()
                    true
                }
            }
            .padding(horizontal = LDTheme.textInset),
        contentAlignment = Alignment.CenterStart
    ) {
        if (bpm != null) {
            Text(stringResource(R.string.about_bpm, bpm), style = LDType.mono(13.sp, bold = true), color = LDTheme.colors.accentTextSoft)
        } else {
            Text(stringResource(R.string.ld_about_version_line, version.ldLeftToRight), style = LDType.noteSmall, color = LDTheme.colors.muted)
        }
    }
}

/**
 * The fan beside its rows. The rows all take the height of the tallest, at least the board's 64, so
 * the fan's branches end on their centres also when larger text wraps one of them.
 */
@Composable
private fun FanWithRows(fanWidth: Dp, fan: @Composable () -> Unit, rows: @Composable () -> Unit) {
    Layout(
        content = {
            fan()
            rows()
        },
        modifier = Modifier.padding(top = 18.dp)
    ) { measurables, constraints ->
        val width = constraints.maxWidth
        val fanPixels = fanWidth.roundToPx()
        val rowWidth = (width - fanPixels - 14.dp.roundToPx()).coerceAtLeast(0)
        val rowMeasurables = measurables.drop(1)
        val rowHeight = rowMeasurables.maxOf { it.maxIntrinsicHeight(rowWidth) }.coerceAtLeast(64.dp.roundToPx())
        val fanPlaceable = measurables.first().measure(Constraints.fixed(fanPixels, rowHeight * rowMeasurables.size))
        val rowPlaceables = rowMeasurables.map { it.measure(Constraints.fixed(rowWidth, rowHeight)) }
        layout(width, rowHeight * rowMeasurables.size) {
            fanPlaceable.placeRelative(0, 0)
            rowPlaceables.forEachIndexed { index, row -> row.placeRelative(fanPixels, rowHeight * index) }
        }
    }
}

/** A row beside the fan: 15/20 bold over 13/18, with the chevron. */
@Composable
private fun FanRow(title: String, status: String, statusColor: Color, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Row(
        modifier
            .fillMaxSize()
            .clickable(role = Role.Button, onClick = onClick),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = LDType.sans(15.sp, FontWeight.Bold, 20.sp), color = LDTheme.colors.text)
            Text(status, style = LDType.rowSubtitle, color = statusColor)
        }
        LDChevron()
    }
}

/** The board's size and its fixed points: viewBox 176 by 192 with the hub at (110, 96). */
private object FanBoard {
    const val WIDTH = 176
    const val HUB_X = 110f
    const val MIDDLE = 96f
    const val TIP_X = 166f
    val beat = listOf(36f to 96f, 44f to 76f, 56f to 120f, 66f to 90f, 71f to 96f, 104f to 96f)
}

/**
 * The heartbeat that branches to the three rows, drawn in on appear: first the beat, then the
 * branches. Scaled by the width it gets; the branch ends follow the height. Mirrored as a whole with
 * the reading direction, so it still runs towards the rows. Taps count for the easter egg only, so
 * TalkBack skips it.
 */
@Composable
private fun AboutFan(isBeating: Boolean, heartScale: () -> Float, onTap: () -> Unit) {
    val colors = LDTheme.colors
    val moves = LDTheme.motionEnabled
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val progress = remember { Animatable(if (moves) 0f else 1f) }
    LaunchedEffect(moves) {
        if (moves) progress.animateTo(1f, tween(1200, easing = EaseInOut)) else progress.snapTo(1f)
    }
    Box(
        Modifier
            .clearAndSetSemantics { }
            .pointerInput(Unit) { detectTapGestures { onTap() } }
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val shown = progress.value
            val unit = size.width / FanBoard.WIDTH
            val middle = size.height / 2
            fun at(x: Float, y: Float) = Offset(x * unit, middle + (y - FanBoard.MIDDLE) * unit)
            val hub = at(FanBoard.HUB_X, FanBoard.MIDDLE)
            scale(if (rtl) -1f else 1f, 1f, pivot = center) {
                val beat = Path().apply {
                    at(0f, FanBoard.MIDDLE).let { moveTo(it.x, it.y) }
                    FanBoard.beat.forEach { (x, y) -> at(x, y).let { lineTo(it.x, it.y) } }
                }
                trimmed(beat, part(shown, 0f, 0.5f), colors.accent, 4 * unit)
                listOf(1f / 6 to colors.accent, 3f / 6 to colors.accent, 5f / 6 to colors.accentDim).forEach { (fraction, color) ->
                    val tip = Offset(FanBoard.TIP_X * unit, size.height * fraction)
                    val branch = Path().apply {
                        moveTo(hub.x, hub.y)
                        cubicTo(138 * unit, hub.y, 134 * unit, tip.y, tip.x, tip.y)
                    }
                    trimmed(branch, part(shown, 0.54f, 0.9f), color, 3 * unit)
                    drawCircle(color, 4 * unit * part(shown, 0.88f, 1f), tip)
                }
                drawCircle(colors.accent, 7 * unit * part(shown, 0.48f, 0.56f), hub)
            }
        }
        if (isBeating) {
            Icon(
                Icons.Filled.Favorite,
                contentDescription = null,
                tint = colors.accent,
                modifier = Modifier
                    .align(BiasAlignment(2 * FanBoard.HUB_X / FanBoard.WIDTH - 1, 0f))
                    .size(30.dp)
                    .graphicsLayer {
                        scaleX = heartScale()
                        scaleY = heartScale()
                    }
            )
        }
    }
}

private fun part(progress: Float, from: Float, to: Float) = ((progress - from) / (to - from)).coerceIn(0f, 1f)

private fun DrawScope.trimmed(path: Path, fraction: Float, color: Color, width: Float) {
    if (fraction <= 0f) return
    val measure = PathMeasure().apply { setPath(path, false) }
    val shown = Path()
    measure.getSegment(0f, measure.length * fraction, shown, true)
    drawPath(shown, color, style = Stroke(width = width, cap = StrokeCap.Round, join = StrokeJoin.Round))
}
