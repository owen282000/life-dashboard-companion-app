package com.owen282000.lifedashboard.ui.ld

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.owen282000.lifedashboard.R

/** One choice in [LDSegmented]. */
data class LDSegment<T>(val value: T, val title: String)

/**
 * The boards' segmented control, with apply-at-once in mind: every tap is a choice. [label] is what
 * TalkBack calls the group. At the largest font sizes the segments stack.
 */
@Composable
fun <T> LDSegmented(
    selected: T,
    segments: List<LDSegment<T>>,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null
) {
    val outer = modifier
        .padding(horizontal = LDTheme.cardInset)
        .fillMaxWidth()
        .ldSurface(22.dp)
        .padding(horizontal = 3.dp)
        .selectableGroup()
        .semantics { if (label != null) contentDescription = label }
    val item: @Composable (LDSegment<T>, Modifier) -> Unit = { segment, itemModifier ->
        val isSelected = segment.value == selected
        Box(
            itemModifier
                .selectable(selected = isSelected, role = Role.RadioButton) { onSelect(segment.value) }
                .padding(vertical = 5.dp)
                .heightIn(min = 38.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(if (isSelected) LDTheme.colors.accent else Color.Transparent)
                .padding(horizontal = 4.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                segment.title,
                style = LDType.sans(14.sp, if (isSelected) FontWeight.Bold else FontWeight.Medium, 18.sp),
                color = if (isSelected) LDTheme.colors.onAccent else LDTheme.colors.text,
                textAlign = TextAlign.Center
            )
        }
    }
    if (ldLargeText()) {
        Column(outer, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            segments.forEach { item(it, Modifier.fillMaxWidth()) }
        }
    } else {
        Row(outer.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            segments.forEach { item(it, Modifier.weight(1f).fillMaxHeight()) }
        }
    }
}

/**
 * A pill. Selectable, or removable with [LDRemovableChip]. 44 high on the boards, with a clear edge
 * around it that counts as its touch area, so the tap target is 48 without changing what is drawn:
 * the 6 between chips is that edge.
 */
@Composable
fun LDChip(
    title: String,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    width: Dp? = null,
    contentDescription: String? = null,
    onClick: () -> Unit
) {
    LDChipBody(modifier, selected, width, contentDescription, onClick) {
        Text(
            title,
            style = LDType.sans(14.sp, if (selected) FontWeight.Bold else FontWeight.Medium, 18.sp),
            color = if (selected) LDTheme.colors.onAccent else LDTheme.colors.text,
            textAlign = TextAlign.Center
        )
    }
}

/** A chip with an x: tapping it removes it. A time on When to send. */
@Composable
fun LDRemovableChip(title: String, modifier: Modifier = Modifier, onRemove: () -> Unit) {
    LDChipBody(modifier, false, null, stringResource(R.string.ld_remove_named, title), onRemove) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = LDType.sans(14.sp, FontWeight.Medium, 18.sp), color = LDTheme.colors.text)
            Icon(LDIcons.close, contentDescription = null, tint = LDTheme.colors.muted, modifier = Modifier.size(14.dp))
        }
    }
}

@Composable
private fun LDChipBody(
    modifier: Modifier,
    selected: Boolean,
    width: Dp?,
    label: String?,
    onClick: () -> Unit,
    content: @Composable () -> Unit
) {
    val colors = LDTheme.colors
    Box(
        modifier
            .selectable(selected = selected, role = Role.Button, onClick = onClick)
            .semantics { if (label != null) contentDescription = label }
            .padding(horizontal = 3.dp, vertical = 2.dp),
        contentAlignment = Alignment.Center
    ) {
        Box(
            Modifier
                .then(if (width != null) Modifier.widthIn(min = width) else Modifier)
                .heightIn(min = 44.dp)
                .clip(CircleShape)
                .background(if (selected) colors.accent else Color.Transparent)
                .border(1.dp, if (selected) colors.accent else colors.ghostBorder, CircleShape)
                .padding(horizontal = if (width == null) 14.dp else 0.dp),
            contentAlignment = Alignment.Center
        ) {
            content()
        }
    }
}

/** Chips in rows that wrap, with the boards' side margin. */
@Composable
fun LDChips(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    // Each chip brings 3 of clear edge per side, see LDChip.
    FlowRow(modifier.fillMaxWidth().padding(horizontal = LDTheme.textInset - 3.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        content()
    }
}

/** One day in [LDDayChips]. */
data class LDDayChip(val letter: String, val fullName: String, val selected: Boolean, val onClick: () -> Unit)

/**
 * The seven days on one line, as on the boards, each 44 wide with its tap area filling the share of
 * the line. Wraps only when 44 no longer fits, at a narrow width or a large font size.
 */
@Composable
fun LDDayChips(days: List<LDDayChip>, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier.fillMaxWidth().padding(horizontal = LDTheme.textInset)) {
        if (maxWidth / days.size >= 44.dp && !ldLargeText()) {
            Row(Modifier.fillMaxWidth()) {
                days.forEach { day ->
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        LDChip(day.letter, Modifier.fillMaxWidth(), selected = day.selected, width = 44.dp, contentDescription = day.fullName, onClick = day.onClick)
                    }
                }
            }
        } else {
            FlowRow(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                days.forEach { day ->
                    LDChip(day.letter, selected = day.selected, width = 44.dp, contentDescription = day.fullName, onClick = day.onClick)
                }
            }
        }
    }
}

enum class LDButtonKind {
    Primary,
    Ghost,

    /** The boards' danger is amber, not red. */
    Danger
}

enum class LDButtonSize(internal val height: Dp, internal val textSize: Int) {
    /** 50 high, 15 text. */
    Regular(50.dp, 15),

    /** 44 high, 15 text: the footers of Preview and a delivery. */
    Medium(44.dp, 15),

    /** 44 high, 14 text: Sync Now and the Logs footer. */
    Small(44.dp, 14)
}

/**
 * A capsule button with the boards' side margin of 14 when it is full width, none inside LDFooterBar.
 * [busy] adds a spinner and disables it, for as long as the work runs. A 44 high one keeps a 48 high
 * tap area.
 */
@Composable
fun LDButton(
    title: String,
    modifier: Modifier = Modifier,
    kind: LDButtonKind = LDButtonKind.Primary,
    size: LDButtonSize = LDButtonSize.Regular,
    fullWidth: Boolean = true,
    inset: Dp? = null,
    enabled: Boolean = true,
    busy: Boolean = false,
    onClick: () -> Unit
) {
    val colors = LDTheme.colors
    val foreground = when (kind) {
        LDButtonKind.Primary -> colors.onAccent
        LDButtonKind.Ghost -> colors.text
        LDButtonKind.Danger -> colors.warning
    }
    val border = when (kind) {
        LDButtonKind.Primary -> Color.Transparent
        LDButtonKind.Ghost -> colors.ghostBorder
        LDButtonKind.Danger -> colors.warningDim
    }
    val side = inset ?: LocalLDButtonInset.current ?: if (fullWidth) LDTheme.cardInset else 0.dp
    val active = enabled && !busy
    Box(
        modifier
            .padding(horizontal = side)
            .then(if (fullWidth) Modifier.fillMaxWidth() else Modifier)
            .heightIn(min = 48.dp)
            .clip(CircleShape)
            .clickable(enabled = active, role = Role.Button, onClick = onClick)
            .alpha(if (active) 1f else 0.45f),
        contentAlignment = Alignment.Center
    ) {
        Row(
            (if (fullWidth) Modifier.fillMaxWidth() else Modifier)
                .heightIn(min = size.height)
                .clip(CircleShape)
                .background(if (kind == LDButtonKind.Primary) colors.accent else Color.Transparent)
                .border(1.dp, border, CircleShape)
                .padding(horizontal = 18.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (busy) {
                CircularProgressIndicator(Modifier.size(16.dp), color = foreground, strokeWidth = 2.dp)
            }
            Text(
                title,
                style = LDType.sans(size.textSize.sp, if (kind == LDButtonKind.Primary) FontWeight.Bold else FontWeight.Medium, 20.sp),
                color = foreground,
                textAlign = TextAlign.Center
            )
        }
    }
}

/** Text in the soft accent as a button, as Add destination and Skip setup on the boards. */
@Composable
fun LDTextButton(
    title: String,
    modifier: Modifier = Modifier,
    color: Color = LDTheme.colors.accentTextSoft,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Box(
        modifier
            .heightIn(min = 48.dp)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 4.dp)
            .alpha(if (enabled) 1f else 0.45f),
        contentAlignment = Alignment.CenterStart
    ) {
        Text(title, style = LDType.sectionLabel, color = color)
    }
}
