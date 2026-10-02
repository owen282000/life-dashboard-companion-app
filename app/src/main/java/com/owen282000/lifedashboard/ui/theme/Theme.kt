package com.owen282000.lifedashboard.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

private val DarkColorScheme = darkColorScheme(
    primary = BrandGreen,
    onPrimary = OnPrimary,
    primaryContainer = HealthContainerDark,
    onPrimaryContainer = OnHealthContainerDark,
    // The Material secondary role has to point somewhere, and it used to be an orange no
    // component in the app ever rendered (verified by turning it magenta and photographing
    // nine screens in both themes). Pointing it at the Screen Time accent means anything
    // added later that reads the role lands on a palette colour rather than a dead one.
    secondary = ScreenTimePrimary,
    onSecondary = Color.White,
    secondaryContainer = ScreenTimeContainerDark,
    onSecondaryContainer = OnScreenTimeContainerDark,
    background = BackgroundDark,
    onBackground = TextPrimaryDark,
    surface = SurfaceDark,
    onSurface = TextPrimaryDark,
    surfaceVariant = SurfaceVariantDark,
    onSurfaceVariant = TextSecondaryDark,
    // Compose draws the error role as text (supporting text, our own messages): the ink, which
    // clears 4.5:1 here where the fill does not (4.1:1).
    error = ErrorInkDark,
    errorContainer = ErrorContainerDark,
    onErrorContainer = OnErrorContainerDark
)

private val LightColorScheme = lightColorScheme(
    // The primary role is text as often as it is a fill: TextButtons, a focused field's label,
    // the cursor. Brand green is 2.3:1 on white, so light takes the ink, as the iOS app's
    // AccentColor does, and white on it (5.9:1). Our own green tiles and icons keep BrandGreen.
    primary = HealthInk,
    onPrimary = Color.White,
    primaryContainer = HealthContainer,
    onPrimaryContainer = OnHealthContainer,
    secondary = ScreenTimePrimary,
    onSecondary = Color.White,
    secondaryContainer = ScreenTimeContainer,
    onSecondaryContainer = OnScreenTimeContainer,
    background = BackgroundLight,
    onBackground = TextPrimary,
    surface = SurfaceLight,
    onSurface = TextPrimary,
    surfaceVariant = SurfaceVariantLight,
    onSurfaceVariant = TextSecondary,
    error = ErrorInk,
    errorContainer = ErrorContainer,
    onErrorContainer = OnErrorContainer
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp)
)

@Composable
fun LifeDashboardTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.background.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        shapes = AppShapes,
        content = content
    )
}

/**
 * This colour as text: its ink in the current theme (see [inkOf]). Use it wherever an accent or
 * a status colour colours words; fills, icons and tiles keep the colour itself.
 */
@Composable
@ReadOnlyComposable
fun Color.ink(): Color = inkOf(this, dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f)
