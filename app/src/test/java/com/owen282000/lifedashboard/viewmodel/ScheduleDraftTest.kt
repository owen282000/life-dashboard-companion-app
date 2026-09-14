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
import java.time.DayOfWeek
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
        assertEquals(UiMessage.IntervalTooShort, SettingsRules.scheduleProblem(draft))
    }

    @Test
    fun `times mode needs at least one time`() {
        val draft = ScheduleDraft(mode = SyncMode.TIMES, times = emptyList())
        assertEquals(UiMessage.NoSyncTimes, SettingsRules.scheduleProblem(draft))
    }

    @Test
    fun `the interval floor does not apply to fixed times`() {
        // Two syncs five minutes apart is the user's call: fixed times run as one-time work.
        val draft = ScheduleDraft(
            mode = SyncMode.TIMES,
            intervalText = "5",
            times = listOf(time("08:00"), time("08:05"))
        )
        assertNull(SettingsRules.scheduleProblem(draft))
    }

    @Test
    fun `a schedule that can never run is refused with its own message`() {
        val noDays = ScheduleDraft(mode = SyncMode.INTERVAL, intervalText = "60", days = emptySet())
        assertEquals(UiMessage.ScheduleNeverRuns, SettingsRules.scheduleProblem(noDays))

        val allTimesQuiet = ScheduleDraft(
            mode = SyncMode.TIMES,
            times = listOf(time("03:00")),
            quietFrom = time("23:00"),
            quietTo = time("07:00")
        )
        assertEquals(UiMessage.ScheduleNeverRuns, SettingsRules.scheduleProblem(allTimesQuiet))
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
        assertNull(SettingsRules.scheduleProblem(draft))
        assertFalse(draft.wouldNeverRun())
    }

    @Test
    fun `the quiet window needs both ends before it counts`() {
        assertFalse(ScheduleDraft(quietFrom = time("23:00")).quietEnabled)
        assertFalse(ScheduleDraft(quietTo = time("07:00")).quietEnabled)
        assertTrue(ScheduleDraft(quietFrom = time("23:00"), quietTo = time("07:00")).quietEnabled)
    }

    @Test
    fun `switching mode back and forth is not an unsaved change`() {
        val saved = HealthDraft(WebhookDraft(), emptySet(), emptyMqtt(), ScheduleDraft(intervalText = "60"))
        val toTimesAndBack = saved.copy(
            schedule = saved.schedule.copy(mode = SyncMode.TIMES).copy(mode = SyncMode.INTERVAL)
        )
        assertFalse(toTimesAndBack.differsFrom(saved))
    }

    @Test
    fun `in interval mode the times do not count as a change, and the other way around`() {
        val saved = HealthDraft(WebhookDraft(), emptySet(), emptyMqtt(), ScheduleDraft(intervalText = "60"))
        val withTimes = saved.copy(schedule = saved.schedule.copy(times = listOf(time("08:00"))))
        assertFalse(withTimes.differsFrom(saved))

        val savedTimes = saved.copy(
            schedule = saved.schedule.copy(mode = SyncMode.TIMES, times = listOf(time("08:00")))
        )
        val withOtherInterval = savedTimes.copy(schedule = savedTimes.schedule.copy(intervalText = "120"))
        assertFalse(withOtherInterval.differsFrom(savedTimes))
    }

    @Test
    fun `editing days, times or quiet hours is an unsaved change`() {
        val saved = HealthDraft(WebhookDraft(), emptySet(), emptyMqtt(), ScheduleDraft(intervalText = "60"))
        assertTrue(saved.copy(schedule = saved.schedule.copy(days = WEEKDAYS)).differsFrom(saved))
        assertTrue(
            saved.copy(schedule = saved.schedule.copy(quietFrom = time("23:00"), quietTo = time("07:00")))
                .differsFrom(saved)
        )
        val savedTimes = saved.copy(schedule = saved.schedule.copy(mode = SyncMode.TIMES, times = listOf(time("08:00"))))
        assertTrue(
            savedTimes.copy(schedule = savedTimes.schedule.copy(times = listOf(time("09:00"))))
                .differsFrom(savedTimes)
        )
    }

    @Test
    fun `screen time drafts compare their schedule too`() {
        val saved = ScreenTimeDraft(WebhookDraft(), "4", true, emptyMqtt(), ScheduleDraft(intervalText = "60"))
        assertFalse(saved.copy().differsFrom(saved))
        assertTrue(saved.copy(schedule = saved.schedule.copy(days = setOf(DayOfWeek.MONDAY))).differsFrom(saved))
    }
}
