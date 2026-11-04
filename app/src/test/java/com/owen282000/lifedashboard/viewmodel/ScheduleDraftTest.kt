package com.owen282000.lifedashboard.viewmodel

import com.owen282000.lifedashboard.QuietWindow
import com.owen282000.lifedashboard.SyncMode
import com.owen282000.lifedashboard.SyncSchedule
import com.owen282000.lifedashboard.WEEKDAYS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalTime

/**
 * The editing rules around a schedule: what the save button may write, when it lights up,
 * and what the user is told when the settings would silently stop the syncing.
 */
class ScheduleDraftTest {

    private fun time(text: String): LocalTime = LocalTime.parse(text)

    @Test
    fun `a draft round-trips through the domain schedule`() {
        val schedule = SyncSchedule(
            mode = SyncMode.TIMES,
            intervalMinutes = 30,
            times = listOf(time("08:00"), time("21:00")),
            days = WEEKDAYS,
            quietWindow = QuietWindow(time("23:00"), time("07:00"))
        )
        assertEquals(schedule, ScheduleDraft.from(schedule).toSchedule())
    }

    @Test
    fun `an interval that is still being typed falls back to the saved value`() {
        val draft = ScheduleDraft(mode = SyncMode.INTERVAL, intervalText = "")
        assertEquals(45, draft.toSchedule(fallbackInterval = 45).intervalMinutes)
    }

    @Test
    fun `an interval under the floor is rejected, not silently clamped`() {
        val draft = ScheduleDraft(mode = SyncMode.INTERVAL, intervalText = "5")
        assertNull(SettingsRules.intervalOrNull(draft.intervalText))
        assertEquals("the stored one stays, see saveInterval", 60, draft.toSchedule(fallbackInterval = 60).intervalMinutes)
    }

    @Test
    fun `times mode without a time never runs, which the screen warns about`() {
        val draft = ScheduleDraft(mode = SyncMode.TIMES, times = emptyList())
        assertTrue(draft.wouldNeverRun())
    }

    @Test
    fun `the interval floor does not apply to fixed times`() {
        // Two syncs five minutes apart is the user's call: fixed times run as one-time work.
        val draft = ScheduleDraft(
            mode = SyncMode.TIMES,
            intervalText = "5",
            times = listOf(time("08:00"), time("08:05"))
        )
        assertFalse(draft.wouldNeverRun())
        assertEquals(listOf(time("08:00"), time("08:05")), draft.toSchedule().times)
    }

    @Test
    fun `a schedule that can never run is told apart`() {
        val noDays = ScheduleDraft(mode = SyncMode.INTERVAL, intervalText = "60", days = emptySet())
        assertTrue(noDays.wouldNeverRun())

        val allTimesQuiet = ScheduleDraft(
            mode = SyncMode.TIMES,
            times = listOf(time("03:00")),
            quietFrom = time("23:00"),
            quietTo = time("07:00")
        )
        assertTrue(allTimesQuiet.wouldNeverRun())
    }

    @Test
    fun `a normal schedule has nothing to complain about`() {
        val draft = ScheduleDraft(
            mode = SyncMode.TIMES,
            times = listOf(time("08:00"), time("21:00")),
            days = WEEKDAYS,
            quietFrom = time("23:00"),
            quietTo = time("07:00")
        )
        assertFalse(draft.wouldNeverRun())
    }

    @Test
    fun `the quiet window needs both ends before it counts`() {
        assertFalse(ScheduleDraft(quietFrom = time("23:00")).quietEnabled)
        assertFalse(ScheduleDraft(quietTo = time("07:00")).quietEnabled)
        assertTrue(ScheduleDraft(quietFrom = time("23:00"), quietTo = time("07:00")).quietEnabled)
    }

    @Test
    fun `switching mode back and forth is no change for WorkManager`() {
        val saved = ScheduleDraft(intervalText = "60")
        assertFalse(saved.copy(mode = SyncMode.TIMES).copy(mode = SyncMode.INTERVAL).toSchedule().differsFrom(saved.toSchedule()))
    }

    @Test
    fun `the order of the times and doubles among them are no change`() {
        val saved = ScheduleDraft(mode = SyncMode.TIMES, times = listOf(time("08:00"), time("21:00")))
        val shuffled = saved.copy(times = listOf(time("21:00"), time("08:00"), time("08:00")))
        assertFalse(shuffled.toSchedule().differsFrom(saved.toSchedule()))
    }

    @Test
    fun `editing the interval, days, times or quiet hours is a change`() {
        val saved = ScheduleDraft(intervalText = "60")
        fun differs(next: ScheduleDraft, from: ScheduleDraft = saved) = next.toSchedule().differsFrom(from.toSchedule())
        assertTrue(differs(saved.copy(intervalText = "120")))
        assertTrue(differs(saved.copy(days = WEEKDAYS)))
        assertTrue(differs(saved.copy(quietFrom = time("23:00"), quietTo = time("07:00"))))
        val savedTimes = saved.copy(mode = SyncMode.TIMES, times = listOf(time("08:00")))
        assertTrue(differs(savedTimes.copy(times = listOf(time("09:00"))), savedTimes))
    }
}
