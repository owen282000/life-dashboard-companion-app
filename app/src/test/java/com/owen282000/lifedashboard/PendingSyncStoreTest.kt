package com.owen282000.lifedashboard

import kotlinx.coroutines.runBlocking
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

    /** A Screen Time payload holding the 7 days that end on [last], as the sync builds it. */
    private fun week(last: String): String {
        val end = java.time.LocalDate.parse(last)
        val days = (0L until 7L).joinToString(",") { """{"date":"${end.minusDays(it)}","apps":[]}""" }
        return """{"source":"screen_time","screen_time":[$days]}"""
    }

    @Test
    fun screenTimeReplacementWithinTheWeekLosesNothing() {
        val store = store()
        store.enqueue(week("2026-09-01"), "screen_time", "SCREEN_TIME", 1, nowMillis = 100)
        store.enqueue(week("2026-09-03"), "screen_time", "SCREEN_TIME", 1, nowMillis = 200)
        val dropped = store.enqueue(week("2026-09-07"), "screen_time", "SCREEN_TIME", 1, nowMillis = 300)
        assertEquals("every undelivered day since 09-01 is still in the newest week", emptyList<String>(), dropped.map { it.payload })
        val queued = store.peekAll().single()
        assertEquals(week("2026-09-07"), queued.payload)
        assertEquals("the first undelivered day is carried over", "2026-09-01", queued.undeliveredSince)
    }

    @Test
    fun screenTimeReplacementPastAWeekReportsTheSnapshotWhoseDaysFellOut() {
        val store = store()
        store.enqueue(week("2026-09-01"), "screen_time", "SCREEN_TIME", 1, nowMillis = 100)
        assertEquals(emptyList<String>(), store.enqueue(week("2026-09-07"), "screen_time", "SCREEN_TIME", 1, nowMillis = 200))
        // 09-08 starts at 09-02: 09-01 was never delivered and is in no queued week any more.
        val dropped = store.enqueue(week("2026-09-08"), "screen_time", "SCREEN_TIME", 1, nowMillis = 300)
        assertEquals(listOf(week("2026-09-07")), dropped.map { it.payload })
        // A later replacement on the same day loses nothing new.
        assertEquals(emptyList<String>(), store.enqueue(week("2026-09-08"), "screen_time", "SCREEN_TIME", 2, nowMillis = 400))
        assertEquals(1, store.size())
    }

    @Test
    fun screenTimeDaysDeliveredBeforeTheOutageAreNotReported() {
        val store = store()
        // The first failure is on 09-10: its older days went out with the delivery before it.
        store.enqueue(week("2026-09-10"), "screen_time", "SCREEN_TIME", 1, nowMillis = 100)
        val dropped = store.enqueue(week("2026-09-11"), "screen_time", "SCREEN_TIME", 1, nowMillis = 200)
        assertEquals("09-04 fell out, but it was delivered before 09-10", emptyList<String>(), dropped.map { it.payload })
    }

    @Test
    fun screenTimeSnapshotsQueuedBeforeTheUpgradeCollapseIntoOne() {
        val store = store()
        // Up to 1.20 every failed Screen Time sync queued its own week, without undeliveredSince.
        store.enqueue("h1", "health_connect", "HEALTH_CONNECT", 1, nowMillis = 50)
        val dir = tmp.root.resolve("pending")
        listOf("2026-09-01", "2026-09-05", "2026-09-10").forEachIndexed { i, last ->
            java.io.File(dir, "old$i.json").writeText(
                """{"id":"old$i","payload":${kotlinx.serialization.json.JsonPrimitive(week(last))},"dataType":"screen_time","logType":"SCREEN_TIME","recordCount":1,"createdAt":${100 + i}}"""
            )
        }
        val dropped = store.enqueue(week("2026-09-10"), "screen_time", "SCREEN_TIME", 1, nowMillis = 200)
        assertEquals(
            "the weeks that held 09-01 to 09-03 are reported, the other lost nothing",
            listOf(week("2026-09-01"), week("2026-09-05")),
            dropped.map { it.payload }
        )
        assertEquals(listOf("HEALTH_CONNECT", "SCREEN_TIME"), store.peekAll().map { it.logType })
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
        assertEquals(listOf("json"), dir.listFiles { f -> f.isFile }!!.map { it.extension })
        assertEquals("nothing left in flight either", emptyList<String>(), java.io.File(dir, "in_flight").list()!!.toList())

        // What a crash between writing and renaming leaves behind.
        val stale = java.io.File(dir, "crashed.tmp").apply { writeText("{\"id\":") }
        stale.setLastModified(System.currentTimeMillis() - 2 * 60 * 60 * 1000)
        val fresh = java.io.File(dir, "writing.tmp").apply { writeText("{\"id\":") }
        assertEquals(listOf("p1"), store.peekAll().map { it.payload })
        assertTrue("a stale temp file is cleared", !stale.exists())
        assertTrue("one being written right now is left alone", fresh.exists())
        assertEquals(1, store.size())
    }

    // Write-ahead: a sync's payload is on disk, in flight, before anything that read it is stored.

    private class Sent(val code: Int)

    /** One sync's delivery through [PendingSyncStore.writeAhead], noting what it saw on the way. */
    private class Delivery(val store: PendingSyncStore) {
        var committed = false
        var queuedAtPost: List<String>? = null
        var inFlightAtPost: List<String>? = null
        var pushedOut: List<PendingSyncStore.PendingItem>? = null

        fun run(payload: String = "p1", logType: String = "HEALTH_CONNECT", nowMillis: Long = 100, post: () -> Result<Sent>): Result<Sent> = runBlocking {
            store.writeAhead(
                payload = payload,
                dataType = if (logType == "SCREEN_TIME") "screen_time" else "health_connect",
                logType = logType,
                recordCount = 1,
                nowMillis = nowMillis,
                commit = {
                    assertEquals("written ahead of the commit", 1, store.inFlightIds().size)
                    committed = true
                },
                post = {
                    assertTrue("the commit comes before the post", committed)
                    queuedAtPost = store.peekAll().map { it.payload }
                    inFlightAtPost = store.inFlightIds()
                    post()
                },
                onQueued = { pushedOut = it }
            )
        }
    }

    @Test
    fun writeAheadDeliveredLeavesNothingBehind() {
        val store = store()
        val delivery = Delivery(store)
        val outcome = delivery.run { Result.success(Sent(200)) }
        assertEquals(200, outcome.getOrThrow().code)
        assertEquals("in flight while posting", 1, delivery.inFlightAtPost!!.size)
        assertEquals("and out of the drain's sight", emptyList<String>(), delivery.queuedAtPost)
        assertEquals(0, store.size())
        assertEquals(emptyList<String>(), store.inFlightIds())
        assertEquals("nothing queued, nothing to report", null, delivery.pushedOut)
    }

    @Test
    fun writeAheadFailedPostQueuesThePayload() {
        val store = store()
        val delivery = Delivery(store)
        val outcome = delivery.run(nowMillis = 123) { Result.failure(Exception("HTTP 503")) }
        assertTrue(outcome.isFailure)
        val queued = store.peekAll().single()
        assertEquals("p1", queued.payload)
        assertEquals("its original time, for the drain's order", 123, queued.createdAt)
        assertEquals(0, queued.attempts)
        assertEquals(emptyList<String>(), store.inFlightIds())
        assertEquals(emptyList<PendingSyncStore.PendingItem>(), delivery.pushedOut)
    }

    @Test
    fun writeAheadStoppedOrThrowingPostQueuesThePayload() {
        for (thrown in listOf(kotlinx.coroutines.CancellationException("worker stopped"), IllegalStateException("boom"))) {
            val store = PendingSyncStore(tmp.newFolder())
            val delivery = Delivery(store)
            val caught = try {
                delivery.run { throw thrown }
                null
            } catch (e: Exception) {
                e
            }
            assertEquals("the stop or the error goes on up", thrown.message, caught?.message)
            assertEquals("a $thrown leaves the payload queued", listOf("p1"), store.peekAll().map { it.payload })
            assertEquals(emptyList<String>(), store.inFlightIds())
        }
    }

    /** A delivery through a store whose outbox cannot be written, as on a full disk. */
    private fun unwritable(outcome: Result<Unit>): List<String> {
        val store = PendingSyncStore(tmp.newFile())
        val steps = mutableListOf<String>()
        val result = runBlocking {
            store.writeAhead(
                "p1", "health_connect", "HEALTH_CONNECT", 1, 100,
                commit = { steps += "commit" },
                post = { steps += "post"; outcome },
                onQueued = { steps += "queued" },
                onUnwritable = { steps += "warned" }
            )
        }
        assertEquals(outcome, result)
        assertEquals("nothing queued, nothing in flight", 0, store.size() + store.inFlightIds().size)
        return steps
    }

    @Test
    fun anUnwritableOutboxStillPostsAndCommitsOnlyADelivery() {
        assertEquals("posted first, committed once it arrived", listOf("warned", "post", "commit"), unwritable(Result.success(Unit)))
    }

    @Test
    fun anUnwritableOutboxCommitsNothingWhenThePostFails() {
        assertEquals("the next sync reads the same records again", listOf("warned", "post"), unwritable(Result.failure(Exception("HTTP 503"))))
    }

    @Test
    fun anUnwritableOutboxCommitsNothingWhenThePostIsStopped() {
        val store = PendingSyncStore(tmp.newFile())
        var committed = false
        val caught = try {
            runBlocking {
                store.writeAhead<Unit>(
                    "p1", "health_connect", "HEALTH_CONNECT", 1, 100,
                    commit = { committed = true },
                    post = { throw kotlinx.coroutines.CancellationException("worker stopped") },
                    onQueued = {}
                )
            }
            null
        } catch (e: Exception) {
            e
        }
        assertEquals("worker stopped", caught?.message)
        assertTrue(!committed)
    }

    @Test
    fun aDrainBesideTheSyncNeitherSeesNorRecoversItsPayload() {
        val store = store()
        var duringPost: List<PendingSyncStore.PendingItem>? = null
        Delivery(store).run {
            // What the drain of the tile's other sync does while this one waits on its receiver.
            assertEquals(emptyList<PendingSyncStore.PendingItem>(), store.recoverInFlight())
            duringPost = store.peekAll()
            Result.failure(Exception("HTTP 503"))
        }
        assertEquals(emptyList<PendingSyncStore.PendingItem>(), duringPost)
        assertEquals("queued once, by its own sync", listOf("p1"), store.peekAll().map { it.payload })
    }

    /** What a process that died between writing ahead and the post's outcome leaves behind. */
    private fun leftover(dir: java.io.File, id: String, payload: String, logType: String, createdAt: Long) {
        val inFlight = java.io.File(dir, "in_flight").apply { mkdirs() }
        java.io.File(inFlight, "$id.json").writeText(
            """{"id":"$id","payload":${kotlinx.serialization.json.JsonPrimitive(payload)},"dataType":"x","logType":"$logType","recordCount":1,"createdAt":$createdAt}"""
        )
    }

    @Test
    fun aDeadProcessesPayloadIsQueuedByTheNextDrain() {
        val dir = tmp.newFolder("pending")
        val store = PendingSyncStore(dir)
        store.enqueue("older", "health_connect", "HEALTH_CONNECT", 1, nowMillis = 50)
        leftover(dir, "dead", "p1", "HEALTH_CONNECT", createdAt = 100)
        val tempOfTheDeadWrite = java.io.File(dir, "in_flight/dead2.tmp").apply { writeText("{\"id\":") }
        assertEquals("invisible until recovered", listOf("older"), store.peekAll().map { it.payload })

        assertEquals(emptyList<PendingSyncStore.PendingItem>(), store.recoverInFlight())

        assertEquals("queued in its place in the order", listOf("older", "p1"), store.peekAll().map { it.payload })
        assertEquals("dead", store.peekAll().last().id)
        assertEquals(emptyList<String>(), store.inFlightIds())
        assertTrue(!tempOfTheDeadWrite.exists())
        assertEquals("a second drain finds nothing more", emptyList<PendingSyncStore.PendingItem>(), store.recoverInFlight())
        assertEquals(2, store.size())
    }

    @Test
    fun aRecoveredScreenTimeWeekReplacesTheQueuedOne() {
        val dir = tmp.newFolder("pending")
        val store = PendingSyncStore(dir)
        store.enqueue(week("2026-09-01"), "screen_time", "SCREEN_TIME", 1, nowMillis = 100)
        leftover(dir, "dead", week("2026-09-03"), "SCREEN_TIME", createdAt = 200)
        assertEquals(emptyList<PendingSyncStore.PendingItem>(), store.recoverInFlight())
        val queued = store.peekAll().single()
        assertEquals(week("2026-09-03"), queued.payload)
        assertEquals("2026-09-01", queued.undeliveredSince)
    }

    @Test
    fun aFailedScreenTimeWeekReplacesTheQueuedOne() {
        val store = store()
        store.enqueue(week("2026-09-01"), "screen_time", "SCREEN_TIME", 1, nowMillis = 100)
        val delivery = Delivery(store)
        delivery.run(payload = week("2026-09-03"), logType = "SCREEN_TIME", nowMillis = 200) { Result.failure(Exception("HTTP 503")) }
        assertEquals("the queued week is still there while the new one is posted", listOf(week("2026-09-01")), delivery.queuedAtPost)
        val queued = store.peekAll().single()
        assertEquals(week("2026-09-03"), queued.payload)
        assertEquals("2026-09-01", queued.undeliveredSince)
        assertEquals(emptyList<String>(), store.inFlightIds())
    }

    @Test
    fun aRecoveredPayloadCountsTowardsTheCapAndReportsWhatItPushedOut() {
        val dir = tmp.newFolder("pending")
        val store = PendingSyncStore(dir)
        repeat(PendingSyncStore.MAX_HEALTH_ITEMS) { i -> store.enqueue("p$i", "health_connect", "HEALTH_CONNECT", 1, nowMillis = i.toLong()) }
        leftover(dir, "dead", "last", "HEALTH_CONNECT", createdAt = 10_000)
        assertEquals(listOf("p0"), store.recoverInFlight().map { it.payload })
        assertEquals(PendingSyncStore.MAX_HEALTH_ITEMS, store.size())
        assertEquals("last", store.peekAll().last().payload)
    }

    /** Makes [dir] read-only for the test, or skips it where that has no effect (root). */
    private fun readOnly(dir: java.io.File) {
        dir.setWritable(false)
        org.junit.Assume.assumeTrue("a read-only directory needs a user that is not root", !dir.canWrite())
    }

    @Test
    fun aLeftoverThatCannotBeQueuedStaysForTheNextDrainWithoutAThrow() {
        val dir = tmp.newFolder("pending")
        val store = PendingSyncStore(dir)
        store.enqueue("older", "health_connect", "HEALTH_CONNECT", 1, nowMillis = 50)
        leftover(dir, "dead", "p1", "HEALTH_CONNECT", createdAt = 100)
        leftover(dir, "dead2", "p2", "HEALTH_CONNECT", createdAt = 110)
        readOnly(dir)
        val stuck = mutableListOf<Int>()
        try {
            assertEquals(emptyList<PendingSyncStore.PendingItem>(), store.recoverInFlight { count, _ -> stuck += count })
        } finally {
            dir.setWritable(true)
        }
        assertEquals("reported once, for both", listOf(2), stuck)
        assertEquals(listOf("older"), store.peekAll().map { it.payload })
        assertEquals(setOf("dead", "dead2"), store.inFlightIds().toSet())

        assertEquals(emptyList<PendingSyncStore.PendingItem>(), store.recoverInFlight())
        assertEquals("the next drain queues them", listOf("older", "p1", "p2"), store.peekAll().map { it.payload })
    }

    @Test
    fun aRecoveredScreenTimeWeekThatCannotBeRewrittenKeepsTheQueuedOne() {
        val dir = tmp.newFolder("pending")
        val store = PendingSyncStore(dir)
        store.enqueue(week("2026-09-01"), "screen_time", "SCREEN_TIME", 1, nowMillis = 100)
        leftover(dir, "dead", week("2026-09-03"), "SCREEN_TIME", createdAt = 200)
        // The rewrite's temp file cannot be created, as on a full disk.
        java.io.File(dir, "dead.tmp").mkdirs()
        var reason: Exception? = null
        assertEquals(emptyList<PendingSyncStore.PendingItem>(), store.recoverInFlight { _, e -> reason = e })
        assertTrue("reported", reason != null)
        assertEquals("moved first, and the week before it kept, so no day is lost", listOf(week("2026-09-01"), week("2026-09-03")), store.peekAll().map { it.payload })
        assertEquals(emptyList<String>(), store.inFlightIds())

        java.io.File(dir, "dead.tmp").delete()
        store.enqueue(week("2026-09-04"), "screen_time", "SCREEN_TIME", 1, nowMillis = 300)
        val queued = store.peekAll().single()
        assertEquals("the next replacement folds them together", "2026-09-01", queued.undeliveredSince)
    }

    @Test
    fun aStoppedPostStaysAStopWhenItsPayloadCannotBeQueued() {
        val dir = tmp.newFolder("pending")
        val store = PendingSyncStore(dir)
        readOnly(dir)
        dir.setWritable(true)
        val caught = try {
            runBlocking {
                store.writeAhead<Unit>(
                    "p1", "health_connect", "HEALTH_CONNECT", 1, 100,
                    commit = {},
                    post = {
                        dir.setWritable(false)
                        throw kotlinx.coroutines.CancellationException("worker stopped")
                    },
                    onQueued = {}
                )
            }
            null
        } catch (e: Exception) {
            e
        } finally {
            dir.setWritable(true)
        }
        assertTrue("a stop, not a failure: $caught", caught is kotlinx.coroutines.CancellationException)
        assertEquals("worker stopped", caught?.message)
        // The coroutine machinery may hand back a copy with the original as its cause.
        val suppressed = caught!!.suppressed.toList() + caught.cause?.suppressed.orEmpty()
        assertTrue("the queuing error rides along", suppressed.any { it is java.io.IOException })
        assertEquals("left in flight", 1, store.inFlightIds().size)
        store.recoverInFlight()
        assertEquals("and queued by the next drain", listOf("p1"), store.peekAll().map { it.payload })
    }

    @Test
    fun aFailedPostWhosePayloadCannotBeQueuedFailsWithThatError() {
        val dir = tmp.newFolder("pending")
        val store = PendingSyncStore(dir)
        readOnly(dir)
        dir.setWritable(true)
        val caught = try {
            runBlocking {
                store.writeAhead(
                    "p1", "health_connect", "HEALTH_CONNECT", 1, 100,
                    commit = {},
                    post = {
                        dir.setWritable(false)
                        Result.failure<Unit>(Exception("HTTP 503"))
                    },
                    onQueued = {}
                )
            }
            null
        } catch (e: Exception) {
            e
        } finally {
            dir.setWritable(true)
        }
        assertTrue("$caught", caught is java.io.IOException)
        assertEquals(1, store.inFlightIds().size)
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
