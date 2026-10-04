package com.owen282000.lifedashboard

/**
 * Which apps Screen Time sends (issue #63). The list of apps someone used is the most personal
 * thing this app sends, so it is a choice: leave some out, or send only a few.
 *
 * One list with a mode, never a blocklist and an allowlist at once. [ScreenTimeAppFilter.ALL],
 * the default, sends every app, as before. The built-in exclusion of System UI and the launcher
 * (ScreenTimeManager) stays underneath: this list applies to what is left.
 */
enum class AppFilterMode(val payloadName: String?) {
    /** Every app, as before. */
    ALL(null),

    /** Every app except the ones on the list. */
    BLOCKLIST("blocklist"),

    /** Only the apps on the list. */
    ALLOWLIST("allowlist");

    companion object {
        /** A stored name, [ALL] for anything unknown, so a damaged setting sends what it always did. */
        fun from(name: String?): AppFilterMode = entries.firstOrNull { it.name == name } ?: ALL
    }
}

data class ScreenTimeAppFilter(
    val mode: AppFilterMode = AppFilterMode.ALL,
    /** Package names. Kept when the mode is [AppFilterMode.ALL], so switching back restores the list. */
    val packages: Set<String> = emptySet()
) {
    val active: Boolean get() = mode != AppFilterMode.ALL

    fun admits(packageName: String): Boolean = when (mode) {
        AppFilterMode.ALL -> true
        AppFilterMode.BLOCKLIST -> packageName !in packages
        AppFilterMode.ALLOWLIST -> packageName in packages
    }

    /**
     * [days] with only the apps the filter admits. A day keeps its real total
     * ([ScreenTimeData.totalScreenTimeMs]), so the numbers a receiver stored before go on
     * meaning screen time, and gets the sum of the apps that are left as
     * [ScreenTimeData.filteredScreenTimeMs]. A day whose apps are all filtered out stays, with
     * no apps and a filtered sum of 0: the phone was still used that day.
     */
    fun apply(days: List<ScreenTimeData>): List<ScreenTimeData> {
        if (!active) return days
        return days.map { day ->
            val apps = day.apps.filter { admits(it.packageName) }
            day.copy(apps = apps, filteredScreenTimeMs = apps.sumOf { it.totalTimeMs })
        }
    }

    companion object {
        val ALL = ScreenTimeAppFilter()
    }
}

/** An app the picker offers: one that showed up in the usage statistics, or one already on the list. */
data class AppChoice(
    val packageName: String,
    val name: String,
    /** Foreground minutes over the period the picker looked at; 0 for an app only on the list. */
    val minutes: Long
)
