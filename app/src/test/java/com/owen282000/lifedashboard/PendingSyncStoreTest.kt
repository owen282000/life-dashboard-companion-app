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
    fun capDropsTheOldestBeyondFiftyItems() {
        val store = store()
        repeat(PendingSyncStore.MAX_ITEMS + 5) { i ->
            store.enqueue("p$i", "health_connect", "HEALTH_CONNECT", 1, nowMillis = i.toLong())
        }
        val items = store.peekAll()
        assertEquals(PendingSyncStore.MAX_ITEMS, items.size)
        assertEquals("p5", items.first().payload)
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
