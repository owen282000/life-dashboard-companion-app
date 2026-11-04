package com.owen282000.lifedashboard.ui.ld

import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.SeekableTransitionState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * The app around the screens: the tab roots with the bar under them, and the pushed pages sliding
 * over all of that as a layer. Back follows the predictive gesture, or plays the same slide after a
 * plain press. [page] maps a route to its screen.
 *
 * A page that is covered leaves the composition. Its rememberSaveable state, scroll position
 * included, stays with [SaveableStateHolder] under the page's id, and a tab's root under its own, so
 * coming back or switching tabs finds everything as it was.
 */
@Composable
fun LDShell(navigator: LDNavigator, page: @Composable (LDRoute) -> Unit) {
    val holder = rememberSaveableStateHolder()
    val drafts: LDDraftStore = viewModel()
    navigator.onRemoved = { entry ->
        holder.removeState(entry.id)
        drafts.clear(entry.id)
    }
    CompositionLocalProvider(LocalLDNavigator provides navigator) {
        Box(Modifier.fillMaxSize().background(LDTheme.colors.ground)) {
            LDPageStack(navigator, holder, page)
        }
    }
}

@Composable
private fun LDPageStack(navigator: LDNavigator, holder: SaveableStateHolder, page: @Composable (LDRoute) -> Unit) {
    val stack = navigator.stack
    val top = stack.lastOrNull()
    val transitionState = remember { SeekableTransitionState(top) }
    val transition = rememberTransition(transitionState)
    val scope = rememberCoroutineScope()

    LaunchedEffect(top) { transitionState.animateTo(top) }

    // On the root of another tab Back goes to the first tab, and from there out of the app.
    BackHandler(enabled = top == null && navigator.tab != LDTab.entries.first()) { navigator.selectTab(LDTab.entries.first()) }

    PredictiveBackHandler(enabled = top != null) { gesture ->
        val leaving = navigator.stack.lastOrNull()
        val below = navigator.stack.getOrNull(navigator.stack.size - 2)
        try {
            gesture.collect { event -> transitionState.seekTo(event.progress, below) }
            navigator.pop()
        } catch (e: CancellationException) {
            scope.launch { transitionState.snapTo(leaving) }
            throw e
        }
    }

    val forward = if (LocalLayoutDirection.current == LayoutDirection.Rtl) -1 else 1
    transition.AnimatedContent(
        transitionSpec = {
            val to = targetState?.order ?: 0L
            ldLayerTransform(pushing = to > (initialState?.order ?: 0L), forward, zIndex = to.toFloat())
        },
        contentAlignment = Alignment.TopStart,
        contentKey = { it?.id ?: "tabs" }
    ) { entry ->
        if (entry == null) {
            LDTabs(navigator, holder, page)
        } else {
            LDAccentScope(entry.tab.accent) {
                holder.SaveableStateProvider(entry.id) {
                    CompositionLocalProvider(LocalLDEntryId provides entry.id) {
                        Box(Modifier.fillMaxSize().background(LDTheme.colors.ground)) { page(entry.route) }
                    }
                }
            }
        }
    }
}

/** A push slides the page in from the end and the one below a quarter out; a pop runs it backwards. A newer page lies on top. */
private fun ldLayerTransform(pushing: Boolean, forward: Int, zIndex: Float): ContentTransform {
    val spec = tween<IntOffset>(LDMotion.PUSH_MILLIS, easing = LDMotion.Enter)
    return if (pushing) {
        ContentTransform(
            slideInHorizontally(spec) { forward * it },
            slideOutHorizontally(spec) { -forward * it / 4 } + fadeOut(tween(LDMotion.PUSH_MILLIS), targetAlpha = 0.6f),
            zIndex,
            SizeTransform(clip = false)
        )
    } else {
        ContentTransform(
            slideInHorizontally(spec) { -forward * it / 4 } + fadeIn(tween(LDMotion.PUSH_MILLIS), initialAlpha = 0.6f),
            slideOutHorizontally(spec) { forward * it },
            zIndex,
            SizeTransform(clip = false)
        )
    }
}

/** The root screen of the tab in front and the bar. Tab content slides in the direction of the switch. */
@Composable
private fun LDTabs(navigator: LDNavigator, holder: SaveableStateHolder, page: @Composable (LDRoute) -> Unit) {
    val tab = navigator.tab
    val slide = with(LocalDensity.current) { LDMotion.TabSlide.roundToPx() }
    val forward = if (LocalLayoutDirection.current == LayoutDirection.Rtl) -1 else 1
    Column(Modifier.fillMaxSize()) {
        AnimatedContent(
            targetState = tab,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                // The bar below takes the navigation bar's height.
                .consumeWindowInsets(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom)),
            transitionSpec = {
                val direction = (if (targetState.ordinal > initialState.ordinal) 1 else -1) * forward
                (
                    slideInHorizontally(tween(LDMotion.TAB_MILLIS, easing = LDMotion.Enter)) { direction * slide } +
                        fadeIn(tween(LDMotion.TAB_MILLIS, easing = LDMotion.Enter))
                    ) togetherWith fadeOut(tween(LDMotion.TAB_MILLIS / 2)) using SizeTransform(clip = false)
            }
        ) { shown ->
            LDAccentScope(shown.accent) {
                holder.SaveableStateProvider("root-${shown.name}") {
                    Box(Modifier.fillMaxSize().background(LDTheme.colors.ground)) { page(shown.root) }
                }
            }
        }
        LDAccentScope(tab.accent) {
            LDTabBar(tab, onSelect = navigator::selectTab)
        }
    }
}

/**
 * The bottom bar of the boards: 62 high, a line on top, icon over label, and a 3 thick accent bar over
 * the open tab that moves along when the tab changes.
 */
@Composable
fun LDTabBar(selected: LDTab, onSelect: (LDTab) -> Unit, modifier: Modifier = Modifier) {
    val colors = LDTheme.colors
    val tabs = LDTab.entries
    val position by animateFloatAsState(selected.ordinal.toFloat(), tween(LDMotion.TAB_MILLIS, easing = LDMotion.Enter), label = "ld_tab_indicator")
    val indicator by animateColorAsState(colors.accent, tween(LDMotion.TAB_MILLIS), label = "ld_tab_indicator_color")
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Column(
        modifier
            .fillMaxWidth()
            .background(colors.ground)
            .drawBehind {
                drawRect(colors.hairline, Offset.Zero, Size(size.width, 1.dp.toPx()))
                val cell = size.width / tabs.size
                val start = if (rtl) size.width - cell * (position + 1) else cell * position
                drawRect(indicator, Offset(start, 0f), Size(cell, 3.dp.toPx()))
            }
    ) {
        Row(Modifier.fillMaxWidth().heightIn(min = 62.dp).selectableGroup()) {
            tabs.forEach { tab ->
                val isSelected = tab == selected
                val color = if (isSelected) colors.accentText else colors.inactive
                Column(
                    Modifier
                        .weight(1f)
                        .heightIn(min = 62.dp)
                        .selectable(selected = isSelected, role = Role.Tab) { onSelect(tab) }
                        .padding(vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically)
                ) {
                    Icon(tab.icon, contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
                    Text(
                        stringResource(tab.label),
                        style = LDType.tab.copy(fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal),
                        color = color,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
        Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom)))
    }
}
