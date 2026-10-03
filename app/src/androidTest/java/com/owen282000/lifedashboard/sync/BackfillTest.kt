package com.owen282000.lifedashboard.sync

import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import com.owen282000.lifedashboard.BACKFILL_DATA_TYPE
import com.owen282000.lifedashboard.BACKFILL_RUN_DATA_TYPE
import com.owen282000.lifedashboard.BackfillFailure
import com.owen282000.lifedashboard.BackfillJobStore
import com.owen282000.lifedashboard.BackfillRun
import com.owen282000.lifedashboard.BackfillWorker
import com.owen282000.lifedashboard.HealthConnectManager
import com.owen282000.lifedashboard.HealthDataType.STEPS
import com.owen282000.lifedashboard.HealthDataType.WEIGHT
import com.owen282000.lifedashboard.HealthSyncManager
import com.owen282000.lifedashboard.LogType
import com.owen282000.lifedashboard.PendingSyncStore
import com.owen282000.lifedashboard.SyncStatusStore
import com.owen282000.lifedashboard.WriteBackType
import com.owen282000.lifedashboard.appPreferences
import com.owen282000.lifedashboard.harness.AppStateRule
import com.owen282000.lifedashboard.harness.Conservation
import com.owen282000.lifedashboard.harness.HcCall
import com.owen282000.lifedashboard.harness.HcCalls
import com.owen282000.lifedashboard.harness.HcFixture
import com.owen282000.lifedashboard.harness.HcFixture.Companion.ago
import com.owen282000.lifedashboard.harness.Receiver
import com.owen282000.lifedashboard.harness.Schema
import com.owen282000.lifedashboard.harness.SlowHealthConnectClient
import com.owen282000.lifedashboard.harness.TestSetup
import com.owen282000.lifedashboard.harness.Witness
import com.owen282000.lifedashboard.harness.arr
import com.owen282000.lifedashboard.harness.num
import com.owen282000.lifedashboard.harness.str
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Backfill: a one-time export of history in three-day windows, each drained in capped chunks.
 * A receiver treats window_complete as "what this window did not carry is gone", so it may
 * only ever be true on the chunk that drained the window (issue #61, 1.18.0 review c), and
 * every chunk carries the window's daily totals (1.17.1). Since P2-14 it runs as a stored job
 * that resumes at the window it had not finished, and writes one row per run in the Logs tab.
 */
@RunWith(AndroidJUnit4::class)
class BackfillTest {

    private val receiver = Receiver()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(receiver).around(AppStateRule()).around(Witness.Rule(receiver))

    private val context = TestSetup.context
    private val fixture = HcFixture(context)
    private val prefs get() = context.appPreferences()

    /** T43. Three windows over seven days, the busy one in two chunks, everything exactly once. */
    @Test
    fun windowsProgressAndSnapshots() = runBlocking {
        TestSetup.health(receiver, setOf(STEPS, WEIGHT), receive = setOf(WriteBackType.WEIGHT))
        fixture.assertNoForeignRecords(StepsRecord::class, WeightRecord::class)
        val day = 24 * 60L
        val steps = fixture.insert(*Array(7) { i -> fixture.steps(100L + i, ago(i * day + 70), ago(i * day + 60)) })
        // 250 weights five days back, in five inserts with their own modification times.
        val weights = (0 until 5).flatMap { batch ->
            Thread.sleep(20)
            fixture.insert(*Array(50) { i -> fixture.weight(70.0 + i / 10.0, ago(5 * day + batch * 50 + i)) })
        }
        val progress = mutableListOf<Pair<Int, Int>>()

        val run = TestSetup.backfill(7) { done, total -> progress += done to total } as BackfillRun.Done

        assertEquals(steps.size + weights.size, run.sent)
        assertEquals(listOf(1 to 3, 2 to 3, 3 to 3), progress)
        assertNull("a finished job is cleared", BackfillJobStore.load(context))
        val posts = receiver.exchanges.map { Conservation.parse(it.text) }
        posts.forEachIndexed { i, post -> assertEquals(emptyList<String>(), Schema.errors(receiver.exchanges[i].text, "chunk$i")) }
        Conservation.assertExactlyOnce((steps + weights).toSet(), posts)
        posts.forEach { post ->
            assertEquals("true", post.num("backfill"))
            assertNull("backfill never carries the writeback block", post["writeback"])
            assertNotNull("daily totals in every chunk", post.arr("daily_totals"))
        }
        assertEquals("sequence continues the counter", (1..posts.size).map { it.toString() }, posts.map { it.num("sequence") })

        val windows = posts.groupBy { it.str("window_start") to it.str("window_end") }
        assertEquals(3, windows.size)
        val bounds = windows.keys.map { Instant.parse(it.first) to Instant.parse(it.second) }
        bounds.zipWithNext().forEach { (a, b) -> assertEquals("windows meet", a.second, b.first) }
        assertTrue("from seven days back", Duration.between(bounds.first().first, bounds.last().second) == Duration.ofDays(7))
        windows.values.forEach { chunks ->
            assertEquals("only the last chunk is complete", List(chunks.size - 1) { "false" } + "true", chunks.map { it.num("window_complete") })
            assertEquals("the same totals in every chunk of a window", 1, chunks.map { it["daily_totals"] }.distinct().size)
        }
        assertEquals("the busy window took two chunks", 2, windows.values.first().size)

        assertNull("backfill leaves the watermarks alone", prefs.getHealthLastSyncTimestamp(WEIGHT))
        assertNull("and the changes tokens", prefs.getHealthChangesToken(STEPS))
        assertNotNull(SyncStatusStore.read(context, LogType.HEALTH_CONNECT).lastSyncMillis)

        // P2-14: one row for the run, not one per chunk.
        val row = prefs.getWebhookLogs(LogType.HEALTH_CONNECT).single()
        assertEquals(BACKFILL_RUN_DATA_TYPE, row.dataType)
        assertTrue(row.success)
        assertEquals(steps.size + weights.size, row.recordCount)
    }

    /** T44. An empty window still sends one complete snapshot: that is how a receiver learns it is empty. */
    @Test
    fun emptyWindowSendsOneCompletePayload() = runBlocking {
        TestSetup.health(receiver, setOf(STEPS))
        fixture.assertNoForeignRecords(StepsRecord::class)

        val run = TestSetup.backfill(3) as BackfillRun.Done

        assertEquals(0, run.sent)
        val body = Conservation.parse(receiver.exchanges.single().text)
        assertEquals("true", body.num("window_complete"))
        assertEquals(emptyList<Any>(), Conservation.records(body))
    }

    /**
     * T45. A failed window stops the backfill there, and nothing goes to the outbox. The failed
     * delivery keeps its own row in the Logs tab, next to the run's row that says it failed.
     */
    @Test
    fun backfillStopsAtFirstFailure() = runBlocking {
        TestSetup.health(receiver, setOf(STEPS))
        receiver.respond(TestSetup.HEALTH_PATH, 500)

        val run = TestSetup.backfill(7) as BackfillRun.Failed

        assertEquals(BackfillFailure.Delivery(done = 0, total = 3), run.failure)
        assertEquals(0, PendingSyncStore.forContext(context).size())
        assertEquals("three attempts at the first window, none at the next", 3, receiver.exchanges.size)
        assertEquals(1, receiver.exchanges.map { Conservation.parse(it.text).str("window_start") }.distinct().size)
        assertEquals("the job stays at the first window", 0, BackfillJobStore.load(context)?.nextWindow)
        val rows = prefs.getWebhookLogs(LogType.HEALTH_CONNECT)
        assertEquals(listOf(BACKFILL_DATA_TYPE, BACKFILL_RUN_DATA_TYPE), rows.map { it.dataType }.sortedBy { it })
        assertTrue("both say it failed", rows.none { it.success })
    }

    /**
     * P2-14. A backfill that Android stops after its first window continues at the second when
     * it runs again, instead of at the first: the stored job is the cursor. The stop is not a
     * failure; the run before it still gets its one row, and so does the run after it.
     */
    @Test
    fun aStoppedBackfillResumesAtTheNextWindow() = runBlocking {
        TestSetup.health(receiver, setOf(STEPS))
        fixture.assertNoForeignRecords(StepsRecord::class)
        val day = 24 * 60L
        val steps = fixture.insert(*Array(7) { i -> fixture.steps(100L + i, ago(i * day + 70), ago(i * day + 60)) })

        // The first window goes through; the second one's post never gets an answer, and the
        // run is stopped while it waits, as Android stops a worker.
        val stopped = launch(Dispatchers.IO) {
            TestSetup.backfill(7) { done, _ -> if (done == 1) receiver.stall(TestSetup.HEALTH_PATH) }
        }
        receiver.awaitRequests(2)
        stopped.cancel()
        stopped.join()

        val job = BackfillJobStore.load(context)
        assertNotNull("the job is kept", job)
        assertEquals("at the window after the one that went through", 1, job!!.nextWindow)
        val firstRow = prefs.getWebhookLogs(LogType.HEALTH_CONNECT).single()
        assertEquals(BACKFILL_RUN_DATA_TYPE, firstRow.dataType)
        assertTrue("a stop is not a failure", firstRow.success)
        assertEquals(job.recordsSent, firstRow.recordCount)

        receiver.respond(TestSetup.HEALTH_PATH, 200)
        val mark = receiver.exchanges.size
        val run = TestSetup.backfill(7) as BackfillRun.Done

        val first = Conservation.parse(receiver.exchanges[0].text)
        val stalled = Conservation.parse(receiver.exchanges[1].text)
        val resumed = receiver.since(mark).map { Conservation.parse(it.text) }
        assertEquals("windows two and three, nothing of the first", 2, resumed.size)
        assertEquals("it starts where the stop was", stalled.str("window_start"), resumed.first().str("window_start"))
        assertTrue(resumed.none { it.str("window_start") == first.str("window_start") })
        Conservation.assertExactlyOnce(steps.toSet(), listOf(first) + resumed)
        assertEquals("the job counts both runs", steps.size, run.job.recordsSent)
        assertNull("and is cleared once done", BackfillJobStore.load(context))
        val rows = prefs.getWebhookLogs(LogType.HEALTH_CONNECT)
        assertEquals("one row per run", listOf(BACKFILL_RUN_DATA_TYPE, BACKFILL_RUN_DATA_TYPE), rows.map { it.dataType })
        assertTrue(rows.all { it.success })
    }

    /**
     * P2-14 review. A window too dense for one run continues with its next chunk when the work
     * runs again, not with its first: the chunk cursor is saved with the job after every payload.
     * Before, a window that outlasted the worker was sent from its start on every rerun.
     */
    @Test
    fun aStoppedBackfillResumesHalfwayThroughAWindow() = runBlocking {
        TestSetup.health(receiver, setOf(WEIGHT))
        fixture.assertNoForeignRecords(WeightRecord::class)
        val day = 24 * 60L
        // 250 weights five days back, in five inserts with their own modification times: the
        // first window takes more than one chunk, as in windowsProgressAndSnapshots.
        val weights = (0 until 5).flatMap { batch ->
            Thread.sleep(20)
            fixture.insert(*Array(50) { i -> fixture.weight(70.0 + i / 10.0, ago(5 * day + batch * 50 + i)) })
        }

        // The first chunk goes through, the second one's post never gets an answer, and the run
        // is stopped while it waits, as Android stops a worker that ran out of time.
        receiver.answerThenStall(TestSetup.HEALTH_PATH, answered = 1)
        val stopped = launch(Dispatchers.IO) { TestSetup.backfill(7) }
        receiver.awaitRequests(2)
        stopped.cancel()
        stopped.join()

        val job = BackfillJobStore.load(context)!!
        assertEquals("still in the first window", 0, job.nextWindow)
        assertEquals("after its first chunk", 1, job.chunksSent)

        receiver.respond(TestSetup.HEALTH_PATH, 200)
        val mark = receiver.exchanges.size
        val run = TestSetup.backfill(7) as BackfillRun.Done

        val first = Conservation.parse(receiver.exchanges[0].text)
        val resumed = receiver.since(mark).map { Conservation.parse(it.text) }
        assertEquals("it goes on in the first window", first.str("window_start"), resumed.first().str("window_start"))
        // The first chunk is not sent again, and nothing is lost.
        Conservation.assertExactlyOnce(weights.toSet(), listOf(first) + resumed)
        val firstWindow = (listOf(first) + resumed).filter { it.str("window_start") == first.str("window_start") }
        assertEquals("the last chunk of the window completes it", "true", firstWindow.last().num("window_complete"))
        assertEquals(weights.size, run.job.recordsSent)
        assertNull(BackfillJobStore.load(context))
    }

    /** P2-14 review. A job whose data types changed while it was stopped starts over from its first window, and says so. */
    @Test
    fun aJobWhoseTypesChangedStartsOver() = runBlocking {
        TestSetup.health(receiver, setOf(STEPS))
        fixture.assertNoForeignRecords(StepsRecord::class, WeightRecord::class)
        val job = BackfillJobStore.startOrContinue(context, days = 7)
        BackfillJobStore.saveIfCurrent(context, job.afterWindow(0, System.currentTimeMillis()))
        prefs.setHealthEnabledDataTypes(setOf(STEPS, WEIGHT))
        var restarted = false

        val run = TestSetup.syncManager().runBackfill(BackfillJobStore.load(context)!!, onRestarted = { restarted = true }) as BackfillRun.Done

        assertTrue("it says so", restarted)
        assertEquals(listOf("STEPS", "WEIGHT"), run.job.types)
        val starts = receiver.exchanges.map { Conservation.parse(it.text).str("window_start") }.distinct()
        assertEquals("every window, the first included", 3, starts.size)
        assertEquals(Instant.ofEpochMilli(job.rangeStart).toString(), starts.first())
        val row = prefs.getWebhookLogs(LogType.HEALTH_CONNECT).single()
        assertTrue("the run's row says it started over: ${row.rawPayload}", row.rawPayload.orEmpty().contains("\"restarted\":true"))
    }

    /** P2-14. The worker runs the stored job, and with none stored (cancelled before it ran) it does nothing. */
    @Test
    fun theWorkerRunsTheStoredJob() = runBlocking {
        TestSetup.health(receiver, setOf(STEPS))
        fixture.assertNoForeignRecords(StepsRecord::class)
        fixture.insert(fixture.steps(10, ago(30), ago(20)))

        val idle = TestListenableWorkerBuilder<BackfillWorker>(context).build().doWork()
        assertEquals(ListenableWorker.Result.success(), idle)
        assertEquals(0, receiver.exchanges.size)

        BackfillJobStore.startOrContinue(context, days = 3)
        val result = TestListenableWorkerBuilder<BackfillWorker>(context).build().doWork()

        assertEquals(ListenableWorker.Result.success(workDataOf("records" to 1)), result)
        assertEquals(1, receiver.exchanges.size)
        assertNull(BackfillJobStore.load(context))
    }

    /**
     * A backfill started while a sync runs waits for it, says so, and posts only after it. Both
     * draw from the same sequence counter, so side by side they would interleave. The helper and
     * the screen are tested on the JVM; this proves runBackfill really takes the sync lock.
     */
    @Test
    fun backfillWaitsForARunningSync() = runBlocking {
        TestSetup.health(receiver, setOf(STEPS))
        fixture.assertNoForeignRecords(StepsRecord::class)
        fixture.insert(fixture.steps(10, ago(30), ago(20)))
        val slow = SlowHealthConnectClient(HealthConnectClient.getOrCreate(context), delayMs = 5_000)
        slow.held += HcCall.READ_RECORDS
        val readsBefore = HcCalls.snapshot()[HcCall.READ_RECORDS] ?: 0

        val sync = async(Dispatchers.IO) { HealthSyncManager(context, HealthConnectManager(context) { slow }).performSync() }
        withTimeout(10_000) { while ((HcCalls.snapshot()[HcCall.READ_RECORDS] ?: 0) == readsBefore) delay(20) }
        val waits = CopyOnWriteArrayList<Boolean>()
        val backfill = async(Dispatchers.IO) { TestSetup.backfill(3, onWaiting = { waits += it }) }
        withTimeout(10_000) { while (waits.isEmpty()) delay(20) }

        assertEquals("the backfill says it waits", listOf(true), waits.toList())
        assertFalse("the sync still runs", sync.isCompleted)
        assertEquals("nothing posted yet", 0, receiver.exchanges.size)
        slow.held.clear()

        withTimeout(60_000) { sync.await().getOrThrow() }
        assertEquals(1, (withTimeout(60_000) { backfill.await() } as BackfillRun.Done).sent)
        assertEquals(listOf(true, false), waits.toList())
        val flags = receiver.exchanges.map { Conservation.parse(it.text).num("backfill") }
        assertEquals("the sync's post first, then the backfill's", listOf(null, "true"), flags)
    }
}
