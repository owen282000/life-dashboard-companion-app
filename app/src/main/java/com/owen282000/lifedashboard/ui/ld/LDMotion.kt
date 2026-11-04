package com.owen282000.lifedashboard.ui.ld

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

object LDMotion {
    /** cubic-bezier(.2,.8,.2,1) from the boards. */
    val Enter = CubicBezierEasing(0.2f, 0.8f, 0.2f, 1f)

    /** How far tab content starts from where it ends, on the side of the switch. */
    val TabSlide = 18.dp
    const val TAB_MILLIS = 220
    const val PUSH_MILLIS = 300
    const val RISE_MILLIS = 320
    const val STAGGER_MILLIS = 40

    /** Capped so the last row of a long list does not arrive after the user has moved on. */
    const val STAGGER_CAP = 8
}

/** Rises and fades in on appear. [index] is its place in the stagger. Still with the animator scale at 0. */
fun Modifier.ldStaggerRise(index: Int = 0): Modifier = composed {
    val moves = LDTheme.motionEnabled
    val shown = remember { Animatable(if (moves) 0f else 1f) }
    LaunchedEffect(moves) {
        if (moves) {
            shown.animateTo(
                1f,
                tween(LDMotion.RISE_MILLIS, delayMillis = minOf(index, LDMotion.STAGGER_CAP) * LDMotion.STAGGER_MILLIS, easing = LDMotion.Enter)
            )
        } else {
            shown.snapTo(1f)
        }
    }
    val rise = with(LocalDensity.current) { 10.dp.toPx() }
    graphicsLayer {
        translationY = (1f - shown.value) * rise
        alpha = shown.value
    }
}
