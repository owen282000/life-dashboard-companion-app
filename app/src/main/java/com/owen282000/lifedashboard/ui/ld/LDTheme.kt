package com.owen282000.lifedashboard.ui.ld

import android.content.Context
import android.provider.Settings
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.owen282000.lifedashboard.ui.theme.appInDarkTheme

/**
 * The A2b palette, under the token names of the iOS app's LDTheme. Dark is the designed look, light
 * the same product on a pale ground: the accent a step deeper so the line and the fills hold 3:1
 * against the ground, and dark text on them stays at 4.5:1 or better. LDThemeTest measures the pairs.
 */
@Immutable
data class LDColors(
    val ground: Color,
    val text: Color,
    val accent: Color,
    val accentDim: Color,
    val accentText: Color,
    val accentTextSoft: Color,
    val onAccent: Color,
    val icon: Color,
    val muted: Color,
    val secondary: Color,
    val inactive: Color,
    val tile: Color,
    val tileBorder: Color,
    val divider: Color,
    val hairline: Color,
    val ghostBorder: Color,
    val warning: Color,
    val warningText: Color,
    val warningSurface: Color,
    val warningBorder: Color,
    val flatLine: Color,
    val flatDim: Color,
    val sheet: Color,
    val warningDim: Color,
    val warningBody: Color,
    val optionOff: Color,
    val progressTrack: Color,
    // Android only: the switch of the boards, off.
    val toggleOffTrack: Color,
    val toggleOffBorder: Color
)

/** The accent of a tab. Everything drawn in the accent follows it, tiles and their border included. */
enum class LDAccent { Green, Purple }

private fun tone(light: Long, dark: Long, isDark: Boolean) = Color(0xFF000000 or if (isDark) dark else light)

internal fun ldColors(dark: Boolean, accent: LDAccent = LDAccent.Green): LDColors {
    val base = LDColors(
        ground = tone(0xF3F6F4, 0x0A0F0D, dark),
        text = tone(0x101A15, 0xEEF4F0, dark),
        accent = tone(0x1E9665, 0x30B77E, dark),
        accentDim = tone(0x63B797, 0x1F7A55, dark),
        accentText = tone(0x176B49, 0x6FE0AE, dark),
        accentTextSoft = tone(0x1B6F4D, 0x8FDDB8, dark),
        onAccent = tone(0x06130D, 0x06130D, dark),
        icon = tone(0x1B7A54, 0x4FD19A, dark),
        muted = tone(0x56645D, 0xA7B5AE, dark),
        secondary = tone(0x46534C, 0xB9C4BE, dark),
        inactive = tone(0x3E4B44, 0xC7D2CC, dark),
        tile = tone(0xFFFFFF, 0x141C18, dark),
        tileBorder = tone(0xD5DED9, 0x27332D, dark),
        divider = tone(0xE1E7E3, 0x222C27, dark),
        hairline = tone(0xD5DED9, 0x1E2A25, dark),
        ghostBorder = tone(0xA9B8B0, 0x33423B, dark),
        warning = tone(0x955A00, 0xF7C56C, dark),
        warningText = tone(0x6E4A06, 0xF7DFAE, dark),
        warningSurface = tone(0xFBF3DF, 0x1D1A10, dark),
        warningBorder = tone(0xE3C98F, 0x5A4620, dark),
        flatLine = tone(0x7A8E83, 0x3B5A4C, dark),
        flatDim = tone(0xC6D3CB, 0x26382F, dark),
        sheet = tone(0xF8FAF9, 0x0E1512, dark),
        warningDim = tone(0xA97F2E, 0x8A6524, dark),
        warningBody = tone(0x5A4318, 0xE9DDBF, dark),
        optionOff = tone(0x7C8C84, 0x5A6C63, dark),
        progressTrack = tone(0xE2E9E5, 0x1E2622, dark),
        toggleOffTrack = tone(0xD5DED9, 0x26332D, dark),
        toggleOffBorder = tone(0x7C8C84, 0x4A5C53, dark)
    )
    return when (accent) {
        LDAccent.Green -> base
        LDAccent.Purple -> base.copy(
            accent = tone(0x9470F7, 0x9B7BF7, dark),
            accentDim = tone(0xBBA6F5, 0x5B46A6, dark),
            accentText = tone(0x5B34C4, 0xC9B8FF, dark),
            accentTextSoft = tone(0x6238C9, 0xC9B8FF, dark),
            onAccent = tone(0x12082E, 0x12082E, dark),
            icon = tone(0x6A42D6, 0xB9A5FF, dark),
            tile = tone(0xFFFFFF, 0x171522, dark),
            tileBorder = tone(0xDAD3EE, 0x302A47, dark)
        )
    }
}

private val LocalLDColors = staticCompositionLocalOf { ldColors(dark = true) }
private val LocalLDDark = staticCompositionLocalOf { true }
private val LocalLDMotion = staticCompositionLocalOf { true }

object LDTheme {
    val colors: LDColors
        @Composable @ReadOnlyComposable
        get() = LocalLDColors.current

    /** Whether this screen draws dark, for what the tokens do not cover. */
    val isDark: Boolean
        @Composable @ReadOnlyComposable
        get() = LocalLDDark.current

    /** False with the animator scale at 0 in the system settings: then nothing moves. */
    val motionEnabled: Boolean
        @Composable @ReadOnlyComposable
        get() = LocalLDMotion.current

    val cardRadius = 18.dp
    val tileRadius = 20.dp

    /** The side margin of cards, buttons and tiles. */
    val cardInset = 14.dp

    /** The side margin of headings, labels and notes. */
    val textInset = 20.dp

    /** The widest a screen's column gets, in landscape. */
    val readableWidth = 700.dp
}

private fun animatorScaleOn(context: Context): Boolean =
    Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f

/**
 * The tokens, resolved from the app's System, Light or Dark setting. Material's own components (a
 * dialog, a time picker) draw from the same tokens, so they do not look like another app.
 */
@Composable
fun LDTheme(dark: Boolean = appInDarkTheme(), content: @Composable () -> Unit) {
    val context = LocalContext.current
    var motion by remember { mutableStateOf(animatorScaleOn(context)) }
    LifecycleResumeEffect(context) {
        motion = animatorScaleOn(context)
        onPauseOrDispose { }
    }
    val colors = remember(dark) { ldColors(dark) }
    CompositionLocalProvider(
        LocalLDDark provides dark,
        LocalLDColors provides colors,
        LocalLDMotion provides motion
    ) {
        MaterialTheme(colorScheme = materialScheme(colors, dark), typography = LDType.material, content = content)
    }
}

/** Everything inside draws in [accent]: the shell does this per tab, so a screen needs nothing for it. */
@Composable
fun LDAccentScope(accent: LDAccent, content: @Composable () -> Unit) {
    val dark = LocalLDDark.current
    val colors = remember(dark, accent) { ldColors(dark, accent) }
    CompositionLocalProvider(LocalLDColors provides colors) {
        MaterialTheme(colorScheme = materialScheme(colors, dark), typography = LDType.material, content = content)
    }
}

private fun materialScheme(colors: LDColors, dark: Boolean): ColorScheme {
    val base = if (dark) darkColorScheme() else lightColorScheme()
    return base.copy(
        // As text in a dialog's buttons as often as a fill, so the accent as text.
        primary = colors.accentText,
        onPrimary = colors.ground,
        primaryContainer = colors.accent,
        onPrimaryContainer = colors.onAccent,
        secondary = colors.accentText,
        onSecondary = colors.ground,
        secondaryContainer = colors.tile,
        onSecondaryContainer = colors.text,
        tertiary = colors.accentText,
        onTertiary = colors.ground,
        background = colors.ground,
        onBackground = colors.text,
        surface = colors.sheet,
        onSurface = colors.text,
        surfaceVariant = colors.tile,
        onSurfaceVariant = colors.muted,
        surfaceContainerLowest = colors.ground,
        surfaceContainerLow = colors.sheet,
        surfaceContainer = colors.sheet,
        surfaceContainerHigh = colors.sheet,
        surfaceContainerHighest = colors.tile,
        outline = colors.ghostBorder,
        outlineVariant = colors.divider,
        error = colors.warning,
        onError = colors.ground,
        errorContainer = colors.warningSurface,
        onErrorContainer = colors.warningBody
    )
}
