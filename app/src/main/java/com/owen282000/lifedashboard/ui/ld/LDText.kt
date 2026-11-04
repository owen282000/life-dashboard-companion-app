package com.owen282000.lifedashboard.ui.ld

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

/** The font sizes where side by side stops working: a value goes under its name, tiles into one column. */
@Composable
fun ldLargeText(): Boolean = LocalDensity.current.fontScale >= 1.5f

/** The large heading: 30/34 bold, 18 from the top and 20 from the sides. */
@Composable
fun LDHeading(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = LDType.heading,
        color = LDTheme.colors.text,
        modifier = modifier
            .fillMaxWidth()
            .padding(start = LDTheme.textInset, end = LDTheme.textInset, top = 18.dp)
            .semantics { heading() }
    )
}

/** The small label above a card. */
@Composable
fun LDSectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = LDType.sectionLabel,
        color = LDTheme.colors.muted,
        modifier = modifier
            .fillMaxWidth()
            .padding(start = LDTheme.textInset, end = LDTheme.textInset, top = 14.dp, bottom = 6.dp)
            .semantics { heading() }
    )
}

/** The three sizes of body text on the boards. */
enum class LDNoteSize(internal val style: TextStyle) {
    /** 14, the default. */
    Regular(LDType.note),

    /** 13, for footnotes. */
    Small(LDType.noteSmall),

    /** 16, for intros. */
    Large(LDType.noteLarge)
}

/** A line of body text. */
@Composable
fun LDNote(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = LDTheme.colors.secondary,
    top: Dp = 8.dp,
    size: LDNoteSize = LDNoteSize.Regular
) {
    Text(
        text,
        style = size.style,
        color = color,
        modifier = modifier.fillMaxWidth().padding(start = LDTheme.textInset, end = LDTheme.textInset, top = top)
    )
}

/**
 * For what is code whatever the language: JSON, addresses, topics, header names. It reads left to
 * right against the left edge, also on a screen that runs right to left. Text inside takes
 * [ldCodeStyle].
 */
@Composable
fun LDCode(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr, content = content)
}

/** A text style for code, also where the text starts with something that reads right to left. */
fun TextStyle.ldCodeStyle(): TextStyle = copy(textDirection = TextDirection.Ltr)

/**
 * One left to right run inside text of any direction, for a version or an address in a value or a
 * sentence. Without the isolates a right to left line moves what follows a hyphen or a colon around.
 */
val String.ldLeftToRight: String get() = "\u2066$this\u2069"
