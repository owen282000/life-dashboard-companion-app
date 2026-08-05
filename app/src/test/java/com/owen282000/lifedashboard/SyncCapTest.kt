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
}
