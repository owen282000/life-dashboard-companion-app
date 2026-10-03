package com.owen282000.lifedashboard.sync

import androidx.health.connect.client.records.StepsRecord
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import com.owen282000.lifedashboard.DeletedRecord
import com.owen282000.lifedashboard.DeletionSummary
import com.owen282000.lifedashboard.HealthDataType.STEPS
import com.owen282000.lifedashboard.LogType
import com.owen282000.lifedashboard.PendingDrainer
import com.owen282000.lifedashboard.PendingSyncStore
import com.owen282000.lifedashboard.appPreferences
import com.owen282000.lifedashboard.harness.AppStateRule
import com.owen282000.lifedashboard.harness.Conservation
import com.owen282000.lifedashboard.harness.HcFixture
import com.owen282000.lifedashboard.harness.HcFixture.Companion.ago
import com.owen282000.lifedashboard.harness.Receiver
import com.owen282000.lifedashboard.harness.TestSetup
import com.owen282000.lifedashboard.harness.Witness
import com.owen282000.lifedashboard.harness.arr
import com.owen282000.lifedashboard.harness.str
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import java.io.File

/**
 * Write-ahead (P1-12 step 2, for today's single outbox item): a sync puts its payload on disk
 * before it stores the watermarks, and the outcome of the post decides only whether that copy
 * goes or joins the outbox. A sync stopped or killed after its watermarks moved therefore
 * loses nothing, and a drain running beside it never posts its payload a second time.
 *
 * Red before: the payload went to the outbox only after a failed post, after the watermarks
 * were stored, so a process that died in between lost its records for good.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class WriteAheadTest {

    private val receiver = Receiver()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(receiver).around(AppStateRule()).around(Witness.Rule(receiver))

    private val context = TestSetup.context
    private val fixture = HcFixture(context)
    private val prefs get() = context.appPreferences()
    private val store get() = PendingSyncStore.forContext(context)

    /** Records to sync and a stored deletion, with a receiver that takes the post and never answers it. */
    private fun seedAndStall(): Set<String> {
        TestSetup.health(receiver, setOf(STEPS))
        fixture.assertNoForeignRecords(StepsRecord::class)
        val seeded = fixture.insert(fixture.steps(321, ago(40), ago(30)), fixture.steps(123, ago(25), ago(20))).toSet()
        prefs.setPendingDeletions(DeletionSummary(deleted = listOf(DeletedRecord("steps", "deleted-before-the-stop"))))
        receiver.stall(TestSetup.HEALTH_PATH)
        return seeded
    }

    /** Starts a sync and returns once its post is at the receiver: the watermark step is behind it. */
    private suspend fun syncUntilPosted(scope: kotlinx.coroutines.CoroutineScope): Job {
        val job = scope.launch(Dispatchers.IO) { TestSetup.syncManager().performSync() }
        receiver.awaitRequests(1)
        assertNotNull("the watermark moved before the post", prefs.getHealthLastSyncTimestamp(STEPS))
        assertTrue("the deletion left storage", prefs.getPendingDeletions().isEmpty)
        assertEquals("the payload is on disk, in flight", 1, store.inFlightIds().size)
        assertEquals("and out of the outbox", 0, store.size())
        return job
    }

    /** What the receiver took from the request after the stalled one on, each record once. */
    private fun assertDeliveredOnceAfterTheStall(seeded: Set<String>) {
        val delivered = receiver.exchanges.drop(1).filter { it.responseCode in 200..299 }.map { Conservation.parse(it.text) }
        Conservation.assertExactlyOnce(seeded, delivered)
        val deletions = delivered.flatMap { body -> body.arr("deleted_records").orEmpty().map { (it as JsonObject).str("uuid") } }
        assertEquals("the stored deletion arrives once", listOf("deleted-before-the-stop"), deletions)
        assertEquals(0, store.size())
        assertEquals(emptyList<String>(), store.inFlightIds())
    }

    /**
     * A drain beside a sync that is posting, as the tile's Screen Time sync runs one: it does not
     * post the payload in flight. The stopped sync then queues it, without a failure, and the
     * next sync delivers each record exactly once.
     */
    @Test
    fun concurrentDrainDoesNotRepostInFlightItem() = runBlocking {
        val seeded = seedAndStall()
        val job = syncUntilPosted(this)

        PendingDrainer.drain(context)
        Thread.sleep(500)
        assertEquals("the drain posted nothing", 1, receiver.exchanges.size)
        assertEquals("and left the payload in flight", 1, store.inFlightIds().size)

        job.cancelAndJoin()
        assertEquals("a stopped post leaves the payload in the outbox", 1, store.size())
        assertEquals(emptyList<String>(), store.inFlightIds())
        assertEquals("a stop is not a failure", 0, TestSetup.streak("HEALTH_CONNECT"))
        assertEquals(0, prefs.getWebhookLogs(LogType.HEALTH_CONNECT).count { !it.success })

        receiver.respond(TestSetup.HEALTH_PATH, 200)
        TestSetup.syncManager().performSync().getOrThrow()
        assertDeliveredOnceAfterTheStall(seeded)
    }

    /**
     * A process killed after the watermark step, while the post was out: on disk that leaves
     * the moved watermark and the payload in flight, with no sync left to see it through. The
     * test builds that state from a stopped sync (a kill cannot be done from inside the app's
     * own process): it keeps the in-flight file as the post found it and puts it back once the
     * stop has queued it. The next sync's drain queues and delivers it, each record once.
     */
    @Test
    fun syncKilledBetweenWatermarkAndPostOutcomeLosesNothing() = runBlocking {
        val seeded = seedAndStall()
        val job = syncUntilPosted(this)
        val inFlightDir = File(context.filesDir, "pending_sync/in_flight")
        val left = inFlightDir.listFiles { f -> f.extension == "json" }!!.single()
        val bytes = left.readBytes()

        job.cancelAndJoin()
        // Undo what only a living process does after the post: the stop's queuing.
        store.peekAll().forEach { store.remove(it.id) }
        File(inFlightDir, left.name).writeBytes(bytes)
        assertEquals(0, store.size())
        assertEquals(1, store.inFlightIds().size)

        receiver.respond(TestSetup.HEALTH_PATH, 200)
        TestSetup.syncManager().performSync().getOrThrow()
        assertDeliveredOnceAfterTheStall(seeded)
    }
}
