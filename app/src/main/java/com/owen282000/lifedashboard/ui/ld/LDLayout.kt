package com.owen282000.lifedashboard.ui.ld

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.owen282000.lifedashboard.R

/**
 * The ground of every screen. Content starts at the top. [pinned] sits at the bottom, above the tab
 * bar on a root screen, while everything fits, and scrolls along once the font size makes it taller,
 * so nothing clips. [footer] stays fixed under the scrolling part. A [title] gives the 56 high top
 * bar with the back arrow, and [actions] goes at its end; a tab's root screen has neither. Back
 * pops the page, unless [onBack] says otherwise: a screen outside the shell has to. The keyboard
 * pushes the footer up. A Spacer in [content] does nothing: what belongs at the bottom goes in
 * [pinned].
 */
@Composable
fun LDScreen(
    modifier: Modifier = Modifier,
    title: String? = null,
    onBack: (() -> Unit)? = null,
    background: Color = LDTheme.colors.ground,
    actions: @Composable RowScope.() -> Unit = {},
    scrollState: ScrollState = rememberScrollState(),
    pinned: (@Composable ColumnScope.() -> Unit)? = null,
    footer: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier
            .fillMaxSize()
            .background(background)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
            .imePadding()
    ) {
        Spacer(Modifier.windowInsetsTopHeight(WindowInsets.safeDrawing))
        if (title != null) LDTopBar(title, onBack = onBack, actions = actions)
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            val viewport = maxHeight
            LDPinnedLayout(
                minHeight = viewport,
                modifier = Modifier
                    .verticalScroll(scrollState)
                    .widthIn(max = LDTheme.readableWidth)
                    .fillMaxWidth()
            ) {
                Column(Modifier.fillMaxWidth().padding(bottom = if (pinned == null) 16.dp else 0.dp), content = content)
                Column(Modifier.fillMaxWidth()) {
                    pinned?.invoke(this)
                    if (footer == null) Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom)))
                }
            }
        }
        if (footer != null) {
            footer()
            Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom)))
        }
    }
}

/**
 * The content at the top and the pinned part at the bottom, at least [minHeight] tall. Both parts
 * always get the height they ask for, so nothing is cut off and the scrolling takes over.
 */
@Composable
private fun LDPinnedLayout(minHeight: Dp, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Layout(content, modifier) { measurables, constraints ->
        val child = constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity)
        val placeables = measurables.map { it.measure(child) }
        val height = maxOf(minHeight.roundToPx(), placeables.sumOf { it.height })
        layout(constraints.maxWidth, height) {
            placeables.first().placeRelative(0, 0)
            placeables.last().let { it.placeRelative(0, height - it.height) }
        }
    }
}

/** The 56 high bar of a pushed page: back arrow, title and room for an action such as Save. */
@Composable
fun LDTopBar(title: String, modifier: Modifier = Modifier, onBack: (() -> Unit)? = null, actions: @Composable RowScope.() -> Unit = {}) {
    val goBack = onBack ?: run {
        val navigator = LocalLDNavigator.current
        val entryId = LocalLDEntryId.current
        return@run { navigator.pop(entryId) }
    }
    Row(
        modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val back = stringResource(R.string.common_back)
        Box(
            Modifier
                .size(48.dp)
                .clickable(role = Role.Button, onClick = goBack),
            contentAlignment = Alignment.Center
        ) {
            Icon(LDIcons.back, contentDescription = back, tint = LDTheme.colors.text, modifier = Modifier.size(24.dp))
        }
        Text(
            title,
            style = LDType.topBar,
            color = LDTheme.colors.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(start = 4.dp).semantics { heading() }
        )
        actions()
    }
}

/** Text as an action in the top bar, such as Save. */
@Composable
fun LDTopBarAction(title: String, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        Modifier
            .heightIn(min = 48.dp)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            title,
            style = LDType.row.copy(fontWeight = FontWeight.Bold),
            color = LDTheme.colors.accentText.copy(alpha = if (enabled) 1f else 0.45f),
            maxLines = 1
        )
    }
}

/** The side margin a full width LDButton takes; LDFooterBar sets it to none. */
internal val LocalLDButtonInset = compositionLocalOf<Dp?> { null }

/**
 * The buttons under a screen, with the hairline above them. Goes in LDScreen's `footer`. Equal widths
 * while every label fits on one line that way, otherwise in proportion to their labels, otherwise on
 * more lines, so a label only wraps when it is wider than the whole bar.
 */
@Composable
fun LDFooterBar(modifier: Modifier = Modifier, background: Color = LDTheme.colors.ground, content: @Composable () -> Unit) {
    Column(modifier.fillMaxWidth().background(background)) {
        LDHairline()
        CompositionLocalProvider(LocalLDButtonInset provides 0.dp) {
            Layout(content, Modifier.padding(start = LDTheme.cardInset, end = LDTheme.cardInset, top = 10.dp, bottom = 12.dp)) { measurables, constraints ->
                val spacing = 8.dp.roundToPx()
                val lines = LDFooterLines.lines(measurables.map { it.maxIntrinsicWidth(Constraints.Infinity) }, constraints.maxWidth, spacing)
                var height = 0
                val placed = lines.map { line ->
                    val lineHeight = line.maxOf { measurables[it.index].maxIntrinsicHeight(it.width) }
                    val top = height
                    height += lineHeight + spacing
                    Triple(top, lineHeight, line)
                }
                val placeables = placed.flatMap { (_, lineHeight, line) ->
                    line.map { it.index to measurables[it.index].measure(Constraints.fixed(it.width, lineHeight)) }
                }.toMap()
                layout(constraints.maxWidth, (height - spacing).coerceAtLeast(0)) {
                    placed.forEach { (top, _, line) ->
                        var start = 0
                        line.forEach { cell ->
                            placeables.getValue(cell.index).placeRelative(start, top)
                            start += cell.width + spacing
                        }
                    }
                }
            }
        }
    }
}

/** Which button goes on which line, at what width. Apart from the layout so a test can hold it. */
internal object LDFooterLines {
    data class Cell(val index: Int, val width: Int)

    fun lines(ideal: List<Int>, width: Int, spacing: Int): List<List<Cell>> {
        val lines = mutableListOf<List<Cell>>()
        var start = 0
        while (start < ideal.size) {
            var end = start + 1
            var used = ideal[start]
            while (end < ideal.size && used + spacing + ideal[end] <= width) {
                used += spacing + ideal[end]
                end++
            }
            val count = end - start
            val room = width - spacing * (count - 1)
            val equal = room / count
            val isEqual = (start until end).all { ideal[it] <= equal }
            val share = (room - (start until end).sumOf { ideal[it].coerceAtMost(room) }) / count
            lines += (start until end).map { Cell(it, if (isEqual) equal else ideal[it].coerceAtMost(room) + share) }
            start = end
        }
        return lines
    }
}

/** Fixed vertical space between blocks: `sp(n)` on the boards. */
@Composable
fun LDGap(height: Dp) = Spacer(Modifier.height(height))

/** The line between rows in a card. LDCard draws its own. */
@Composable
fun LDDivider(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(LDTheme.colors.divider).clearAndSetSemantics { })
}

/** The fainter line above a footer and between log rows. */
@Composable
fun LDHairline(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(LDTheme.colors.hairline).clearAndSetSemantics { })
}
