package com.owen282000.lifedashboard.ui.ld

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.owen282000.lifedashboard.R

/** The tile fill, its border and its corners. */
@Composable
internal fun Modifier.ldSurface(radius: Dp, border: Color = LDTheme.colors.tileBorder): Modifier {
    val shape = RoundedCornerShape(radius)
    return clip(shape).background(LDTheme.colors.tile, shape).border(1.dp, border, shape)
}

/** The trailing chevron, following the reading direction. */
@Composable
fun LDChevron(modifier: Modifier = Modifier) {
    Icon(LDIcons.chevron, contentDescription = null, tint = LDTheme.colors.muted, modifier = modifier.size(18.dp))
}

/**
 * Rows on a tile surface with a line between them. Every child that takes height counts as a row, so
 * `if` and a loop in the content never leave a stray line. [bottom] is space below the card.
 */
@Composable
fun LDCard(modifier: Modifier = Modifier, bottom: Dp = 0.dp, content: @Composable () -> Unit) {
    val divider = LDTheme.colors.divider
    var lines by remember { mutableStateOf(IntArray(0)) }
    Layout(
        content,
        modifier
            .padding(start = LDTheme.cardInset, end = LDTheme.cardInset, bottom = bottom)
            .fillMaxWidth()
            .ldSurface(LDTheme.cardRadius)
            .drawWithContent {
                drawContent()
                lines.forEach { y -> drawRect(divider, Offset(0f, y.toFloat()), Size(size.width, 1.dp.toPx())) }
            }
    ) { measurables, constraints ->
        // The border is drawn over the content, so the rows start one pixel inside it, as on the boards.
        val inset = 1.dp.roundToPx()
        val line = 1.dp.roundToPx()
        val width = constraints.maxWidth
        val rowConstraints = Constraints(minWidth = width - inset * 2, maxWidth = width - inset * 2)
        val placeables = measurables.map { it.measure(rowConstraints) }.filter { it.height > 0 }
        var y = inset
        val tops = IntArray(placeables.size)
        val found = IntArray((placeables.size - 1).coerceAtLeast(0))
        placeables.forEachIndexed { index, placeable ->
            tops[index] = y
            y += placeable.height
            if (index < placeables.lastIndex) {
                found[index] = y
                y += line
            }
        }
        lines = found
        layout(width, y + inset) {
            placeables.forEachIndexed { index, placeable -> placeable.placeRelative(inset, tops[index]) }
        }
    }
}

/**
 * The main part and what stands at the end of a row. [end] is measured first and held to 60% of the
 * width where it holds a value, so a long value wraps instead of squeezing the name word by word.
 */
@Composable
private fun LDRowLayout(modifier: Modifier, main: @Composable () -> Unit, end: @Composable () -> Unit) {
    Layout({ Box { main() }; Box { end() } }, modifier) { measurables, constraints ->
        val gap = 8.dp.roundToPx()
        val width = constraints.maxWidth
        val endPlaceable = measurables[1].measure(Constraints(maxWidth = (width * 0.6f).toInt()))
        val taken = if (endPlaceable.width > 0) endPlaceable.width + gap else 0
        val mainPlaceable = measurables[0].measure(Constraints(minWidth = (width - taken).coerceAtLeast(0), maxWidth = (width - taken).coerceAtLeast(0)))
        val height = maxOf(mainPlaceable.height, endPlaceable.height, constraints.minHeight)
        layout(width, height) {
            mainPlaceable.placeRelative(0, (height - mainPlaceable.height) / 2)
            endPlaceable.placeRelative(width - endPlaceable.width, (height - endPlaceable.height) / 2)
        }
    }
}

/**
 * A row in a card: name, optional subtitle, optional value and a chevron, 50 high or 62 with a
 * subtitle, taller with larger text. [onClick] makes it a button; without one, give it
 * `showChevron = false`. [trailing] holds a control or a spinner in place of the value and the
 * chevron. At the largest font sizes the value goes under the name.
 */
@Composable
fun LDRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    value: String? = null,
    valueColor: Color = LDTheme.colors.muted,
    valueMono: Boolean = false,
    showChevron: Boolean = true,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null
) {
    val stacked = ldLargeText()
    val valueStyle = if (valueMono) LDType.monoField else LDType.value
    val clickable = if (onClick != null) Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick) else Modifier
    val grouped = if (trailing == null && onClick == null) Modifier.semantics(mergeDescendants = true) { } else Modifier
    LDRowLayout(
        modifier
            .fillMaxWidth()
            .then(clickable)
            .then(grouped)
            .alpha(if (enabled) 1f else 0.45f)
            .heightIn(min = if (subtitle == null) 50.dp else 62.dp)
            .padding(start = 14.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
        main = {
            Column {
                Text(title, style = LDType.row, color = LDTheme.colors.text)
                if (subtitle != null) Text(subtitle, style = LDType.rowSubtitle, color = LDTheme.colors.muted)
                if (value != null && stacked) Text(value, style = valueStyle, color = valueColor)
            }
        },
        end = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (value != null && !stacked) {
                    Text(value, style = valueStyle, color = valueColor, textAlign = TextAlign.End, modifier = Modifier.weight(1f, fill = false))
                }
                trailing?.invoke()
                if (showChevron && trailing == null) LDChevron()
            }
        }
    )
}

/**
 * A row with a switch in the accent. [locked] is a data type without its permission: a lock and half
 * strength, and switching it on still calls [onCheckedChange], which asks for the permission.
 */
@Composable
fun LDToggleRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    enabled: Boolean = true,
    locked: Boolean = false
) {
    val colors = LDTheme.colors
    val lockedText = stringResource(R.string.ld_type_locked)
    Row(
        modifier
            .fillMaxWidth()
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange)
            .heightIn(min = 50.dp)
            .padding(start = 14.dp, end = 12.dp, top = 6.dp, bottom = 6.dp)
            .alpha(if (locked || !enabled) 0.5f else 1f),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = LDType.row, color = colors.text)
            if (subtitle != null) Text(subtitle, style = LDType.rowSubtitle, color = colors.muted)
        }
        // Said by the padlock, not as a state: that would take the place of TalkBack's On and Off.
        if (locked) Icon(LDIcons.padlock, contentDescription = lockedText, tint = colors.muted, modifier = Modifier.size(16.dp))
        Switch(
            checked = checked,
            onCheckedChange = null,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor = colors.onAccent,
                checkedTrackColor = colors.accent,
                checkedBorderColor = colors.accent,
                uncheckedThumbColor = colors.muted,
                uncheckedTrackColor = colors.toggleOffTrack,
                uncheckedBorderColor = colors.toggleOffBorder,
                disabledCheckedThumbColor = colors.onAccent,
                disabledCheckedTrackColor = colors.accent,
                disabledUncheckedThumbColor = colors.muted,
                disabledUncheckedTrackColor = colors.toggleOffTrack,
                disabledUncheckedBorderColor = colors.toggleOffBorder
            )
        )
    }
}

/**
 * A small label over a value, Space Mono by default. With [onValueChange] it is an editable field,
 * without one a value that cannot be edited. [error] goes under the value in the warning colour:
 * that is where a validation problem belongs, not in a toast. [isCode] keeps the text left to right
 * in every language and takes the keyboard's autocorrect away; pass false for a field that takes
 * words or a plain number. [trailing] holds a control such as a reveal button. [onDone] makes the
 * keyboard's action run what the screen's Add, Use or Save runs. [focusRequester] puts the focus here.
 */
@Composable
fun LDFieldRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    onValueChange: ((String) -> Unit)? = null,
    placeholder: String? = null,
    mono: Boolean = true,
    isSecure: Boolean = false,
    isCode: Boolean = true,
    error: String? = null,
    keyboardOptions: KeyboardOptions? = null,
    onDone: (() -> Unit)? = null,
    focusRequester: FocusRequester? = null,
    trailing: (@Composable () -> Unit)? = null
) {
    val colors = LDTheme.colors
    val base = if (mono) LDType.monoField else LDType.value
    val style = (if (isCode) base.ldCodeStyle() else base).copy(color = colors.text)
    Row(
        modifier
            .fillMaxWidth()
            .then(if (onValueChange == null) Modifier.semantics(mergeDescendants = true) { } else Modifier)
            .heightIn(min = 60.dp)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                label,
                style = LDType.rowSubtitle,
                color = colors.muted,
                // The field below says this label itself; TalkBack would read it twice.
                modifier = if (onValueChange != null) Modifier.clearAndSetSemantics { } else Modifier
            )
            val field: @Composable () -> Unit = {
                if (onValueChange != null) {
                    BasicTextField(
                        value = value,
                        onValueChange = onValueChange,
                        textStyle = style,
                        singleLine = true,
                        visualTransformation = if (isSecure) PasswordVisualTransformation() else VisualTransformation.None,
                        keyboardOptions = (
                            keyboardOptions ?: when {
                                isSecure -> KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, keyboardType = KeyboardType.Password)
                                isCode -> KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false)
                                else -> KeyboardOptions.Default
                            }
                            ).let { if (onDone != null) it.copy(imeAction = ImeAction.Done) else it },
                        keyboardActions = if (onDone != null) KeyboardActions(
                            onDone = {
                                onDone()
                                // A page that stays, as after saving an interval, has no more use for the keyboard.
                                defaultKeyboardAction(ImeAction.Done)
                            }
                        ) else KeyboardActions.Default,
                        cursorBrush = SolidColor(colors.accentText),
                        modifier = Modifier.fillMaxWidth().then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier).semantics {
                            contentDescription = label
                            if (error != null) error(error)
                        },
                        decorationBox = { inner ->
                            Box {
                                if (value.isEmpty() && placeholder != null) {
                                    Text(placeholder, style = style.copy(color = colors.muted), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                inner()
                            }
                        }
                    )
                } else {
                    SelectionContainer { Text(value, style = style, modifier = Modifier.fillMaxWidth()) }
                }
            }
            if (isCode) LDCode(field) else field()
            if (error != null) {
                Text(
                    error,
                    style = LDType.rowSubtitle,
                    color = colors.warning,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                )
            }
        }
        trailing?.invoke()
    }
}

/**
 * A destination on Home: name, status in the soft accent (or [statusColor] for a problem) and a
 * chevron, full width. Name and status go under each other when they do not fit side by side, so a
 * large font size never breaks a word in two.
 */
@Composable
fun LDDestinationRow(
    name: String,
    status: String,
    modifier: Modifier = Modifier,
    statusColor: Color = LDTheme.colors.accentTextSoft,
    onClick: (() -> Unit)? = null
) {
    Row(
        modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier.semantics(mergeDescendants = true) { })
            .heightIn(min = 48.dp)
            .padding(horizontal = LDTheme.textInset, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Layout(
            content = {
                Text(name, style = LDType.destination, color = LDTheme.colors.text, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
                Text(status, style = LDType.destinationStatus, color = statusColor)
            },
            modifier = Modifier.weight(1f)
        ) { measurables, constraints ->
            val width = constraints.maxWidth
            val gap = 10.dp.roundToPx()
            val fits = measurables[0].maxIntrinsicWidth(Constraints.Infinity) + gap + measurables[1].maxIntrinsicWidth(Constraints.Infinity) <= width
            val each = Constraints(maxWidth = width)
            val name = measurables[0].measure(each)
            val state = measurables[1].measure(each)
            if (fits) {
                val height = maxOf(name.height, state.height)
                layout(width, height) {
                    name.placeRelative(0, (height - name.height) / 2)
                    state.placeRelative(width - state.width, (height - state.height) / 2)
                }
            } else {
                layout(width, name.height + state.height) {
                    name.placeRelative(0, 0)
                    state.placeRelative(0, name.height)
                }
            }
        }
        if (onClick != null) LDChevron()
    }
}

enum class LDOptionMark { Radio, Check }

/** A card to pick, with a radio circle or a check square. A real control: TalkBack says selected for a radio, checked for a check. */
@Composable
fun LDOption(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    mark: LDOptionMark = LDOptionMark.Radio,
    bottom: Dp = 8.dp
) {
    val colors = LDTheme.colors
    val indicator = RoundedCornerShape(if (mark == LDOptionMark.Radio) 12.dp else 7.dp)
    Row(
        modifier
            .padding(start = LDTheme.cardInset, end = LDTheme.cardInset, bottom = bottom)
            .fillMaxWidth()
            .ldSurface(LDTheme.cardRadius, if (selected) colors.accent else colors.tileBorder)
            .then(
                if (mark == LDOptionMark.Radio) {
                    Modifier.selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
                } else {
                    Modifier.toggleable(value = selected, role = Role.Checkbox, onValueChange = { onClick() })
                }
            )
            .heightIn(min = 58.dp)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(24.dp)
                .clip(indicator)
                .background(if (selected) colors.accent else Color.Transparent)
                .border(2.dp, if (selected) colors.accent else colors.optionOff, indicator),
            contentAlignment = Alignment.Center
        ) {
            if (selected) Icon(LDIcons.check, contentDescription = null, tint = colors.onAccent, modifier = Modifier.size(16.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = LDType.tileTitle, color = colors.text)
            if (subtitle != null) Text(subtitle, style = LDType.rowSubtitle, color = colors.muted)
        }
    }
}
