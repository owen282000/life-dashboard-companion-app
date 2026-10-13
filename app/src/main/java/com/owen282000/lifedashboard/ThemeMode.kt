package com.owen282000.lifedashboard

/**
 * Light or dark for the whole app, chosen in About (S-02). [SYSTEM] follows the phone's dark
 * mode, which is all the app did before there was a choice, and stays the default. The widget
 * is drawn by the launcher and keeps following the phone whatever is chosen here.
 *
 * [key] is what the settings and a backup store, so it stays the same when a name changes.
 */
enum class ThemeMode(val key: String) {
    SYSTEM("system"),
    LIGHT("light"),
    DARK("dark");

    /** Whether the app draws dark, given whether the phone is in dark mode right now. */
    fun isDark(systemDark: Boolean): Boolean = when (this) {
        SYSTEM -> systemDark
        LIGHT -> false
        DARK -> true
    }

    companion object {
        /** The mode stored under [key]; null for nothing or for a key this build does not know. */
        fun fromKey(key: String?): ThemeMode? = entries.firstOrNull { it.key == key }
    }
}
