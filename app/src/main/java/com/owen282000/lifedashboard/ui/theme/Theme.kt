package com.owen282000.lifedashboard.ui.theme

import android.app.Activity
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import com.owen282000.lifedashboard.ThemeMode
import com.owen282000.lifedashboard.appPreferences

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

// The scrims enableEdgeToEdge puts behind a three-button navigation bar before Android 10,
// which draws no contrast scrim of its own; the same values as the library's defaults.
private val LightNavigationScrim = android.graphics.Color.argb(0xE6, 0xFF, 0xFF, 0xFF)
private val DarkNavigationScrim = android.graphics.Color.argb(0x80, 0x1B, 0x1B, 0x1B)

/**
 * The theme chosen in About, kept current wherever it changes: About itself, or an imported
 * backup. A screen in the back stack picks it up too, so going back needs no restart.
 */
@Composable
fun chosenThemeMode(): ThemeMode {
    if (LocalInspectionMode.current) return ThemeMode.SYSTEM
    val context = LocalContext.current
    val prefs = remember(context) { context.appPreferences() }
    val mode by remember(prefs) { prefs.themeModeChanges() }.collectAsState(initial = prefs.getThemeMode())
    return mode
}

/** Whether the app draws dark: the choice in About, or the phone's dark mode under System. */
@Composable
fun appInDarkTheme(): Boolean = chosenThemeMode().isDark(isSystemInDarkTheme())

@Composable
fun LifeDashboardTheme(
    darkTheme: Boolean = appInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val activity = view.context as Activity
            // The bar icons follow the app's theme, not the phone's: Light on a dark phone
            // needs dark icons. enableEdgeToEdge decides that from the phone's dark mode
            // unless it is told, so each activity's call in onCreate is redone here with the
            // app's answer, the navigation bar included.
            (activity as? ComponentActivity)?.enableEdgeToEdge(
                statusBarStyle = SystemBarStyle.auto(
                    android.graphics.Color.TRANSPARENT,
                    android.graphics.Color.TRANSPARENT
                ) { darkTheme },
                navigationBarStyle = SystemBarStyle.auto(LightNavigationScrim, DarkNavigationScrim) { darkTheme }
            )
            activity.window.statusBarColor = colorScheme.background.toArgb()
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
