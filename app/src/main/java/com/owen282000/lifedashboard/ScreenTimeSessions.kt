package com.owen282000.lifedashboard

import android.app.usage.UsageEvents

/** One usage event, decoupled from [UsageEvents.Event] so the aggregation can be unit tested. */
data class UsageEventSnapshot(
    val packageName: String,
    val className: String?,
    val eventType: Int,
    val timestampMs: Long
)

/** Foreground time of one package within a day, plus the last event seen for it. */
data class PackageForeground(
    val foregroundMs: Long,
    val lastUsedMs: Long
)

/**
 * Turns a day's usage events into per-package foreground time.
 *
 * A package is in the foreground while at least one of its activities is resumed, so sessions
 * are tracked per activity and only closed when the last resumed activity of the package pauses
 * or stops. Screen off, keyguard and shutdown end every open session, which keeps a missed
 * ACTIVITY_PAUSED from being counted until the end of the day. The event constants are inlined
 * at compile time; on devices too old to emit the newer ones the branch simply never matches.
 */
@Suppress("InlinedApi")
object ScreenTimeSessions {

    /** Packages that are never reported, matching what Digital Wellbeing hides. */
    const val SYSTEM_UI_PACKAGE = "com.android.systemui"

    /**
     * The launchers among the activities that answer the HOME intent, given as package and
     * activity name. Settings answers it too, with FallbackHome, the screen Android shows
     * before the phone is unlocked after a restart; counting that as a launcher hid every
     * minute spent in Settings. It is left out, and so is every other FallbackHome.
     */
    fun launcherPackages(homeActivities: List<Pair<String, String>>): Set<String> =
        homeActivities.filterNot { (_, activity) -> activity.endsWith(".FallbackHome") }.map { it.first }.toSet()

    /**
     * How long before the start of a day its events are read. A session that began before
     * midnight (or the day boundary) and ended after it has its resume there; without it the
     * pause has nothing to pair with and the minutes after midnight are lost. Bounded, so that
     * on a phone before Android 9, which reports no screen off, an evening's missed pause cannot
     * open the whole next day.
     */
    const val CARRY_OVER_MS = 6 * 60 * 60 * 1000L

    /** Where to start reading the events of the day that starts at [dayStartMs]. */
    fun queryStartMs(dayStartMs: Long): Long = dayStartMs - CARRY_OVER_MS

    /**
     * [events] are the day's events in time order, read from [queryStartMs]: the ones before
     * [dayStartMs] only open sessions, whose time counts from the start of the day.
     */
    fun aggregate(
        events: List<UsageEventSnapshot>,
        dayStartMs: Long,
        dayEndMs: Long,
        nowMs: Long,
        excludedPackages: Set<String> = emptySet()
    ): Map<String, PackageForeground> {
        val foreground = mutableMapOf<String, Long>()
        val lastUsed = mutableMapOf<String, Long>()
        val resumedActivities = mutableMapOf<String, MutableSet<String>>()
        val packageStart = mutableMapOf<String, Long>()

        fun close(packageName: String, atMs: Long) {
            val start = packageStart.remove(packageName) ?: return
            resumedActivities.remove(packageName)
            val effectiveStart = maxOf(start, dayStartMs)
            val effectiveEnd = minOf(atMs, dayEndMs)
            if (effectiveEnd > effectiveStart) {
                foreground[packageName] = (foreground[packageName] ?: 0L) + (effectiveEnd - effectiveStart)
            }
        }

        for (event in events) {
            when (event.eventType) {
                // Device-wide events: nothing can be in the foreground after these.
                UsageEvents.Event.SCREEN_NON_INTERACTIVE,
                UsageEvents.Event.KEYGUARD_SHOWN,
                UsageEvents.Event.DEVICE_SHUTDOWN -> {
                    packageStart.keys.toList().forEach { close(it, event.timestampMs) }
                }

                // ACTIVITY_RESUMED shares its value with the older MOVE_TO_FOREGROUND.
                UsageEvents.Event.ACTIVITY_RESUMED -> {
                    if (event.packageName in excludedPackages) continue
                    val activities = resumedActivities.getOrPut(event.packageName) { mutableSetOf() }
                    if (activities.isEmpty()) {
                        packageStart.putIfAbsent(event.packageName, event.timestampMs)
                    }
                    activities.add(event.className ?: "")
                    lastUsed[event.packageName] = event.timestampMs
                }

                // ACTIVITY_PAUSED shares its value with the older MOVE_TO_BACKGROUND.
                UsageEvents.Event.ACTIVITY_PAUSED,
                UsageEvents.Event.ACTIVITY_STOPPED -> {
                    if (event.packageName in excludedPackages) continue
                    val activities = resumedActivities[event.packageName]
                    if (activities != null) {
                        activities.remove(event.className ?: "")
                        if (activities.isEmpty()) close(event.packageName, event.timestampMs)
                    }
                    lastUsed[event.packageName] = event.timestampMs
                }
            }
        }

        // Still open when the events run out: count up to now, never past the end of the day.
        packageStart.keys.toList().forEach { close(it, minOf(dayEndMs, nowMs)) }

        // A session carried over from before the day was in use when the day started.
        return foreground.mapValues { (packageName, ms) ->
            PackageForeground(ms, maxOf(lastUsed[packageName] ?: dayEndMs, dayStartMs))
        }
    }
}
