package com.owen282000.lifedashboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [InMemoryPrefs] is the fallback used when the keystore cannot be opened. The property that
 * matters for security is that it is not a file: a keystore outage must never cause secrets to
 * be written to plain, backup-eligible SharedPreferences (P0-1).
 *
 * These tests pin the behaviour PreferencesManager relies on.
 */
class InMemoryPrefsTest {

    @Test
    fun readsReturnDefaultsWhenEmpty() {
        val prefs = InMemoryPrefs()
        assertNull(prefs.getString("health_webhook_secret", null))
        assertEquals("fallback", prefs.getString("missing", "fallback"))
        assertFalse(prefs.getBoolean("missing", false))
        assertEquals(7, prefs.getInt("missing", 7))
    }

    @Test
    fun writesAreVisibleWithinTheInstance() {
        val prefs = InMemoryPrefs()
        prefs.edit().putString("token", "abc").apply()
        assertEquals("abc", prefs.getString("token", null))
    }

    @Test
    fun nothingSurvivesANewInstance() {
        InMemoryPrefs().edit().putString("token", "abc").apply()
        // A new instance models a new process: the outage must not have persisted anything.
        assertNull(InMemoryPrefs().getString("token", null))
    }

    @Test
    fun removeAndClearBehaveLikeSharedPreferences() {
        val prefs = InMemoryPrefs()
        prefs.edit().putString("a", "1").putString("b", "2").apply()

        prefs.edit().remove("a").apply()
        assertNull(prefs.getString("a", null))
        assertEquals("2", prefs.getString("b", null))

        prefs.edit().clear().apply()
        assertNull(prefs.getString("b", null))
        assertTrue(prefs.all.isEmpty())
    }

    @Test
    fun putNullStringRemovesTheKey() {
        val prefs = InMemoryPrefs()
        prefs.edit().putString("a", "1").apply()
        prefs.edit().putString("a", null).apply()
        assertFalse(prefs.contains("a"))
    }

    @Test
    fun listenersAreNotifiedForChangedKeys() {
        val prefs = InMemoryPrefs()
        val seen = mutableListOf<String>()
        prefs.registerOnSharedPreferenceChangeListener { _, key -> key?.let { seen.add(it) } }

        prefs.edit().putString("a", "1").apply()
        assertEquals(listOf("a"), seen)
    }

    @Test
    fun commitAppliesTheSameWayAsApply() {
        val prefs = InMemoryPrefs()
        assertTrue(prefs.edit().putInt("n", 42).commit())
        assertEquals(42, prefs.getInt("n", 0))
    }
}
