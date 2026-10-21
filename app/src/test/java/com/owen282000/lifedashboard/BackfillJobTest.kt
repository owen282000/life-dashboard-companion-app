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
 * The stored cursor of a backfill (P2-14): the windows it walks, where a stopped job resumes
 * (also halfway through a window), when starting again continues a job instead of beginning at
 * the first window, and when it gives up.
 */
class BackfillJobTest {

    private val now = Instant.parse("2026-10-03T12:00:00.750Z").toEpochMilli()
    private val hour = Duration.ofHours(1).toMillis()
    private val steps = listOf("STEPS")

    private fun start(days: Int, types: List<String> = steps, at: Long = now) = BackfillJob.start(days, types, at)

    @Test
    fun `a job covers its days in three-day windows, the last one shorter, ending on a whole second`() {
        val job = start(days = 7)
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
            assertEquals("$days days", (days + 2) / 3, start(days).windowCount)
        }
    }

    @Test
    fun `each completed window moves the cursor and adds its records, and the last one finishes the job`() {
        var job = start(days = 7)
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
    fun `a chunk that went through is kept inside its window, and the window's end clears it`() {
        val mark = ChunkMark.of(Watermark(Instant.parse("2026-09-20T08:00:00.123456789Z"), tieId = "abc"))
        var job = start(days = 7).afterChunk(
            records = 1000,
            cursor = mapOf("HEART_RATE" to mark),
            draining = setOf("HEART_RATE"),
            unread = setOf("WEIGHT"),
            now = now
        )
        assertEquals("still in the first window", 0, job.nextWindow)
        assertEquals(1, job.chunksSent)
        assertEquals(1000, job.recordsSent)

        // What a run that Android stops leaves behind, and the next run reads back.
        job = BackfillJob.decode(BackfillJob.encode(job))!!
        assertEquals("to the nanosecond", Watermark(Instant.parse("2026-09-20T08:00:00.123456789Z"), "abc"), job.chunkCursor.getValue("HEART_RATE").toWatermark())
        assertEquals(listOf("HEART_RATE"), job.draining)
        assertEquals("an unread type keeps the window from being complete after a resume too", listOf("WEIGHT"), job.windowUnread)

        job = job.afterWindow(records = 10, now = now)
        assertEquals(1, job.nextWindow)
        assertEquals(1010, job.recordsSent)
        assertEquals(0, job.chunksSent)
        assertEquals(emptyMap<String, ChunkMark>(), job.chunkCursor)
        assertNull(job.draining)
        assertEquals(emptyList<String>(), job.windowUnread)
    }

    @Test
    fun `a window that ended incomplete is sent again from its start, with its records counted`() {
        val job = start(days = 7)
            .afterChunk(500, mapOf("STEPS" to ChunkMark(1, 2)), setOf("STEPS"), emptySet(), now)
            .windowFromStart(records = 20, now = now)
        assertEquals(0, job.nextWindow)
        assertEquals(0, job.chunksSent)
        assertEquals(emptyMap<String, ChunkMark>(), job.chunkCursor)
        assertNull(job.draining)
        assertEquals(520, job.recordsSent)
    }

    @Test
    fun `a stored job survives the round trip, and resumes at the window it had not finished`() {
        val job = start(days = 90).afterWindow(100, now).afterWindow(50, now)
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
        val job = start(days = 30)
        val newer = BackfillJob.encode(job).dropLast(1) + ""","added_later":true}"""
        assertEquals(job, BackfillJob.decode(newer))
    }

    @Test
    fun `starting the same length and types again continues the stopped job with its range and cursor`() {
        val stopped = start(days = 90).afterWindow(40, now).afterWindow(40, now)
            .afterChunk(7, mapOf("STEPS" to ChunkMark(5, 0)), setOf("STEPS"), emptySet(), now)
        val later = now + 3 * hour

        val run = BackfillJob.startOrContinue(stopped, days = 90, types = steps, now = later)

        assertEquals(stopped.id, run.id)
        assertEquals(2, run.nextWindow)
        assertEquals("halfway through the window, where it was", 1, run.chunksSent)
        assertEquals(stopped.chunkCursor, run.chunkCursor)
        assertEquals(87, run.recordsSent)
        assertEquals("the same windows as before the stop", stopped.rangeEnd, run.rangeEnd)
    }

    @Test
    fun `other types, another length, a finished job, a day old job or none starts over at the first window`() {
        val stopped = start(days = 90).afterWindow(40, now)
        val finished = start(days = 3).afterWindow(1, now)
        assertTrue(finished.isFinished)
        val later = now + hour
        val dayLater = now + Duration.ofHours(25).toMillis()

        listOf(
            BackfillJob.startOrContinue(stopped, days = 90, types = listOf("STEPS", "WEIGHT"), now = later) to later,
            BackfillJob.startOrContinue(stopped, days = 30, types = steps, now = later) to later,
            BackfillJob.startOrContinue(stopped, days = 90, types = steps, now = dayLater) to dayLater,
            BackfillJob.startOrContinue(finished, days = 3, types = steps, now = later) to later,
            BackfillJob.startOrContinue(null, days = 90, types = steps, now = later) to later
        ).forEach { (run, at) ->
            assertEquals(0, run.nextWindow)
            assertEquals(0, run.recordsSent)
            assertNotEquals(stopped.id, run.id)
            assertEquals("a range up to the start", at - Math.floorMod(at, 1000L), run.rangeEnd)
        }
    }

    @Test
    fun `a job whose types changed starts over from its first window, keeping its id and range`() {
        val job = start(days = 30).afterWindow(40, now)
            .afterChunk(9, mapOf("STEPS" to ChunkMark(5, 0)), setOf("STEPS"), setOf("STEPS"), now)
        val types = BackfillJob.typeNames(listOf(HealthDataType.WEIGHT, HealthDataType.STEPS))
        assertEquals("sorted, so a set compares by value", listOf("STEPS", "WEIGHT"), types)

        val restarted = job.restartedFor(types, now + hour)

        assertEquals(job.id, restarted.id)
        assertEquals(job.rangeStart, restarted.rangeStart)
        assertEquals(job.rangeEnd, restarted.rangeEnd)
        assertEquals(types, restarted.types)
        assertEquals(0, restarted.nextWindow)
        assertEquals(0, restarted.chunksSent)
        assertEquals(emptyMap<String, ChunkMark>(), restarted.chunkCursor)
        assertEquals(emptyList<String>(), restarted.windowUnread)
        assertEquals(0, restarted.recordsSent)
    }

    @Test
    fun `a job from a clock that went back is not continued`() {
        val stopped = start(days = 90).afterWindow(40, now)
        assertFalse(BackfillJob.continues(stopped, days = 90, types = steps, now = now - hour))
    }

    @Test
    fun `runs that send nothing are counted until the job gives up, and any payload resets the count`() {
        var job = start(days = 30)
        repeat(BackfillJob.MAX_IDLE_RUNS - 1) { job = job.afterIdleRun(now) }
        assertFalse(job.givesUp)
        assertEquals("an idle run does not move the cursor", 0, job.nextWindow)

        // A payload inside a window is progress, not only a whole window.
        job = job.afterChunk(5, emptyMap(), emptySet(), emptySet(), now)
        assertEquals(0, job.idleRuns)
        repeat(BackfillJob.MAX_IDLE_RUNS) { job = job.afterIdleRun(now) }
        assertTrue(job.givesUp)

        // Starting it again is a new request: it continues where it stopped, with a fresh count.
        val again = BackfillJob.startOrContinue(job, days = 30, types = steps, now = now + hour)
        assertEquals(1, again.chunksSent)
        assertEquals(0, again.idleRuns)
    }

    @Test
    fun `a failure survives the trip through the work's output`() {
        listOf(
            BackfillFailure.NoWebhook,
            BackfillFailure.NoTypes,
            BackfillFailure.Read("SecurityException: not allowed"),
            BackfillFailure.Delivery(3, 10),
            BackfillFailure.NotReturned(2, 10, listOf("heart_rate", "steps")),
            BackfillFailure.TooManyChunks(4, 10),
            BackfillFailure.NoProgress(5, 10),
            BackfillFailure.Unexpected("boom")
        ).forEach { failure ->
            val done = (failure as? BackfillFailure.Delivery)?.done ?: (failure as? BackfillFailure.NotReturned)?.done
                ?: (failure as? BackfillFailure.TooManyChunks)?.done ?: (failure as? BackfillFailure.NoProgress)?.done ?: 0
            val back = BackfillFailure.of(failure.code, done, 10, BackfillFailure.detailOf(failure))
            assertEquals(failure, back)
        }
        assertNull(BackfillFailure.of("from_a_newer_version", 0, 0, null))
    }

    @Test
    fun `a backfill of all history starts at the oldest record, on a whole second, and ends now`() {
        val earliest = Instant.parse("2020-03-14T07:30:00.999Z").toEpochMilli()
        val job = BackfillJob.start(BackfillJob.ALL_HISTORY, steps, now, earliest)
        assertEquals(Instant.parse("2020-03-14T07:30:00Z").toEpochMilli(), job.rangeStart)
        assertEquals(Instant.parse("2026-10-03T12:00:00Z").toEpochMilli(), job.rangeEnd)
        assertEquals(BackfillJob.ALL_HISTORY, job.days)
        assertEquals(2395, job.spanDays)
        assertEquals((2395 + 2) / 3, job.windowCount)
        assertEquals(job.windowCount, BackfillJob.allHistoryWindows(earliest, now))
    }

    @Test
    fun `all history covers at least a day, and cannot start without the oldest record`() {
        val job = BackfillJob.start(BackfillJob.ALL_HISTORY, steps, now, earliest = now)
        assertEquals(Duration.ofDays(1).toMillis(), job.rangeEnd - job.rangeStart)
        assertEquals(1, job.windowCount)
        assertTrue(runCatching { BackfillJob.start(BackfillJob.ALL_HISTORY, steps, now) }.isFailure)
    }

    @Test
    fun `starting all history again continues the stopped one, without looking up the oldest record`() {
        val earliest = Instant.parse("2021-01-01T00:00:00Z").toEpochMilli()
        val stopped = BackfillJob.start(BackfillJob.ALL_HISTORY, steps, now, earliest).afterWindow(10, now).afterIdleRun(now)
        val again = BackfillJob.startOrContinue(stopped, BackfillJob.ALL_HISTORY, steps, now + hour)
        assertEquals(stopped.id, again.id)
        assertEquals(stopped.rangeStart, again.rangeStart)
        assertEquals(1, again.nextWindow)

        val fixed = BackfillJob.startOrContinue(stopped, 365, steps, now + hour)
        assertNotEquals("another length starts over", stopped.id, fixed.id)
        assertEquals(365, fixed.spanDays)
    }

    @Test
    fun `the dialog leaves out lengths that reach back past the oldest record`() {
        assertEquals("unknown: all of them", BackfillJob.LENGTHS, BackfillJob.lengthsFor(null, now))
        val threeYears = now - Duration.ofDays(3 * 365).toMillis()
        assertEquals(listOf(30, 90, 180, 365, 730, BackfillJob.ALL_HISTORY), BackfillJob.lengthsFor(threeYears, now))
        val tenDays = now - Duration.ofDays(10).toMillis()
        assertEquals(listOf(BackfillJob.ALL_HISTORY), BackfillJob.lengthsFor(tenDays, now))
        val exactlyAYear = now - Duration.ofDays(365).toMillis()
        assertEquals("a length as long as the history is All", listOf(30, 90, 180, BackfillJob.ALL_HISTORY), BackfillJob.lengthsFor(exactlyAYear, now))
        assertEquals("a stopped job's length stays", listOf(30, 90, 180, 1825, BackfillJob.ALL_HISTORY), BackfillJob.lengthsFor(exactlyAYear, now, keep = 1825))
    }

    @Test
    fun `the windows the dialog names match the job each length starts`() {
        BackfillJob.LENGTHS.filter { it != BackfillJob.ALL_HISTORY }.forEach { days ->
            assertEquals("$days days", start(days).windowCount, BackfillJob.windowsFor(days))
        }
    }
}
