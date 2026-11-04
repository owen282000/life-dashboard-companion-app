package com.owen282000.lifedashboard.ui.ld

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.progressSemantics
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.owen282000.lifedashboard.R

/** The warning card. [actions] holds [LDBannerAction]s and may stay empty. */
@Composable
fun LDBanner(
    title: String,
    modifier: Modifier = Modifier,
    message: String? = null,
    actions: (@Composable () -> Unit)? = null
) {
    val colors = LDTheme.colors
    val shape = RoundedCornerShape(LDTheme.cardRadius)
    Column(
        modifier
            .padding(start = LDTheme.cardInset, end = LDTheme.cardInset, top = 10.dp)
            .fillMaxWidth()
            .background(colors.warningSurface, shape)
            .border(1.dp, colors.warningBorder, shape)
            .padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = if (actions == null) 12.dp else 2.dp)
    ) {
        Text(title, style = LDType.sans(15.sp, FontWeight.Bold, 20.sp), color = colors.warning)
        if (message != null) {
            Text(message, style = LDType.note, color = colors.warningBody, modifier = Modifier.padding(top = 2.dp))
        }
        if (actions != null) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) { actions() }
        }
    }
}

/** An action inside an [LDBanner]. */
@Composable
fun LDBannerAction(title: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier.heightIn(min = 48.dp).clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.CenterStart
    ) {
        Text(title, style = LDType.sans(14.sp, FontWeight.Bold, 18.sp), color = LDTheme.colors.warningText)
    }
}

/**
 * The state's `refused` as a banner: nothing was stored, because the secure storage is not ready.
 * Shows nothing while [visible] is false.
 */
@Composable
fun LDRefusedBanner(visible: Boolean, onRetry: () -> Unit, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    if (!visible) return
    LDBanner(stringResource(R.string.ld_not_saved), modifier, message = stringResource(R.string.secrets_not_ready)) {
        LDBannerAction(stringResource(R.string.ld_try_again), onClick = onRetry)
        LDBannerAction(stringResource(R.string.common_close), onClick = onDismiss)
    }
}

/** One line of [LDKeyValueList]. [isCode] is for an address or an identifier, not words. */
data class LDKeyValue(val key: String, val value: String, val color: Color? = null, val isCode: Boolean = false)

/**
 * Labels on the left, values in Space Mono on the right, on a card. Every label gets the width of
 * the longest, at most 45% of the card, so none breaks in the middle of a word when it does not need
 * to. At the largest font sizes the value goes under its label.
 */
@Composable
fun LDKeyValueList(items: List<LDKeyValue>, modifier: Modifier = Modifier) {
    val colors = LDTheme.colors
    val stacked = ldLargeText()
    var lines by remember { mutableStateOf(IntArray(0)) }
    SelectionContainer(
        modifier
            .padding(start = LDTheme.cardInset, end = LDTheme.cardInset, top = 4.dp)
            .fillMaxWidth()
            .ldSurface(LDTheme.cardRadius)
            .drawWithContent {
                drawContent()
                lines.forEach { y -> drawRect(colors.divider, Offset(0f, y.toFloat()), Size(size.width, 1.dp.toPx())) }
            }
    ) {
        Layout(
            content = {
                items.forEach { item ->
                    Text(item.key, style = LDType.rowSubtitle, color = colors.muted, modifier = Modifier.clearAndSetSemantics { })
                    val spoken = stringResource(R.string.ld_key_value, item.key, item.value)
                    val value: @Composable () -> Unit = {
                        Text(
                            item.value,
                            style = if (item.isCode) LDType.monoValue.ldCodeStyle() else LDType.monoValue,
                            color = item.color ?: colors.text,
                            modifier = Modifier.semantics { contentDescription = spoken }
                        )
                    }
                    if (item.isCode) LDCode(value) else value()
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) { measurables, constraints ->
            val padX = 14.dp.roundToPx()
            val padY = 7.dp.roundToPx()
            val gap = 8.dp.roundToPx()
            val line = 1.dp.roundToPx()
            val width = constraints.maxWidth
            val inner = width - padX * 2
            val keys = measurables.filterIndexed { index, _ -> index % 2 == 0 }
            val values = measurables.filterIndexed { index, _ -> index % 2 == 1 }
            val keyColumn = if (stacked) inner else minOf(keys.maxOfOrNull { it.maxIntrinsicWidth(Constraints.Infinity) } ?: 0, (inner * 0.45f).toInt())
            val keyPlaceables = keys.map { it.measure(Constraints(maxWidth = keyColumn)) }
            val valueWidth = if (stacked) inner else inner - keyColumn - gap
            val valuePlaceables = values.map { it.measure(Constraints(maxWidth = valueWidth)) }
            val tops = IntArray(items.size)
            val found = IntArray((items.size - 1).coerceAtLeast(0))
            var y = 0
            items.indices.forEach { index ->
                tops[index] = y + padY
                val rowHeight = if (stacked) keyPlaceables[index].height + valuePlaceables[index].height else maxOf(keyPlaceables[index].height, valuePlaceables[index].height)
                y += rowHeight + padY * 2
                if (index < items.lastIndex) {
                    found[index] = y
                    y += line
                }
            }
            lines = found
            layout(width, y) {
                items.indices.forEach { index ->
                    keyPlaceables[index].placeRelative(padX, tops[index])
                    if (stacked) {
                        valuePlaceables[index].placeRelative(padX, tops[index] + keyPlaceables[index].height)
                    } else {
                        valuePlaceables[index].placeRelative(padX + keyColumn + gap, tops[index])
                    }
                }
            }
        }
    }
}

/**
 * Text as it is, in Space Mono on a card, left to right in every language. Long lines scroll
 * sideways, or wrap with [wraps]: a stored payload is compact JSON, one line of up to 12,000
 * characters. [selectable] false is for a payload, where a Copy button is what should move it.
 */
@Composable
fun LDCodeBlock(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = LDTheme.colors.inactive,
    wraps: Boolean = false,
    selectable: Boolean = true
) {
    val code: @Composable () -> Unit = {
        Text(text, style = LDType.monoValue.ldCodeStyle(), color = color, modifier = Modifier.padding(14.dp))
    }
    LDCode {
        Box(
            modifier
                .padding(horizontal = LDTheme.cardInset)
                .fillMaxWidth()
                .ldSurface(LDTheme.cardRadius)
                .then(if (wraps) Modifier else Modifier.horizontalScroll(rememberScrollState()))
        ) {
            if (selectable) SelectionContainer { code() } else code()
        }
    }
}

/** The 8 high bar of a running backfill. [value] runs from 0 to 1. Give [modifier] a label for TalkBack. */
@Composable
fun LDProgressBar(value: Float, modifier: Modifier = Modifier) {
    val clamped = value.coerceIn(0f, 1f)
    val colors = LDTheme.colors
    Box(
        modifier
            .padding(horizontal = LDTheme.textInset)
            .fillMaxWidth()
            .height(8.dp)
            .progressSemantics(clamped)
            .clip(CircleShape)
            .background(colors.progressTrack)
    ) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(clamped).clip(CircleShape).background(colors.accent))
    }
}

/**
 * A tile: 26 icon and chevron on top, title and subtitle at the bottom, 118 high or taller with
 * larger text.
 */
@Composable
fun LDTile(
    title: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onClick: (() -> Unit)? = null
) {
    val colors = LDTheme.colors
    Column(
        modifier
            .fillMaxWidth()
            .heightIn(min = 118.dp)
            .ldSurface(LDTheme.tileRadius)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier.semantics(mergeDescendants = true) { })
            .padding(horizontal = 14.dp, vertical = 13.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            Icon(icon, contentDescription = null, tint = colors.icon, modifier = Modifier.size(26.dp))
            Spacer(Modifier.weight(1f))
            if (onClick != null) LDChevron()
        }
        Spacer(Modifier.weight(1f).heightIn(min = 8.dp))
        Text(title, style = LDType.tileTitle, color = colors.text)
        if (subtitle != null) Text(subtitle, style = LDType.rowSubtitle, color = colors.muted, modifier = Modifier.padding(top = 1.dp))
    }
}

/**
 * Tiles in two columns with the boards' padding, each row as tall as its tallest tile, one column at
 * the largest font sizes. Goes in LDScreen's `pinned`.
 */
@Composable
fun LDTileGrid(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val padded = modifier.padding(start = LDTheme.cardInset, end = LDTheme.cardInset, top = 16.dp, bottom = 14.dp)
    val oneColumn = ldLargeText()
    Layout(content, padded.fillMaxWidth()) { measurables, constraints ->
        val gap = 8.dp.roundToPx()
        val columns = if (oneColumn) 1 else 2
        val width = constraints.maxWidth
        val column = ((width - gap * (columns - 1)) / columns).coerceAtLeast(0)
        val rows = measurables.chunked(columns)
        val heights = rows.map { row -> row.maxOf { it.maxIntrinsicHeight(column) } }
        val placeables = rows.mapIndexed { index, row -> row.map { it.measure(Constraints.fixed(column, heights[index])) } }
        val height = heights.sum() + gap * (rows.size - 1).coerceAtLeast(0)
        layout(width, height) {
            var y = 0
            placeables.forEachIndexed { index, row ->
                row.forEachIndexed { cell, placeable -> placeable.placeRelative(cell * (column + gap), y) }
                y += heights[index] + gap
            }
        }
    }
}
