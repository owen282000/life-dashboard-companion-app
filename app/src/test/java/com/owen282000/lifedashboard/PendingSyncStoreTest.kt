package com.owen282000.lifedashboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PendingSyncStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun store() = PendingSyncStore(tmp.newFolder("pending"))

    @Test
    fun enqueuedItemsComeBackOldestFirst() {
        val store = store()
        store.enqueue("p1", "health_connect", "HEALTH_CONNECT", 3, nowMillis = 100)
        store.enqueue("p2", "screen_time", "SCREEN_TIME", 1, nowMillis = 200)
        val items = store.peekAll()
        assertEquals(listOf("p1", "p2"), items.map { it.payload })
        assertEquals(2, store.size())
    }

    @Test
    fun removeDeletesTheItem() {
        val store = store()
        store.enqueue("p1", "health_connect", "HEALTH_CONNECT", 3, nowMillis = 100)
        store.remove(store.peekAll().single().id)
        assertEquals(0, store.size())
    }

    @Test
    fun recordAttemptIncrementsAndPersists() {
        val store = store()
        store.enqueue("p1", "health_connect", "HEALTH_CONNECT", 3, nowMillis = 100)
        store.recordAttempt(store.peekAll().single())
        assertEquals(1, store.peekAll().single().attempts)
    }

    @Test
    fun healthCapDropsTheOldestAndReturnsThem() {
        val store = store()
        val dropped = (0 until PendingSyncStore.MAX_HEALTH_ITEMS + 2).flatMap { i ->
            store.enqueue("p$i", "health_connect", "HEALTH_CONNECT", 1, nowMillis = i.toLong())
        }
        assertEquals("the caller gets what was dropped, to report it", listOf("p0", "p1"), dropped.map { it.payload })
        val items = store.peekAll()
        assertEquals(PendingSyncStore.MAX_HEALTH_ITEMS, items.size)
        assertEquals("p2", items.first().payload)
    }

    @Test
    fun screenTimeSnapshotReplacesItsPredecessorWithoutReportingIt() {
        val store = store()
        store.enqueue("h1", "health_connect", "HEALTH_CONNECT", 3, nowMillis = 100)
        store.enqueue("s1", "screen_time", "SCREEN_TIME", 10, nowMillis = 200)
        val dropped = store.enqueue("s2", "screen_time", "SCREEN_TIME", 12, nowMillis = 300)
        assertEquals("the newest carries all 7 days, so nothing was lost", emptyList<String>(), dropped.map { it.payload })
        assertEquals("health is untouched by the Screen Time cap", listOf("h1", "s2"), store.peekAll().map { it.payload })
    }

    @Test
    fun screenTimeSnapshotInTheSameMillisecondStillWins() {
        val store = store()
        store.enqueue("s1", "screen_time", "SCREEN_TIME", 10, nowMillis = 200)
        store.enqueue("s2", "screen_time", "SCREEN_TIME", 12, nowMillis = 200)
        assertEquals(listOf("s2"), store.peekAll().map { it.payload })
    }

    @Test
    fun recordAttemptDoesNotBringBackAReplacedItem() {
        val store = store()
        store.enqueue("s1", "screen_time", "SCREEN_TIME", 10, nowMillis = 200)
        val draining = store.peekAll().single()
        store.enqueue("s2", "screen_time", "SCREEN_TIME", 12, nowMillis = 300)
        store.recordAttempt(draining)
        assertEquals(listOf("s2"), store.peekAll().map { it.payload })
    }

    @Test
    fun writesLeaveNoTempFileAndStaleOnesAreCleared() {
        val dir = tmp.newFolder("pending3")
        val store = PendingSyncStore(dir)
        store.enqueue("p1", "health_connect", "HEALTH_CONNECT", 1, nowMillis = 100)
        store.recordAttempt(store.peekAll().single())
        assertEquals(listOf("json"), dir.listFiles()!!.map { it.extension })

        // What a crash between writing and renaming leaves behind.
        val stale = java.io.File(dir, "crashed.tmp").apply { writeText("{\"id\":") }
        stale.setLastModified(System.currentTimeMillis() - 2 * 60 * 60 * 1000)
        val fresh = java.io.File(dir, "writing.tmp").apply { writeText("{\"id\":") }
        assertEquals(listOf("p1"), store.peekAll().map { it.payload })
        assertTrue("a stale temp file is cleared", !stale.exists())
        assertTrue("one being written right now is left alone", fresh.exists())
        assertEquals(1, store.size())
    }

    @Test
    fun corruptFilesAreDroppedNotFatal() {
        val dir = tmp.newFolder("pending2")
        val store = PendingSyncStore(dir)
        store.enqueue("good", "health_connect", "HEALTH_CONNECT", 1, nowMillis = 100)
        java.io.File(dir, "broken.json").writeText("{not json")
        assertEquals(listOf("good"), store.peekAll().map { it.payload })
        assertTrue(!java.io.File(dir, "broken.json").exists())
    }
}
