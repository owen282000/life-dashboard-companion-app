package com.owen282000.lifedashboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * Guards the lastSync/record-cap interaction: the cap must keep the OLDEST records so that
 * advancing lastSync to the delivered batch's maximum never permanently skips capped records.
 * A takeLast-style cap (keeping the newest) once caused exactly that silent data loss.
 */
class SyncCapTest {

    private data class Rec(val id: Int, val time: Instant)

    private val base = Instant.parse("2026-01-01T00:00:00Z")

    private fun rec(minute: Int) = Rec(minute, base.plusSeconds(minute * 60L))

    @Test
    fun underTheLimitRecordsPassThroughUnchanged() {
        val records = listOf(rec(3), rec(1), rec(2))
        val result = ResilientReadLogic.capOldestFirst(records, maxLimit = 5) { it.time }
        assertEquals(records, result)
    }

    @Test
    fun overTheLimitTheOldestRecordsAreKept() {
        val records = (1..10).map(::rec).shuffled(java.util.Random(42))
        val result = ResilientReadLogic.capOldestFirst(records, maxLimit = 4) { it.time }
        assertEquals(listOf(1, 2, 3, 4), result.map { it.id })
    }

    @Test
    fun everyDroppedRecordIsNewerThanEveryKeptRecord() {
        val records = (1..100).map(::rec).shuffled(java.util.Random(7))
        val kept = ResilientReadLogic.capOldestFirst(records, maxLimit = 30) { it.time }
        val dropped = records - kept.toSet()
        val newestKept = kept.maxOf { it.time }
        assertEquals(30, kept.size)
        assertTrue(dropped.all { it.time > newestKept })
    }

    @Test
    fun repeatedSyncsDeliverEverythingExactlyOnce() {
        // Simulates the real sync loop: filter records newer than lastSync, cap the batch,
        // then advance lastSync to the delivered batch's maximum timestamp.
        val all = (1..25).map(::rec).shuffled(java.util.Random(1))
        val delivered = mutableListOf<Rec>()
        var lastSync: Instant? = null
        var rounds = 0
        while (true) {
            val filtered = all.filter { lastSync == null || it.time > lastSync }
            if (filtered.isEmpty()) break
            val batch = ResilientReadLogic.capOldestFirst(filtered, maxLimit = 10) { it.time }
            delivered += batch
            lastSync = batch.maxOf { it.time }
            rounds++
        }
        assertEquals(3, rounds)
        // Every record is delivered exactly once; order within the final uncapped batch is free.
        assertEquals(25, delivered.size)
        assertEquals((1..25).toList(), delivered.map { it.id }.sorted())
    }

    /**
     * Records sharing the boundary timestamp must all be included (issue #38): with a hard
     * take(max), a tie group split by the cap left records that were above the cap but not
     * above the watermark, and the strict '>' filter then skipped them forever.
     */
    @Test
    fun tiesAtTheCapBoundaryAreIncluded() {
        val tied = (1..5).map { Rec(10 + it, base.plusSeconds(600)) }
        val records = listOf(rec(1), rec(2)) + tied + listOf(rec(20))
        val result = ResilientReadLogic.capOldestFirst(records, maxLimit = 4) { it.time }
        // 2 older + the full 5-record tie group; only the newer record is deferred.
        assertEquals(7, result.size)
        assertTrue(tied.all { it in result })
    }

    @Test
    fun repeatedSyncsWithTiedTimestampsStillDeliverEverythingExactlyOnce() {
        // 30 records across only 3 distinct timestamps, capped at 8 per batch.
        val all = (0 until 30).map { Rec(it, base.plusSeconds((it % 3) * 60L)) }
        val delivered = mutableListOf<Rec>()
        var lastSync: Instant? = null
        var rounds = 0
        while (rounds < 20) {
            val filtered = all.filter { lastSync == null || it.time > lastSync }
            if (filtered.isEmpty()) break
            val batch = ResilientReadLogic.capOldestFirst(filtered, maxLimit = 8) { it.time }
            delivered += batch
            lastSync = batch.maxOf { it.time }
            rounds++
        }
        assertEquals(30, delivered.size)
        assertEquals(all.map { it.id }.sorted(), delivered.map { it.id }.sorted())
    }

    @Test
    fun sampleRecordCapKeepsWholeRecordsAndIncludesBoundaryTies() {
        data class HrRec(val id: Int, val samples: Int, val modified: Instant)

        val records = listOf(
            HrRec(1, samples = 400, modified = base.plusSeconds(60)),
            HrRec(2, samples = 400, modified = base.plusSeconds(120)),
            // These two share the boundary modification time; both must be included even
            // though the sample budget is already exceeded after the first.
            HrRec(3, samples = 400, modified = base.plusSeconds(180)),
            HrRec(4, samples = 400, modified = base.plusSeconds(180)),
            HrRec(5, samples = 400, modified = base.plusSeconds(240))
        )
        val included = ResilientReadLogic.capRecordsBySamples(
            records, maxSamples = 1000, samplesOf = { it.samples }, timeOf = { it.modified }
        )
        assertEquals(listOf(1, 2, 3, 4), included.map { it.id })
    }

    @Test
    fun repeatedSampleRecordSyncsDeliverEverythingExactlyOnce() {
        data class HrRec(val id: Int, val samples: Int, val modified: Instant)

        val all = (0 until 40).map { HrRec(it, samples = 300, modified = base.plusSeconds((it / 4) * 60L)) }
        val delivered = mutableListOf<HrRec>()
        var lastSync: Instant? = null
        var rounds = 0
        while (rounds < 50) {
            val fresh = all.filter { lastSync == null || it.modified > lastSync }
            if (fresh.isEmpty()) break
            val batch = ResilientReadLogic.capRecordsBySamples(
                fresh, maxSamples = 1000, samplesOf = { it.samples }, timeOf = { it.modified }
            )
            delivered += batch
            lastSync = batch.maxOf { it.modified }
            rounds++
        }
        assertEquals(40, delivered.size)
        assertEquals(all.map { it.id }.sorted(), delivered.map { it.id }.sorted())
    }

    /**
     * Watch apps (Zepp, Garmin) upload data hours later with the ORIGINAL record timestamps.
     * The sync watermark therefore runs on modification time, not record time: a backfilled
     * record has an old record time but a recent modification time and must still sync.
     */
    @Test
    fun backfilledRecordsWithOldTimestampsAreStillDelivered() {
        data class R(val id: Int, val time: Instant, val modified: Instant)

        fun at(hour: Int, minute: Int) = base.plusSeconds((hour * 3600 + minute * 60).toLong())
        var watermark: Instant? = null
        fun sync(all: List<R>): List<R> {
            val fresh = all.filter { watermark == null || it.modified > watermark }
            val batch = ResilientReadLogic.capOldestFirst(fresh, maxLimit = 100) { it.modified }
            batch.maxOfOrNull { it.modified }?.let { watermark = it }
            return batch
        }

        // Round 1: live data measured and uploaded at 10:00.
        val live = R(1, time = at(10, 0), modified = at(10, 0))
        assertEquals(listOf(1), sync(listOf(live)).map { it.id })

        // Round 2: the watch backfills an 08:00 record at 12:05. A record-time watermark
        // (10:00) would skip it forever; the modification-time watermark picks it up.
        val backfilled = R(2, time = at(8, 0), modified = at(12, 5))
        assertEquals(listOf(2), sync(listOf(live, backfilled)).map { it.id })

        // Round 3: nothing new, nothing re-sent.
        assertEquals(emptyList<Int>(), sync(listOf(live, backfilled)).map { it.id })
    }
}
