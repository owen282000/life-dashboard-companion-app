package com.owen282000.lifedashboard

import androidx.compose.ui.graphics.Color
import com.owen282000.lifedashboard.ui.theme.BackgroundDark
import com.owen282000.lifedashboard.ui.theme.BackgroundLight
import com.owen282000.lifedashboard.ui.theme.BrandGreen
import com.owen282000.lifedashboard.ui.theme.Error
import com.owen282000.lifedashboard.ui.theme.HealthInk
import com.owen282000.lifedashboard.ui.theme.LogsPrimary
import com.owen282000.lifedashboard.ui.theme.OnPrimary
import com.owen282000.lifedashboard.ui.theme.ScreenTimePrimary
import com.owen282000.lifedashboard.ui.theme.Success
import com.owen282000.lifedashboard.ui.theme.SurfaceDark
import com.owen282000.lifedashboard.ui.theme.SurfaceLight
import com.owen282000.lifedashboard.ui.theme.SurfaceVariantDark
import com.owen282000.lifedashboard.ui.theme.SurfaceVariantLight
import com.owen282000.lifedashboard.ui.theme.TextPrimary
import com.owen282000.lifedashboard.ui.theme.Warning
import com.owen282000.lifedashboard.ui.theme.inkOf
import com.owen282000.lifedashboard.ui.theme.onAccent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

/**
 * Text in the accent and status colours, measured against everything it is drawn on
 * (P2-10 step 4). The iOS app measures its own palette the same way in UIParityTests.
 */
class ColorContrastTest {

    private val fills = listOf(BrandGreen, ScreenTimePrimary, LogsPrimary, Success, Error, Warning)
    private val lightSurfaces = listOf(SurfaceLight, BackgroundLight, SurfaceVariantLight)
    private val darkSurfaces = listOf(BackgroundDark, SurfaceDark, SurfaceVariantDark)

    private fun luminance(c: Color): Double {
        fun channel(v: Float) = if (v <= 0.04045f) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
        return 0.2126 * channel(c.red) + 0.7152 * channel(c.green) + 0.0722 * channel(c.blue)
    }

    private fun contrast(a: Color, b: Color): Double {
        val (high, low) = listOf(luminance(a), luminance(b)).sortedDescending()
        return (high + 0.05) / (low + 0.05)
    }

    /** [color] at [alpha] over [background], as a tinted pill or chip draws it. */
    private fun over(color: Color, background: Color, alpha: Float) = Color(
        red = color.red * alpha + background.red * (1 - alpha),
        green = color.green * alpha + background.green * (1 - alpha),
        blue = color.blue * alpha + background.blue * (1 - alpha)
    )

    private fun lighter(color: Color, fraction: Float) = Color(
        red = color.red + (1 - color.red) * fraction,
        green = color.green + (1 - color.green) * fraction,
        blue = color.blue + (1 - color.blue) * fraction
    )

    @Test
    fun theFillsFailAsTextOnLightWhichIsWhyInksExist() {
        assertTrue(contrast(BrandGreen, SurfaceLight) < 3.0)
    }

    @Test
    fun everyInkReadsOnEverySurfaceAndInItsOwnTint() {
        for (fill in fills) {
            val lightInk = inkOf(fill, dark = false)
            val darkInk = inkOf(fill, dark = true)
            // StatusPill is 12% of the fill; a selected day or resolution chip 18%.
            for (surface in lightSurfaces) {
                for (background in listOf(surface, over(fill, surface, 0.12f), over(fill, surface, 0.18f))) {
                    assertTrue("$fill light on $background: ${contrast(lightInk, background)}", contrast(lightInk, background) >= 4.5)
                }
            }
            for (surface in darkSurfaces) {
                for (background in listOf(surface, over(fill, surface, 0.12f), over(fill, surface, 0.18f))) {
                    assertTrue("$fill dark on $background: ${contrast(darkInk, background)}", contrast(darkInk, background) >= 4.5)
                }
            }
        }
    }

    @Test
    fun textOnAnAccentFillReadsAcrossTheHeaderGradient() {
        // StatusBanner runs from the accent to the accent 28% towards white.
        for (accent in listOf(BrandGreen, ScreenTimePrimary, LogsPrimary)) {
            for (background in listOf(accent, lighter(accent, 0.28f), over(Color.White, accent, 0.22f))) {
                assertTrue("on $background: ${contrast(onAccent(accent), background)}", contrast(onAccent(accent), background) >= 4.5)
            }
        }
        assertTrue(contrast(onAccent(Success), Success) >= 4.5)
        // A filled banner chip is white in both themes, so it carries the light ink.
        for (accent in listOf(BrandGreen, ScreenTimePrimary, LogsPrimary)) {
            assertTrue(contrast(inkOf(accent, dark = false), Color.White) >= 4.5)
        }
    }

    @Test
    fun theLightPrimaryRoleIsTheInkWithWhiteOnIt() {
        // Material draws the primary role as text (TextButtons, a focused label) and as a fill.
        assertTrue(contrast(HealthInk, SurfaceLight) >= 4.5)
        assertTrue(contrast(Color.White, HealthInk) >= 4.5)
        assertEquals(OnPrimary, onAccent(BrandGreen))
    }

    @Test
    fun aColourWithoutAnInkIsLeftAlone() {
        assertEquals(TextPrimary, inkOf(TextPrimary, dark = false))
        assertEquals(TextPrimary, inkOf(TextPrimary, dark = true))
    }
}
