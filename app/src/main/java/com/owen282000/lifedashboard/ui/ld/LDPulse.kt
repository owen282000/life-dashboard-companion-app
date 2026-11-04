package com.owen282000.lifedashboard.ui.ld

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

/** The boards' heartbeat line in its 390 by 76 box. All of it is scaled by the width it gets. */
private object PulseGeometry {
    const val WIDTH = 390f
    const val HEIGHT = 76f
    const val STROKE = 4f
    private fun svg(path: String): Path = PathParser().parsePathString(path).toPath()

    val beat = svg("M0 42H136l11-26 17 52 15-38 7 12h40")
    val flat = svg("M0 42H226")
    val progress = svg("M0 42H136l11-26 17 52 15-38 7 12H380")
    val innerArc = svg("M250 28a20 20 0 0 1 0 28")
    val outerArc = svg("M266 18a36 36 0 0 1 0 48")
    val dot = Offset(232f, 42f)
    const val DOT_RADIUS = 7f
}

/** [path] drawn up to [fraction] of its length, in the box's own units. */
private fun DrawScope.trimmed(path: Path, fraction: Float, color: Color, width: Float) {
    if (fraction <= 0f) return
    val measure = PathMeasure().apply { setPath(path, false) }
    val part = Path()
    measure.getSegment(0f, measure.length * fraction.coerceAtMost(1f), part, true)
    drawPath(part, color, style = Stroke(width = width, cap = StrokeCap.Round, join = StrokeJoin.Round))
}

private fun window(progress: Float, from: Float, to: Float) = ((progress - from) / (to - from)).coerceIn(0f, 1f)

/** The line runs with the reading direction, as the widget's and the About fan's do; a Canvas does not flip by itself. */
private inline fun DrawScope.followingReadingDirection(draw: DrawScope.() -> Unit) {
    val mirrored = layoutDirection == LayoutDirection.Rtl
    withTransform({ if (mirrored) scale(-1f, 1f, pivot = center) }, draw)
}

private inline fun DrawScope.inPulseBox(draw: DrawScope.() -> Unit) {
    val scale = size.width / PulseGeometry.WIDTH
    followingReadingDirection {
        withTransform({ scale(scale, scale, pivot = Offset.Zero) }, draw)
    }
}

enum class LDPulseVariant {
    /** Everything is arriving. */
    Normal,

    /** A destination needs attention. */
    Warning,

    /** Nothing to send yet: no beat, in the dim colours. */
    Flat
}

/**
 * The heartbeat line with its dot and two arcs. It runs from the edge where reading starts, so it sits
 * directly in an LDScreen without a margin. Draws in when it appears or changes variant.
 */
@Composable
fun PulseLine(modifier: Modifier = Modifier, variant: LDPulseVariant = LDPulseVariant.Normal) {
    val colors = LDTheme.colors
    val (color, dim) = when (variant) {
        LDPulseVariant.Normal -> colors.accent to colors.accentDim
        LDPulseVariant.Warning -> colors.warning to colors.warningDim
        LDPulseVariant.Flat -> colors.flatLine to colors.flatDim
    }
    val moves = LDTheme.motionEnabled
    val progress = remember(variant) { Animatable(if (moves) 0f else 1f) }
    LaunchedEffect(variant, moves) {
        if (moves) progress.animateTo(1f, tween(800, easing = EaseOut)) else progress.snapTo(1f)
    }
    Canvas(modifier.fillMaxWidth().aspectRatio(PulseGeometry.WIDTH / PulseGeometry.HEIGHT)) {
        val shown = progress.value
        inPulseBox {
            trimmed(if (variant == LDPulseVariant.Flat) PulseGeometry.flat else PulseGeometry.beat, window(shown, 0f, 0.62f), color, PulseGeometry.STROKE)
            drawCircle(color, PulseGeometry.DOT_RADIUS * window(shown, 0.6f, 0.72f), PulseGeometry.dot)
            trimmed(PulseGeometry.innerArc, window(shown, 0.7f, 0.86f), color, PulseGeometry.STROKE)
            trimmed(PulseGeometry.outerArc, window(shown, 0.84f, 1f), dim, PulseGeometry.STROKE)
        }
    }
}

/**
 * The onboarding's progress: the beat in the dim colour, with the accent drawn over it up to this
 * step. It draws on from the previous step. "Step N of 5" has to say it in words next to the line.
 */
@Composable
fun PulseProgress(step: Int, of: Int, modifier: Modifier = Modifier) {
    fun fraction(at: Int) = if (of <= 0) 0f else (at.toFloat() / of).coerceIn(0f, 1f)
    val colors = LDTheme.colors
    val moves = LDTheme.motionEnabled
    val shown = remember { Animatable(fraction(if (moves) step - 1 else step)) }
    LaunchedEffect(step, moves) {
        if (moves) shown.animateTo(fraction(step), tween(600, easing = EaseOut)) else shown.snapTo(fraction(step))
    }
    Canvas(modifier.fillMaxWidth().aspectRatio(PulseGeometry.WIDTH / PulseGeometry.HEIGHT)) {
        val value = shown.value
        inPulseBox {
            trimmed(PulseGeometry.progress, 1f, colors.flatDim, PulseGeometry.STROKE)
            trimmed(PulseGeometry.progress, value, colors.accent, PulseGeometry.STROKE)
        }
    }
}

/**
 * A series as a thin line ending in a dot, drawn in on appear. Draws nothing without values; one
 * value is a dot on its own. Hidden from TalkBack: say the trend in words on the figure it belongs to.
 */
@Composable
fun SparkLine(values: List<Double>, modifier: Modifier = Modifier, color: Color = LDTheme.colors.accent, height: Dp = 20.dp) {
    val moves = LDTheme.motionEnabled
    val progress = remember { Animatable(if (moves) 0f else 1f) }
    LaunchedEffect(moves) {
        if (moves) progress.animateTo(1f, tween(700, easing = EaseOut)) else progress.snapTo(1f)
    }
    Canvas(modifier.fillMaxWidth().height(height)) {
        if (values.isEmpty()) return@Canvas
        val inset = 3.dp.toPx()
        val low = values.min()
        val high = values.max()
        val spread = if (high > low) high - low else 1.0
        val step = if (values.size > 1) (size.width - inset * 2) / (values.size - 1) else 0f
        val points = values.mapIndexed { index, value ->
            Offset(
                x = if (values.size > 1) inset + step * index else size.width - inset,
                y = size.height - inset - (size.height - inset * 2) * ((value - low) / spread).toFloat()
            )
        }
        val shown = progress.value
        followingReadingDirection {
            if (points.size > 1) {
                val line = Path().apply {
                    moveTo(points.first().x, points.first().y)
                    points.drop(1).forEach { lineTo(it.x, it.y) }
                }
                trimmed(line, shown, color, 2.dp.toPx())
            }
            drawCircle(color, 3.dp.toPx() * window(shown, 0.8f, 1f), points.last())
        }
    }
}
