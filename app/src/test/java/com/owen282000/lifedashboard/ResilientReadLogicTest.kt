package com.owen282000.lifedashboard

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

/**
 * Guards the bisection fallback for malformed source records (issue #12): a record the
 * Health Connect client refuses to read must cost at most one minimal window of data,
 * never the entire data type.
 */
class ResilientReadLogicTest {

    private data class Rec(val id: String, val time: Instant)

    private val base = Instant.parse("2026-01-01T00:00:00Z")
    private val windowEnd: Instant = base.plus(Duration.ofDays(7))

    /** Hourly records across the whole 7-day window. */
    private val hourlyRecords = (0 until 7 * 24).map { hour ->
        Rec("rec-$hour", base.plus(Duration.ofHours(hour.toLong())))
    }

    /**
     * Mimics readAllRecords(): returns instant records inside [start, end), but throws the
     * Health Connect client's error whenever the window contains a malformed record.
     */
    private fun readerWithMalformedAt(
        records: List<Rec>,
        badTimes: List<Instant>,
        onRead: () -> Unit = {}
    ): suspend (Instant, Instant) -> PagedResult<Rec> = { start, end ->
        onRead()
        if (badTimes.any { !it.isBefore(start) && it.isBefore(end) }) {
            throw IllegalArgumentException("startTime must be before endTime.")
        }
        PagedResult(records.filter { !it.time.isBefore(start) && it.time.isBefore(end) }, 1)
    }

    @Test
    fun cleanWindowIsReadInASingleCall() = runBlocking {
        var calls = 0
        val result = ResilientReadLogic.readResilient(base, windowEnd, idOf = { r: Rec -> r.id },
            read = readerWithMalformedAt(hourlyRecords, badTimes = emptyList()) { calls++ })
        assertEquals(1, calls)
        assertEquals(hourlyRecords, result.records)
        assertEquals(0, result.skippedWindows)
    }

    @Test
    fun malformedRecordCostsAtMostTheMinimalWindow() = runBlocking {
        val badTime = base.plus(Duration.ofDays(3)).plus(Duration.ofMinutes(30)).plusSeconds(17)
        val result = ResilientReadLogic.readResilient(base, windowEnd, idOf = { r: Rec -> r.id },
            read = readerWithMalformedAt(hourlyRecords, badTimes = listOf(badTime)))

        assertEquals(1, result.skippedWindows)
        val lost = hourlyRecords - result.records.toSet()
        // Anything lost must sit inside the skipped minimal window around the malformed record.
        assertTrue(lost.all {
            Duration.between(it.time, badTime).abs() <= ResilientReadLogic.MIN_BISECT_WINDOW
        })
        // No good record is within 5 minutes of this badTime, so nothing may be lost at all.
        assertEquals(emptyList<Rec>(), lost)
    }

    @Test
    fun twoMalformedRegionsAreIsolatedIndependently() = runBlocking {
        val badTimes = listOf(
            base.plus(Duration.ofDays(1)).plusSeconds(42),
            base.plus(Duration.ofDays(5)).plus(Duration.ofHours(7)).plusSeconds(11)
        )
        val result = ResilientReadLogic.readResilient(base, windowEnd, idOf = { r: Rec -> r.id },
            read = readerWithMalformedAt(hourlyRecords, badTimes))

        assertEquals(2, result.skippedWindows)
        val lost = hourlyRecords - result.records.toSet()
        assertTrue(lost.all { rec ->
            badTimes.any { Duration.between(rec.time, it).abs() <= ResilientReadLogic.MIN_BISECT_WINDOW }
        })
    }

    @Test
    fun intervalRecordsOverlappingSplitPointsAreDeduped() = runBlocking {
        // Interval-record semantics: a record is returned by every queried window it overlaps.
        data class IntervalRec(val id: String, val start: Instant, val end: Instant)

        val span = IntervalRec("span", base.plus(Duration.ofDays(2)), base.plus(Duration.ofDays(4)))
        val badTime = base.plus(Duration.ofDays(3))
        val read: suspend (Instant, Instant) -> PagedResult<IntervalRec> = { start, end ->
            if (!badTime.isBefore(start) && badTime.isBefore(end)) {
                throw IllegalArgumentException("startTime must be before endTime.")
            }
            val overlaps = span.start.isBefore(end) && span.end.isAfter(start)
            PagedResult(if (overlaps) listOf(span) else emptyList(), 1)
        }

        val result = ResilientReadLogic.readResilient(base, windowEnd, idOf = { r: IntervalRec -> r.id }, read = read)
        assertEquals(1, result.records.count { it.id == "span" })
    }

    @Test
    fun unrelatedIllegalArgumentExceptionsPropagate() {
        val boom = assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                ResilientReadLogic.readResilient(base, windowEnd, idOf = { r: Rec -> r.id },
                    read = { _, _ -> throw IllegalArgumentException("some other validation error") })
            }
        }
        assertEquals("some other validation error", boom.message)
    }
}
