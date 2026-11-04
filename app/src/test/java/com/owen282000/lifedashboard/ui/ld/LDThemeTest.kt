package com.owen282000.lifedashboard.ui.ld

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

/** The kit's tokens measured against what they are drawn on, in both themes and both accents, as UIParityTests does on iOS. */
class LDThemeTest {

    private fun luminance(c: Color): Double {
        fun channel(v: Float) = if (v <= 0.04045f) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
        return 0.2126 * channel(c.red) + 0.7152 * channel(c.green) + 0.0722 * channel(c.blue)
    }

    private fun contrast(a: Color, b: Color): Double {
        val (high, low) = listOf(luminance(a), luminance(b)).sortedDescending()
        return (high + 0.05) / (low + 0.05)
    }

    private val palettes = listOf(true, false).flatMap { dark -> LDAccent.entries.map { accent -> "dark=$dark $accent" to ldColors(dark, accent) } }

    private fun hold(minimum: Double, what: String, pair: (LDColors) -> Pair<Color, Color>) {
        palettes.forEach { (name, colors) ->
            val (a, b) = pair(colors)
            val measured = contrast(a, b)
            assertTrue("$what in $name is ${"%.2f".format(measured)}:1", measured >= minimum)
        }
    }

    @Test
    fun `the text tones hold 4,5 to 1 on the surfaces they are drawn on`() {
        listOf<Pair<String, (LDColors) -> Color>>(
            "text" to { it.text },
            "muted" to { it.muted },
            "secondary" to { it.secondary },
            "inactive" to { it.inactive },
            "accentText" to { it.accentText },
            "accentTextSoft" to { it.accentTextSoft }
        ).forEach { (name, tone) ->
            hold(4.5, "$name on ground") { tone(it) to it.ground }
            hold(4.5, "$name on tile") { tone(it) to it.tile }
            hold(4.5, "$name on sheet") { tone(it) to it.sheet }
        }
    }

    @Test
    fun `what is on an accent fill holds 4,5 to 1`() {
        hold(4.5, "onAccent on accent") { it.onAccent to it.accent }
    }

    @Test
    fun `the line, the fills and the icons hold 3 to 1`() {
        hold(3.0, "accent on ground") { it.accent to it.ground }
        hold(3.0, "accent on tile") { it.accent to it.tile }
        hold(3.0, "icon on tile") { it.icon to it.tile }
        hold(3.0, "warning on ground") { it.warning to it.ground }
    }

    @Test
    fun `the warning card holds 4,5 to 1`() {
        hold(4.5, "warning on its surface") { it.warning to it.warningSurface }
        hold(4.5, "warning body on its surface") { it.warningBody to it.warningSurface }
        hold(4.5, "warning text on its surface") { it.warningText to it.warningSurface }
    }

    @Test
    fun `the green tokens are the brief's`() {
        val dark = ldColors(dark = true)
        assertEquals(Color(0xFF0A0F0D), dark.ground)
        assertEquals(Color(0xFF30B77E), dark.accent)
        assertEquals(Color(0xFF141C18), dark.tile)
        assertEquals(Color(0xFF0E1512), dark.sheet)
        assertEquals("light takes the iOS values", Color(0xFFF3F6F4), ldColors(dark = false).ground)
    }

    @Test
    fun `Screen Time changes the accent family and nothing else`() {
        val green = ldColors(dark = true)
        val purple = ldColors(dark = true, accent = LDAccent.Purple)
        assertEquals(Color(0xFF9B7BF7), purple.accent)
        assertEquals(green.ground, purple.ground)
        assertEquals(green.text, purple.text)
        assertEquals(green.warning, purple.warning)
    }

    @Test
    fun `the footer gives equal widths while every label fits`() {
        val lines = LDFooterLines.lines(listOf(60, 80), 300, 8)
        assertEquals(1, lines.size)
        assertEquals(listOf(146, 146), lines.single().map { it.width })
    }

    @Test
    fun `the footer goes by the labels when equal widths would wrap one`() {
        val line = LDFooterLines.lines(listOf(60, 200), 300, 8).single()
        assertEquals(listOf(76, 216), line.map { it.width })
    }

    @Test
    fun `the footer goes on more lines when the labels do not fit on one`() {
        val lines = LDFooterLines.lines(listOf(200, 200, 50), 300, 8)
        assertEquals(listOf(1, 2), lines.map { it.size })
        assertEquals(300, lines.first().single().width)
    }
}
