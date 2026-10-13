package com.owen282000.lifedashboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The app's own light or dark choice (S-02), next to the phone's dark mode. */
class ThemeModeTest {

    @Test
    fun systemFollowsThePhone() {
        assertTrue(ThemeMode.SYSTEM.isDark(systemDark = true))
        assertFalse(ThemeMode.SYSTEM.isDark(systemDark = false))
    }

    @Test
    fun lightAndDarkHoldWhateverThePhoneDoes() {
        for (systemDark in listOf(true, false)) {
            assertFalse(ThemeMode.LIGHT.isDark(systemDark))
            assertTrue(ThemeMode.DARK.isDark(systemDark))
        }
    }

    @Test
    fun theStoredKeysStayAsTheyAre() {
        // Settings and backups hold these, so renaming an entry must not change them.
        assertEquals(listOf("system", "light", "dark"), ThemeMode.entries.map { it.key })
        ThemeMode.entries.forEach { assertEquals(it, ThemeMode.fromKey(it.key)) }
    }

    @Test
    fun nothingOrAnUnknownKeyIsNoMode() {
        assertNull(ThemeMode.fromKey(null))
        assertNull(ThemeMode.fromKey(""))
        assertNull(ThemeMode.fromKey("sepia"))
        assertNull("keys are lower case", ThemeMode.fromKey("DARK"))
    }
}
