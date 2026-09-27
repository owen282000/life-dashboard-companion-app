package com.owen282000.lifedashboard.sync

import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.test.ext.junit.runners.AndroidJUnit4
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
 * every chunk carries the window's daily totals (1.17.1).
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

        val result = TestSetup.syncManager().performBackfill(7) { done, total -> progress += done to total }.getOrThrow()

        assertEquals(steps.size + weights.size, result)
        assertEquals(listOf(1 to 3, 2 to 3, 3 to 3), progress)
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
    }

    /** T44. An empty window still sends one complete snapshot: that is how a receiver learns it is empty. */
    @Test
    fun emptyWindowSendsOneCompletePayload() = runBlocking {
        TestSetup.health(receiver, setOf(STEPS))
        fixture.assertNoForeignRecords(StepsRecord::class)

        val result = TestSetup.syncManager().performBackfill(3).getOrThrow()

        assertEquals(0, result)
        val body = Conservation.parse(receiver.exchanges.single().text)
        assertEquals("true", body.num("window_complete"))
        assertEquals(emptyList<Any>(), Conservation.records(body))
    }

    /** T45. A failed window stops the backfill there, and nothing goes to the outbox. */
    @Test
    fun backfillStopsAtFirstFailure() = runBlocking {
        TestSetup.health(receiver, setOf(STEPS))
        receiver.respond(TestSetup.HEALTH_PATH, 500)

        val result = TestSetup.syncManager().performBackfill(7)

        assertEquals("Delivery failed after 0 of 3 windows; rerun to resume", result.exceptionOrNull()?.message)
        assertEquals(0, PendingSyncStore.forContext(context).size())
        assertEquals("three attempts at the first window, none at the next", 3, receiver.exchanges.size)
        assertEquals(1, receiver.exchanges.map { Conservation.parse(it.text).str("window_start") }.distinct().size)
    }

    /**
     * A backfill started while a sync runs waits for it, says so, and posts only after it. Both
     * draw from the same sequence counter, so side by side they would interleave. The helper and
     * the screen are tested on the JVM; this proves performBackfill really takes the sync lock.
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
        val backfill = async(Dispatchers.IO) { TestSetup.syncManager().performBackfill(3, onWaiting = { waits += it }) }
        withTimeout(10_000) { while (waits.isEmpty()) delay(20) }

        assertEquals("the backfill says it waits", listOf(true), waits.toList())
        assertFalse("the sync still runs", sync.isCompleted)
        assertEquals("nothing posted yet", 0, receiver.exchanges.size)
        slow.held.clear()

        withTimeout(60_000) { sync.await().getOrThrow() }
        assertEquals(1, withTimeout(60_000) { backfill.await().getOrThrow() })
        assertEquals(listOf(true, false), waits.toList())
        val flags = receiver.exchanges.map { Conservation.parse(it.text).num("backfill") }
        assertEquals("the sync's post first, then the backfill's", listOf(null, "true"), flags)
    }
}
