package com.owen282000.lifedashboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

/**
 * The time range a sync reads per type: a week back from the last complete
 * read, so a pause does not push what a watch wrote before it out of reach, and never more
 * than 30 days, with the part that did not fit named instead of skipped in silence.
 */
class LookbackWindowTest {

    private val now = Instant.parse("2026-09-27T12:00:00Z")
    private val week = Duration.ofDays(7)

    @Test
    fun `without a complete read the range is the plain week, as before`() {
        // The first sync after the update, and a newly enabled type.
        val window = LookbackWindow.of(now, coveredUntil = null)
        assertEquals(now - week, window.start)
        assertNull(window.gapFrom)
    }

    @Test
    fun `a normal sync reads the week before the previous one, one interval wider`() {
        val previous = now - Duration.ofMinutes(15)
        val window = LookbackWindow.of(now, previous)
        assertEquals(previous - week, window.start)
        assertNull(window.gapFrom)
    }

    @Test
    fun `after a ten day pause the range reaches a week before the pause`() {
        // A record from the day before the pause that the watch uploaded during it is inside;
        // with the old range, which started three days after the pause began, it was lost.
        val previous = now - Duration.ofDays(10)
        val window = LookbackWindow.of(now, previous)
        assertEquals(previous - week, window.start)
        assertNull(window.gapFrom)
        val lateUpload = previous - Duration.ofDays(1)
        assertTrue(lateUpload >= window.start)
        assertTrue(lateUpload < now - week)
    }

    @Test
    fun `a pause that just fits reaches exactly thirty days back without a gap`() {
        val previous = now - LookbackWindow.MAX_REACH + week
        val window = LookbackWindow.of(now, previous)
        assertEquals(now - LookbackWindow.MAX_REACH, window.start)
        assertNull(window.gapFrom)
    }

    @Test
    fun `a longer pause stops at thirty days and names where the range should have started`() {
        val previous = now - Duration.ofDays(40)
        val window = LookbackWindow.of(now, previous)
        assertEquals(now - Duration.ofDays(30), window.start)
        assertEquals(previous - week, window.gapFrom)
    }

    @Test
    fun `thirty days is within reach without the history permission`() {
        // Health Connect lets an app read up to 30 days before its first grant, and that grant
        // is never later than now, so the furthest start is always readable.
        assertEquals(Duration.ofDays(30), LookbackWindow.MAX_REACH)
    }

    @Test
    fun `a clock set back since the last read does not move the range forward`() {
        val window = LookbackWindow.of(now, coveredUntil = now + Duration.ofHours(2))
        assertEquals(now - week, window.start)
        assertNull(window.gapFrom)
    }

    @Test
    fun `only types read completely move their anchor`() {
        val enabled = setOf(HealthDataType.STEPS, HealthDataType.HEART_RATE, HealthDataType.WEIGHT, HealthDataType.SLEEP)
        val covered = LookbackWindow.covered(
            enabled = enabled,
            capped = setOf(HealthDataType.HEART_RATE),
            unread = setOf(HealthDataType.WEIGHT),
            readEnd = now
        )
        // A capped type still has a backlog, which may be older than the next range would be;
        // an unread type was not read at all. Both keep the anchor they had.
        assertEquals(mapOf(HealthDataType.STEPS to now, HealthDataType.SLEEP to now), covered)
    }

    @Test
    fun `a capped type keeps reading from before the pause until its backlog is drained`() {
        val beforePause = now - Duration.ofDays(12)
        // Pass one of the first sync after the pause: capped, so the anchor stays.
        val first = LookbackWindow.of(now, beforePause)
        val hr = setOf(HealthDataType.HEART_RATE)
        val stored = LookbackWindow.covered(hr, capped = hr, unread = emptySet(), readEnd = now)[HealthDataType.HEART_RATE]
            ?: beforePause
        // The next sync, an interval later, starts where the first did.
        val later = now + Duration.ofMinutes(15)
        assertEquals(first.start, LookbackWindow.of(later, stored).start)
    }
}
