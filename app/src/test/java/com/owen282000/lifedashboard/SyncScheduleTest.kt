package com.owen282000.lifedashboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime

class SyncScheduleTest {

    private fun at(text: String) = LocalDateTime.parse(text)
    private fun time(text: String): LocalTime = LocalTime.parse(text)

    // ==================== Interval mode ====================

    @Test
    fun `interval mode counts from the last run`() {
        val schedule = SyncSchedule(mode = SyncMode.INTERVAL, intervalMinutes = 60)
        val next = schedule.nextRun(after = at("2026-09-14T10:05:00"), lastRun = at("2026-09-14T10:00:00"))
        assertEquals(at("2026-09-14T11:00:00"), next)
    }

    @Test
    fun `an overdue interval run is due immediately`() {
        val schedule = SyncSchedule(mode = SyncMode.INTERVAL, intervalMinutes = 60)
        val next = schedule.nextRun(after = at("2026-09-14T14:00:00"), lastRun = at("2026-09-14T10:00:00"))
        assertEquals(at("2026-09-14T14:00:00"), next)
    }

    @Test
    fun `without a last run the interval starts now`() {
        val schedule = SyncSchedule(mode = SyncMode.INTERVAL, intervalMinutes = 30)
        assertEquals(at("2026-09-14T10:05:00"), schedule.nextRun(after = at("2026-09-14T10:05:00")))
    }

    // ==================== Times mode ====================

    @Test
    fun `times mode picks the next time today`() {
        val schedule = SyncSchedule(mode = SyncMode.TIMES, times = listOf(time("08:00"), time("21:00")))
        assertEquals(at("2026-09-14T21:00:00"), schedule.nextRun(after = at("2026-09-14T09:30:00")))
    }

    @Test
    fun `after the last time of the day it rolls over to tomorrow`() {
        val schedule = SyncSchedule(mode = SyncMode.TIMES, times = listOf(time("08:00"), time("21:00")))
        assertEquals(at("2026-09-15T08:00:00"), schedule.nextRun(after = at("2026-09-14T22:00:00")))
    }

    @Test
    fun `a time exactly now still counts as the next run`() {
        val schedule = SyncSchedule(mode = SyncMode.TIMES, times = listOf(time("08:00")))
        assertEquals(at("2026-09-14T08:00:00"), schedule.nextRun(after = at("2026-09-14T08:00:00")))
    }

    @Test
    fun `unsorted and duplicated times behave like the sorted distinct list`() {
        val schedule = SyncSchedule(
            mode = SyncMode.TIMES,
            times = listOf(time("21:00"), time("08:00"), time("08:00"))
        )
        assertEquals(at("2026-09-14T08:00:00"), schedule.nextRun(after = at("2026-09-14T07:00:00")))
    }

    @Test
    fun `a run that fired seconds early is not followed by a second one for the same slot`() {
        // WorkManager honours the delay to the millisecond, but a run can still finish
        // before the minute ticks over; the last run acts as a floor of one minute.
        val schedule = SyncSchedule(mode = SyncMode.TIMES, times = listOf(time("08:00"), time("21:00")))
        val next = schedule.nextRun(after = at("2026-09-14T07:59:58"), lastRun = at("2026-09-14T07:59:57"))
        assertEquals(at("2026-09-14T21:00:00"), next)
    }

    @Test
    fun `times mode without times never runs`() {
        val schedule = SyncSchedule(mode = SyncMode.TIMES, times = emptyList())
        assertTrue(schedule.isNeverRunning)
        assertNull(schedule.nextRun(after = at("2026-09-14T10:00:00")))
    }

    // ==================== Weekday filter ====================

    @Test
    fun `a weekday filter skips to the next allowed day`() {
        // 2026-09-14 is a Monday, so Saturday the 19th is the first allowed day.
        val schedule = SyncSchedule(
            mode = SyncMode.TIMES,
            times = listOf(time("08:00")),
            days = setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)
        )
        assertEquals(at("2026-09-19T08:00:00"), schedule.nextRun(after = at("2026-09-14T10:00:00")))
    }

    @Test
    fun `the weekday filter applies to interval mode too`() {
        val schedule = SyncSchedule(
            mode = SyncMode.INTERVAL,
            intervalMinutes = 60,
            days = WEEKDAYS
        )
        // Saturday 2026-09-19 at noon; the next weekday is Monday the 21st at midnight.
        assertEquals(at("2026-09-21T00:00:00"), schedule.nextRun(after = at("2026-09-19T12:00:00")))
    }

    @Test
    fun `no days at all never runs`() {
        val schedule = SyncSchedule(days = emptySet())
        assertTrue(schedule.isNeverRunning)
        assertNull(schedule.nextRun(after = at("2026-09-14T10:00:00")))
    }

    // ==================== Quiet window ====================

    @Test
    fun `a quiet window over midnight pushes the run to its end`() {
        val schedule = SyncSchedule(
            mode = SyncMode.INTERVAL,
            intervalMinutes = 60,
            quietWindow = QuietWindow(time("23:00"), time("07:00"))
        )
        assertEquals(at("2026-09-15T07:00:00"), schedule.nextRun(after = at("2026-09-14T23:30:00")))
        assertEquals(at("2026-09-14T07:00:00"), schedule.nextRun(after = at("2026-09-14T02:00:00")))
    }

    @Test
    fun `a quiet window inside one day pushes the run to its end`() {
        val schedule = SyncSchedule(
            mode = SyncMode.INTERVAL,
            intervalMinutes = 60,
            quietWindow = QuietWindow(time("13:00"), time("14:00"))
        )
        assertEquals(at("2026-09-14T14:00:00"), schedule.nextRun(after = at("2026-09-14T13:30:00")))
    }

    @Test
    fun `the end of the quiet window is not itself quiet`() {
        val quiet = QuietWindow(time("23:00"), time("07:00"))
        assertTrue(quiet.contains(time("23:00")))
        assertTrue(quiet.contains(time("03:00")))
        assertFalse(quiet.contains(time("07:00")))
        assertFalse(quiet.contains(time("12:00")))
    }

    @Test
    fun `an empty quiet window blocks nothing`() {
        val quiet = QuietWindow(time("07:00"), time("07:00"))
        assertFalse(quiet.contains(time("07:00")))
        assertFalse(quiet.contains(time("03:00")))
    }

    @Test
    fun `a scheduled time inside the quiet window is skipped, not moved`() {
        // Moving 03:00 to 07:00 would invent a run nobody asked for; the next configured
        // time outside the window is the honest answer.
        val schedule = SyncSchedule(
            mode = SyncMode.TIMES,
            times = listOf(time("03:00"), time("09:00")),
            quietWindow = QuietWindow(time("23:00"), time("07:00"))
        )
        assertEquals(at("2026-09-14T09:00:00"), schedule.nextRun(after = at("2026-09-14T02:00:00")))
    }

    @Test
    fun `a times schedule whose every time is quiet never runs`() {
        val schedule = SyncSchedule(
            mode = SyncMode.TIMES,
            times = listOf(time("03:00")),
            quietWindow = QuietWindow(time("23:00"), time("07:00"))
        )
        assertNull(schedule.nextRun(after = at("2026-09-14T02:00:00")))
    }

    @Test
    fun `weekday filter and quiet window combine`() {
        val schedule = SyncSchedule(
            mode = SyncMode.TIMES,
            times = listOf(time("06:00"), time("08:00")),
            days = WEEKDAYS,
            quietWindow = QuietWindow(time("23:00"), time("07:00"))
        )
        // Friday 06:00 is quiet, so the next configured time, 08:00, runs instead.
        assertEquals(at("2026-09-18T08:00:00"), schedule.nextRun(after = at("2026-09-18T05:00:00")))
        // Saturday and Sunday are filtered out, so from Saturday it lands on Monday 08:00.
        assertEquals(at("2026-09-21T08:00:00"), schedule.nextRun(after = at("2026-09-19T05:00:00")))
    }

    @Test
    fun `interval mode also refuses to run twice for the same due moment`() {
        // The last run is the floor in both modes; here it comes from the interval itself.
        val schedule = SyncSchedule(mode = SyncMode.INTERVAL, intervalMinutes = 60)
        val next = schedule.nextRun(after = at("2026-09-14T11:00:00"), lastRun = at("2026-09-14T11:00:00"))
        assertEquals(at("2026-09-14T12:00:00"), next)
    }

    @Test
    fun `a busy times schedule still terminates`() {
        // 48 times a day, all but one inside a wide quiet window: the search must not give up
        // before it finds the single slot that is allowed.
        val everyHalfHour = (0 until 48).map { LocalTime.of(it / 2, if (it % 2 == 0) 0 else 30) }
        val schedule = SyncSchedule(
            mode = SyncMode.TIMES,
            times = everyHalfHour,
            quietWindow = QuietWindow(time("12:30"), time("12:00"))
        )
        assertEquals(at("2026-09-14T12:00:00"), schedule.nextRun(after = at("2026-09-14T00:00:00")))
        assertFalse(schedule.isNeverRunning)
    }

    // ==================== Delay ====================

    @Test
    fun `the delay is the distance to the next run and never negative`() {
        val schedule = SyncSchedule(mode = SyncMode.TIMES, times = listOf(time("08:00")))
        assertEquals(Duration.ofMinutes(30), schedule.delayFrom(at("2026-09-14T07:30:00")))
        assertEquals(Duration.ZERO, schedule.delayFrom(at("2026-09-14T08:00:00")))
    }

    @Test
    fun `a schedule that never runs has no delay`() {
        assertNull(SyncSchedule(days = emptySet()).delayFrom(at("2026-09-14T10:00:00")))
    }

    // ==================== Serialization ====================

    @Test
    fun `times round-trip through text`() {
        val times = listOf(time("21:00"), time("08:30"))
        assertEquals("08:30,21:00", SyncSchedule.formatTimes(times))
        assertEquals(listOf(time("08:30"), time("21:00")), SyncSchedule.parseTimes("08:30,21:00"))
    }

    @Test
    fun `parsing times drops what it cannot read and keeps the rest`() {
        assertEquals(listOf(time("08:00")), SyncSchedule.parseTimes("08:00, nonsense, "))
        assertEquals(emptyList<LocalTime>(), SyncSchedule.parseTimes(""))
    }

    @Test
    fun `days round-trip through text, nothing stored means every day`() {
        assertEquals("MONDAY,FRIDAY", SyncSchedule.formatDays(setOf(DayOfWeek.FRIDAY, DayOfWeek.MONDAY)))
        assertEquals(setOf(DayOfWeek.MONDAY, DayOfWeek.FRIDAY), SyncSchedule.parseDays("MONDAY,FRIDAY"))
        assertEquals(DayOfWeek.entries.toSet(), SyncSchedule.parseDays(null))
        assertEquals(emptySet<DayOfWeek>(), SyncSchedule.parseDays(SyncSchedule.formatDays(emptySet())))
    }
}
