package com.owen282000.lifedashboard.sync

import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import com.owen282000.lifedashboard.DeletedRecord
import com.owen282000.lifedashboard.DeletionSummary
import com.owen282000.lifedashboard.HealthConnectManager
import com.owen282000.lifedashboard.HealthDataType
import com.owen282000.lifedashboard.HealthDataType.DISTANCE
import com.owen282000.lifedashboard.HealthDataType.HEART_RATE
import com.owen282000.lifedashboard.HealthDataType.SLEEP
import com.owen282000.lifedashboard.HealthDataType.STEPS
import com.owen282000.lifedashboard.HealthDataType.WEIGHT
import com.owen282000.lifedashboard.HealthSyncManager
import com.owen282000.lifedashboard.HealthSyncResult
import com.owen282000.lifedashboard.PendingSyncStore
import com.owen282000.lifedashboard.appPreferences
import com.owen282000.lifedashboard.harness.AppStateRule
import com.owen282000.lifedashboard.harness.Conservation
import com.owen282000.lifedashboard.harness.CountingHealthConnectClient
import com.owen282000.lifedashboard.harness.HcCall
import com.owen282000.lifedashboard.harness.HcFixture
import com.owen282000.lifedashboard.harness.HcFixture.Companion.ago
import com.owen282000.lifedashboard.harness.Receiver
import com.owen282000.lifedashboard.harness.Schema
import com.owen282000.lifedashboard.harness.SlowHealthConnectClient
import com.owen282000.lifedashboard.harness.TestSetup
import com.owen282000.lifedashboard.harness.Witness
import com.owen282000.lifedashboard.harness.arr
import com.owen282000.lifedashboard.harness.num
import com.owen282000.lifedashboard.harness.strings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import kotlin.math.abs

/**
 * Deletions (issue #61): Health Connect only reports a deletion through its changes feed, which
 * is consumed by reading it, so every deletion read must reach a receiver, exactly once, even
 * when nothing else changed and even when the delivery fails. 1.18.0 lost them in several ways.
 */
@RunWith(AndroidJUnit4::class)
class DeletionTrackingTest {

    private val receiver = Receiver()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(receiver).around(AppStateRule()).around(Witness.Rule(receiver))

    private val context = TestSetup.context
    private val fixture = HcFixture(context)
    private val prefs get() = context.appPreferences()

    private fun deleted(payload: JsonObject): List<Pair<String, String>> = payload.arr("deleted_records").orEmpty().map {
        val entry = it as JsonObject
        (entry["type"] as JsonPrimitive).content to (entry["uuid"] as JsonPrimitive).content
    }

    /** One synced steps record, so every later sync has a token to read the feed from. */
    private fun firstSync(types: Set<HealthDataType> = setOf(STEPS)): String = runBlocking {
        TestSetup.health(receiver, types)
        fixture.assertNoForeignRecords(StepsRecord::class)
        val (id) = fixture.insert(fixture.steps(400, ago(40), ago(30)))
        TestSetup.syncManager().performSync().getOrThrow()
        id
    }

    /** T26. The first sync registers a token per enabled type and reports no deletion. */
    @Test
    fun firstSyncRegistersTokens() {
        val types = setOf(STEPS, WEIGHT)
        firstSync(types)
        val now = System.currentTimeMillis()
        types.forEach { type ->
            assertNotNull("a token for $type", prefs.getHealthChangesToken(type))
            assertTrue("issued now", abs(now - prefs.getHealthChangesTokenIssuedAt(type)!!) < 60_000)
        }
        val body = Conservation.parse(receiver.exchanges.single().text)
        assertNull(body["deleted_records"])
        assertNull(body["deletions_unavailable"])
    }

    /** T27. A deletion and nothing else: a payload of its own, with a sequence and no records. */
    @Test
    fun deletionOnlySyncSendsItsOwnPayload() = runBlocking {
        val id = firstSync()
        fixture.delete(StepsRecord::class, id)
        val mark = receiver.exchanges.size

        val result = TestSetup.syncManager().performSync().getOrThrow()

        assertTrue("a sync that withdrew a record is not NoData: $result", result is HealthSyncResult.Success)
        val post = receiver.since(mark).single()
        assertEquals(emptyList<String>(), Schema.errors(post.text))
        val body = Conservation.parse(post.text)
        assertEquals(listOf("steps" to id), deleted(body))
        assertEquals("no record arrays", emptyList<Any>(), Conservation.records(body))
        assertEquals("2", body.num("sequence"))
        assertTrue(prefs.getPendingDeletions().isEmpty)
    }

    /** T28. A deletion whose delivery fails waits in the outbox and arrives with the next sync. */
    @Test
    fun deletionSurvivesFailedDelivery() = runBlocking {
        val id = firstSync()
        fixture.delete(StepsRecord::class, id)
        receiver.respond(TestSetup.HEALTH_PATH, 503, 503, 503, 200)
        val mark = receiver.exchanges.size

        TestSetup.syncManager().performSync().getOrThrow()

        assertEquals(3, receiver.since(mark).size)
        val queued = PendingSyncStore.forContext(context).peekAll().single()
        assertEquals(listOf("steps" to id), deleted(Conservation.parse(queued.payload)))
        assertTrue("the outbox holds it now, not the pending list", prefs.getPendingDeletions().isEmpty)

        val mark2 = receiver.exchanges.size
        TestSetup.syncManager().performSync().getOrThrow()

        val drained = receiver.since(mark2).single()
        assertEquals(200, drained.responseCode)
        assertEquals(listOf("steps" to id), deleted(Conservation.parse(drained.text)))
        assertEquals(0, PendingSyncStore.forContext(context).size())
    }

    /** T29. Deletions ride on the first payload of a sync that needs two passes, and only there. */
    @Test
    fun deletionsRideOnlyTheFirstPayload() = runBlocking {
        val id = firstSync(setOf(STEPS, WEIGHT))
        fixture.assertNoForeignRecords(WeightRecord::class)
        fixture.delete(StepsRecord::class, id)
        // Five inserts of 50, each with its own modification time, so the cap of 200 cuts
        // between batches rather than inside one.
        val weights = (0 until 5).flatMap { batch ->
            Thread.sleep(20)
            fixture.insert(*Array(50) { i -> fixture.weight(70.0 + i / 10.0, ago(300L - batch * 50 - i)) })
        }
        val mark = receiver.exchanges.size

        TestSetup.syncManager().performSync().getOrThrow()

        val posts = receiver.since(mark).map { Conservation.parse(it.text) }
        assertEquals(2, posts.size)
        assertEquals(listOf("steps" to id), deleted(posts[0]))
        assertNull("the second payload carries no deletions", posts[1]["deleted_records"])
        Conservation.assertExactlyOnce(weights.toSet(), posts)
    }

    /** T30. A token older than 30 days is not trusted: the type is named and a new token stored. */
    @Test
    fun expiredTokenIsReported() = runBlocking {
        firstSync()
        val token = prefs.getHealthChangesToken(STEPS)
        prefs.setHealthChangesToken(STEPS, token, System.currentTimeMillis() - 31L * 24 * 60 * 60 * 1000)
        val mark = receiver.exchanges.size

        TestSetup.syncManager().performSync().getOrThrow()

        val body = Conservation.parse(receiver.since(mark).single().text)
        assertEquals(listOf("steps"), body["deletions_unavailable"].strings())
        assertTrue("a fresh token", abs(System.currentTimeMillis() - prefs.getHealthChangesTokenIssuedAt(STEPS)!!) < 60_000)
    }

    /**
     * T31 (1.18.1). Health Connect's changes feed does not answer for any of five types. The
     * deletion step gives up after its budget of 20 s in total, the records still go out, all
     * five types are named in deletions_unavailable, and no token moves: the feed positions
     * stay for the next sync.
     */
    @LargeTest
    @Test
    fun deletionStepHonoursItsBudget() = runBlocking {
        val types = setOf(STEPS, HEART_RATE, WEIGHT, DISTANCE, SLEEP)
        firstSync(types)
        val tokens = types.associateWith { prefs.getHealthChangesToken(it) to prefs.getHealthChangesTokenIssuedAt(it) }
        val (fresh) = fixture.insert(fixture.steps(77, ago(15), ago(10)))
        val slow = SlowHealthConnectClient(HealthConnectClient.getOrCreate(context))
        slow.held += setOf(HcCall.GET_CHANGES, HcCall.GET_CHANGES_TOKEN)
        val mark = receiver.exchanges.size

        val started = System.currentTimeMillis()
        val result = withTimeout(45_000) { HealthSyncManager(context, HealthConnectManager(context) { slow }).performSync().getOrThrow() }
        val took = System.currentTimeMillis() - started

        assertTrue("took $took ms, the budget is 20 s", took in 19_000..26_000)
        assertEquals(HealthSyncResult.Success(mapOf(STEPS to 1), webhookCount = 1), result)
        val body = Conservation.parse(receiver.since(mark).single().text)
        Conservation.assertExactlyOnce(setOf(fresh), listOf(body))
        assertEquals(types.map { it.name.lowercase() }.sorted(), body["deletions_unavailable"].strings())
        types.forEach { assertEquals("token of $it unchanged", tokens[it], prefs.getHealthChangesToken(it) to prefs.getHealthChangesTokenIssuedAt(it)) }
        assertEquals(DeletionSummary.EMPTY, prefs.getPendingDeletions())
    }

    /**
     * A worker stopped halfway through the deletion step. A type read before the stop has
     * moved its token, and the feed behind the old token cannot be read again, so its
     * deletions must already be in storage. Red before the fix: they were only in memory,
     * and the stop lost them for good.
     */
    @Test
    fun aStopInTheDeletionStepKeepsWhatItAlreadyRead() = runBlocking {
        val id = firstSync(setOf(STEPS, WEIGHT))
        val stepsToken = prefs.getHealthChangesToken(STEPS)
        val weightToken = prefs.getHealthChangesToken(WEIGHT)
        fixture.delete(StepsRecord::class, id)

        // Steps is read first; the changes read after it hangs until the sync is stopped.
        val hung = CompletableDeferred<Unit>()
        val client = object : CountingHealthConnectClient(HealthConnectClient.getOrCreate(context)) {
            override suspend fun before(call: HcCall) {
                super.before(call)
                if (call == HcCall.GET_CHANGES && prefs.getHealthChangesToken(STEPS) != stepsToken) {
                    hung.complete(Unit)
                    awaitCancellation()
                }
            }
        }
        val sync = launch(Dispatchers.IO) { HealthSyncManager(context, HealthConnectManager(context) { client }).performSync() }
        withTimeout(20_000) { hung.await() }
        sync.cancelAndJoin()

        assertTrue("steps moved its token", prefs.getHealthChangesToken(STEPS) != stepsToken)
        assertEquals("weight kept its token", weightToken, prefs.getHealthChangesToken(WEIGHT))
        assertEquals(listOf(DeletedRecord("steps", id)), prefs.getPendingDeletions().deleted)

        val mark = receiver.exchanges.size
        TestSetup.syncManager().performSync().getOrThrow()
        assertEquals(listOf("steps" to id), receiver.since(mark).flatMap { deleted(Conservation.parse(it.text)) })
    }
}
