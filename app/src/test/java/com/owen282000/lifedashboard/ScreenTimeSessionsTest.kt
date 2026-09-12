package com.owen282000.lifedashboard

import android.app.usage.UsageEvents
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Screen time session pairing. A user reported per-app minutes far above Digital Wellbeing
 * (a weather app at 905 minutes on a day with 4 hours of real use); these tests pin the rules
 * that keep a missed pause, overlapping activities or system packages from inflating totals.
 */
class ScreenTimeSessionsTest {

    private val dayStart = 1_800_000_000_000L
    private val minute = 60_000L
    private val dayEnd = dayStart + 24 * 60 * minute
    private val now = dayEnd + 60 * minute

    private fun ev(type: Int, min: Long, pkg: String = "com.example.weather", cls: String? = "Main") =
        UsageEventSnapshot(pkg, cls, type, dayStart + min * minute)

    private fun aggregate(vararg events: UsageEventSnapshot, excluded: Set<String> = emptySet()) =
        ScreenTimeSessions.aggregate(events.toList(), dayStart, dayEnd, now, excluded)

    @Test
    fun resumeAndPauseCountTheTimeBetween() {
        val result = aggregate(
            ev(UsageEvents.Event.ACTIVITY_RESUMED, 10),
            ev(UsageEvents.Event.ACTIVITY_PAUSED, 25)
        )
        assertEquals(15 * minute, result.getValue("com.example.weather").foregroundMs)
        assertEquals(dayStart + 25 * minute, result.getValue("com.example.weather").lastUsedMs)
    }

    @Test
    fun screenOffEndsASessionWhosePauseWasNeverRecorded() {
        val result = aggregate(
            ev(UsageEvents.Event.ACTIVITY_RESUMED, 10),
            ev(UsageEvents.Event.SCREEN_NON_INTERACTIVE, 14, pkg = "android", cls = null),
            ev(UsageEvents.Event.SCREEN_INTERACTIVE, 600, pkg = "android", cls = null)
        )
        assertEquals(4 * minute, result.getValue("com.example.weather").foregroundMs)
    }

    @Test
    fun keyguardAndShutdownAlsoEndOpenSessions() {
        val keyguard = aggregate(
            ev(UsageEvents.Event.ACTIVITY_RESUMED, 0),
            ev(UsageEvents.Event.KEYGUARD_SHOWN, 3, pkg = "android", cls = null)
        )
        assertEquals(3 * minute, keyguard.getValue("com.example.weather").foregroundMs)

        val shutdown = aggregate(
            ev(UsageEvents.Event.ACTIVITY_RESUMED, 0),
            ev(UsageEvents.Event.DEVICE_SHUTDOWN, 7, pkg = "android", cls = null),
            ev(UsageEvents.Event.DEVICE_STARTUP, 9, pkg = "android", cls = null)
        )
        assertEquals(7 * minute, shutdown.getValue("com.example.weather").foregroundMs)
    }

    @Test
    fun stoppedWithoutPausedClosesTheSession() {
        val result = aggregate(
            ev(UsageEvents.Event.ACTIVITY_RESUMED, 0),
            ev(UsageEvents.Event.ACTIVITY_STOPPED, 5)
        )
        assertEquals(5 * minute, result.getValue("com.example.weather").foregroundMs)
    }

    @Test
    fun stoppedAfterPausedDoesNotCountTwice() {
        val result = aggregate(
            ev(UsageEvents.Event.ACTIVITY_RESUMED, 0),
            ev(UsageEvents.Event.ACTIVITY_PAUSED, 5),
            ev(UsageEvents.Event.ACTIVITY_STOPPED, 6)
        )
        assertEquals(5 * minute, result.getValue("com.example.weather").foregroundMs)
    }

    @Test
    fun activitiesOfOnePackageFormOneSession() {
        // Main opens Detail: Main pauses, Detail resumes, later Detail pauses and Main resumes,
        // and finally Main leaves. The package was in the foreground the whole 20 minutes.
        val result = aggregate(
            ev(UsageEvents.Event.ACTIVITY_RESUMED, 0, cls = "Main"),
            ev(UsageEvents.Event.ACTIVITY_PAUSED, 5, cls = "Main"),
            ev(UsageEvents.Event.ACTIVITY_RESUMED, 5, cls = "Detail"),
            ev(UsageEvents.Event.ACTIVITY_STOPPED, 5, cls = "Main"),
            ev(UsageEvents.Event.ACTIVITY_PAUSED, 15, cls = "Detail"),
            ev(UsageEvents.Event.ACTIVITY_RESUMED, 15, cls = "Main"),
            ev(UsageEvents.Event.ACTIVITY_STOPPED, 15, cls = "Detail"),
            ev(UsageEvents.Event.ACTIVITY_PAUSED, 20, cls = "Main")
        )
        assertEquals(20 * minute, result.getValue("com.example.weather").foregroundMs)
    }

    @Test
    fun overlappingActivitiesDoNotOverwriteOrLoseTheSession() {
        // Detail resumes before Main pauses (reversed order): the package stays open until the
        // last resumed activity leaves.
        val result = aggregate(
            ev(UsageEvents.Event.ACTIVITY_RESUMED, 0, cls = "Main"),
            ev(UsageEvents.Event.ACTIVITY_RESUMED, 10, cls = "Detail"),
            ev(UsageEvents.Event.ACTIVITY_PAUSED, 10, cls = "Main"),
            ev(UsageEvents.Event.ACTIVITY_PAUSED, 30, cls = "Detail")
        )
        assertEquals(30 * minute, result.getValue("com.example.weather").foregroundMs)
    }

    @Test
    fun pauseWithoutResumeIsIgnored() {
        val result = aggregate(ev(UsageEvents.Event.ACTIVITY_PAUSED, 30))
        assertNull(result["com.example.weather"])
    }

    @Test
    fun sessionOpenAtEndOfEventsCountsUpToNowButNeverPastDayEnd() {
        val pastDay = aggregate(ev(UsageEvents.Event.ACTIVITY_RESUMED, 23 * 60 + 30))
        assertEquals(30 * minute, pastDay.getValue("com.example.weather").foregroundMs)

        val today = ScreenTimeSessions.aggregate(
            listOf(ev(UsageEvents.Event.ACTIVITY_RESUMED, 100)),
            dayStart, dayEnd, nowMs = dayStart + 112 * minute
        )
        assertEquals(12 * minute, today.getValue("com.example.weather").foregroundMs)
    }

    @Test
    fun timeBeforeDayStartIsNotCounted() {
        val result = ScreenTimeSessions.aggregate(
            listOf(
                UsageEventSnapshot("com.example.weather", "Main", UsageEvents.Event.ACTIVITY_RESUMED, dayStart - 10 * minute),
                ev(UsageEvents.Event.ACTIVITY_PAUSED, 5)
            ),
            dayStart, dayEnd, now
        )
        assertEquals(5 * minute, result.getValue("com.example.weather").foregroundMs)
    }

    @Test
    fun excludedPackagesNeverAppearWhileDeviceEventsStillApply() {
        val result = aggregate(
            ev(UsageEvents.Event.ACTIVITY_RESUMED, 0, pkg = ScreenTimeSessions.SYSTEM_UI_PACKAGE),
            ev(UsageEvents.Event.ACTIVITY_RESUMED, 0, pkg = "com.example.launcher"),
            ev(UsageEvents.Event.ACTIVITY_RESUMED, 1),
            ev(UsageEvents.Event.SCREEN_NON_INTERACTIVE, 4, pkg = "android", cls = null),
            excluded = setOf(ScreenTimeSessions.SYSTEM_UI_PACKAGE, "com.example.launcher")
        )
        assertFalse(ScreenTimeSessions.SYSTEM_UI_PACKAGE in result)
        assertFalse("com.example.launcher" in result)
        assertEquals(3 * minute, result.getValue("com.example.weather").foregroundMs)
    }

    @Test
    fun independentPackagesAreCountedSeparately() {
        val result = aggregate(
            ev(UsageEvents.Event.ACTIVITY_RESUMED, 0, pkg = "a"),
            ev(UsageEvents.Event.ACTIVITY_PAUSED, 10, pkg = "a"),
            ev(UsageEvents.Event.ACTIVITY_RESUMED, 10, pkg = "b"),
            ev(UsageEvents.Event.ACTIVITY_PAUSED, 25, pkg = "b"),
            ev(UsageEvents.Event.ACTIVITY_RESUMED, 25, pkg = "a"),
            ev(UsageEvents.Event.ACTIVITY_PAUSED, 27, pkg = "a")
        )
        assertEquals(12 * minute, result.getValue("a").foregroundMs)
        assertEquals(15 * minute, result.getValue("b").foregroundMs)
    }
}
