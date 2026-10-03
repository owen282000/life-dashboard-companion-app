package com.owen282000.lifedashboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

/**
 * The stored cursor of a backfill (P2-14): the windows it walks, where a stopped job resumes,
 * and when starting again continues a job instead of beginning at the first window.
 */
class BackfillJobTest {

    private val now = Instant.parse("2026-10-03T12:00:00.750Z").toEpochMilli()
    private val hour = Duration.ofHours(1).toMillis()

    @Test
    fun `a job covers its days in three-day windows, the last one shorter, ending on a whole second`() {
        val job = BackfillJob.start(days = 7, now = now)
        assertEquals(Instant.parse("2026-10-03T12:00:00Z").toEpochMilli(), job.rangeEnd)
        assertEquals(Duration.ofDays(7).toMillis(), job.rangeEnd - job.rangeStart)
        assertEquals(3, job.windowCount)

        val windows = (0 until job.windowCount).map { job.window(it) }
        assertEquals(Instant.ofEpochMilli(job.rangeStart), windows.first().first)
        assertEquals(Instant.ofEpochMilli(job.rangeEnd), windows.last().second)
        windows.zipWithNext().forEach { (a, b) -> assertEquals("windows meet", a.second, b.first) }
        assertEquals(Duration.ofDays(1), Duration.between(windows.last().first, windows.last().second))
    }

    @Test
    fun `the window counts match the old ceil of days over three`() {
        listOf(1, 3, 7, 30, 90, 365).forEach { days ->
            assertEquals("$days days", (days + 2) / 3, BackfillJob.start(days, now).windowCount)
        }
    }

    @Test
    fun `each completed window moves the cursor and adds its records, and the last one finishes the job`() {
        var job = BackfillJob.start(days = 7, now = now)
        assertFalse(job.isFinished)
        job = job.afterWindow(records = 10, now = now + 1).afterWindow(records = 5, now = now + 2)
        assertEquals(2, job.nextWindow)
        assertEquals(15, job.recordsSent)
        assertEquals(job.window(2), job.window())
        assertFalse(job.isFinished)
        job = job.afterWindow(records = 0, now = now + 3)
        assertTrue(job.isFinished)
    }

    @Test
    fun `a stored job survives the round trip, and resumes at the window it had not finished`() {
        val job = BackfillJob.start(days = 90, now = now).afterWindow(100, now).afterWindow(50, now)
        val stored = BackfillJob.decode(BackfillJob.encode(job))
        assertEquals(job, stored)
        assertEquals(2, stored!!.nextWindow)
        assertEquals(job.window(2), stored.window())
    }

    @Test
    fun `a stored job that cannot be read is no job`() {
        assertNull(BackfillJob.decode(null))
        assertNull(BackfillJob.decode(""))
        assertNull(BackfillJob.decode("{not json"))
        assertNull(BackfillJob.decode("""{"id":"x"}"""))
    }

    @Test
    fun `a field from a newer version does not lose the job`() {
        val job = BackfillJob.start(days = 30, now = now)
        val newer = BackfillJob.encode(job).dropLast(1) + ""","added_later":true}"""
        assertEquals(job, BackfillJob.decode(newer))
    }

    @Test
    fun `starting the same length again continues the stopped job with its range and cursor`() {
        val stopped = BackfillJob.start(days = 90, now = now).afterWindow(40, now).afterWindow(40, now)
        val later = now + 3 * hour

        val run = BackfillJob.startOrContinue(stopped, days = 90, now = later)

        assertEquals(stopped.id, run.id)
        assertEquals(2, run.nextWindow)
        assertEquals(80, run.recordsSent)
        assertEquals("the same windows as before the stop", stopped.rangeEnd, run.rangeEnd)
    }

    @Test
    fun `another length, a finished job, a day old job or none starts over at the first window`() {
        val stopped = BackfillJob.start(days = 90, now = now).afterWindow(40, now)
        val finished = BackfillJob.start(days = 3, now = now).afterWindow(1, now)
        assertTrue(finished.isFinished)
        val later = now + hour
        val dayLater = now + Duration.ofHours(25).toMillis()

        listOf(
            BackfillJob.startOrContinue(stopped, days = 30, now = later) to later,
            BackfillJob.startOrContinue(stopped, days = 90, now = dayLater) to dayLater,
            BackfillJob.startOrContinue(finished, days = 3, now = later) to later,
            BackfillJob.startOrContinue(null, days = 90, now = later) to later
        ).forEach { (run, at) ->
            assertEquals(0, run.nextWindow)
            assertEquals(0, run.recordsSent)
            assertNotEquals(stopped.id, run.id)
            assertEquals("a range up to the start", at - Math.floorMod(at, 1000L), run.rangeEnd)
        }
    }

    @Test
    fun `a job from a clock that went back is not continued`() {
        val stopped = BackfillJob.start(days = 90, now = now).afterWindow(40, now)
        assertFalse(BackfillJob.continues(stopped, days = 90, now = now - hour))
    }

    @Test
    fun `the quota pauses a job until it has paused too often without a window going through`() {
        var job = BackfillJob.start(days = 30, now = now)
        repeat(BackfillJob.MAX_QUOTA_PAUSES - 1) { job = job.afterQuotaPause(now) }
        assertFalse(job.quotaGivesUp)
        assertEquals("a pause does not move the cursor", 0, job.nextWindow)

        // A window that goes through starts the count again.
        job = job.afterWindow(5, now)
        assertEquals(0, job.quotaPauses)
        repeat(BackfillJob.MAX_QUOTA_PAUSES) { job = job.afterQuotaPause(now) }
        assertTrue(job.quotaGivesUp)

        // Starting it again is a new request: it continues where it stopped, with a fresh count.
        val again = BackfillJob.startOrContinue(job, days = 30, now = now + hour)
        assertEquals(1, again.nextWindow)
        assertEquals(0, again.quotaPauses)
    }
}
