package com.owen282000.lifedashboard.sync

import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import com.owen282000.lifedashboard.BackfillJobStore
import com.owen282000.lifedashboard.BackfillRun
import com.owen282000.lifedashboard.HealthConnectManager
import com.owen282000.lifedashboard.HealthDataType.STEPS
import com.owen282000.lifedashboard.HealthDataType.WEIGHT
import com.owen282000.lifedashboard.HealthSyncManager
import com.owen282000.lifedashboard.appPreferences
import com.owen282000.lifedashboard.harness.AppStateRule
import com.owen282000.lifedashboard.harness.Conservation
import com.owen282000.lifedashboard.harness.HcCall
import com.owen282000.lifedashboard.harness.HcFixture
import com.owen282000.lifedashboard.harness.HcFixture.Companion.ago
import com.owen282000.lifedashboard.harness.Receiver
import com.owen282000.lifedashboard.harness.SlowHealthConnectClient
import com.owen282000.lifedashboard.harness.TestSetup
import com.owen282000.lifedashboard.harness.Witness
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/**
 * The read step against a Health Connect that does not answer (F3 of P2-4). The deletion
 * step and the Receive write step have budgets since 1.18.1 and 1.20.0; the ordinary read
 * and the daily totals do not, so a dozing phone whose Health Connect hangs holds the sync,
 * and with it the worker, as the deletion step did in 1.18.0. Both tests expect the budget
 * of HealthConnectManager: 10 s per Health Connect call and 120 s for the whole read step.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class ReadBudgetTest {

    private val receiver = Receiver()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(receiver).around(AppStateRule()).around(Witness.Rule(receiver))

    private val context = TestSetup.context
    private val fixture = HcFixture(context)

    /**
     * Every readRecords call hangs. The sync must come back within its budget, move no
     * watermark, and say in the log what it could not read. Red before F3: it never came
     * back ("the sync came back within 45 s" failed after 45 s).
     */
    @Test
    fun readStepHonoursABudget() = runBlocking {
        TestSetup.health(receiver, setOf(STEPS, WEIGHT))
        fixture.assertNoForeignRecords(StepsRecord::class, WeightRecord::class)
        fixture.insert(fixture.steps(10, ago(30), ago(20)), fixture.weight(80.0, ago(20)))
        val slow = SlowHealthConnectClient(HealthConnectClient.getOrCreate(context))
        slow.held += HcCall.READ_RECORDS

        val started = System.currentTimeMillis()
        val result = withTimeoutOrNull(45_000) { HealthSyncManager(context, HealthConnectManager(context) { slow }).performSync() }
        val took = System.currentTimeMillis() - started

        assertNotNull("the sync came back within 45 s", result)
        assertTrue("two calls of 10 s and some margin, took $took ms", took < 30_000)
        assertTrue("a Health Connect that answers for no type is a failure, not no data: $result", result!!.exceptionOrNull()?.message.orEmpty().contains("did not answer"))
        assertNull(context.appPreferences().getHealthLastSyncTimestamp(STEPS))
        assertNull(context.appPreferences().getHealthLastSyncTimestamp(WEIGHT))
    }

    /**
     * The daily totals aggregate hangs while the records read fine. The records must still go
     * out, within the budget. Red before F3: the sync never came back.
     */
    @Test
    fun dailyTotalsHonourABudget() = runBlocking {
        TestSetup.health(receiver, setOf(STEPS))
        fixture.assertNoForeignRecords(StepsRecord::class)
        val (id) = fixture.insert(fixture.steps(10, ago(30), ago(20)))
        val slow = SlowHealthConnectClient(HealthConnectClient.getOrCreate(context))
        slow.held += HcCall.AGGREGATE_GROUPED

        val started = System.currentTimeMillis()
        val result = withTimeoutOrNull(45_000) { HealthSyncManager(context, HealthConnectManager(context) { slow }).performSync() }
        val took = System.currentTimeMillis() - started

        assertNotNull("the sync came back within 45 s", result)
        assertTrue("took $took ms", took < 30_000)
        Conservation.assertExactlyOnce(setOf(id), receiver.exchanges.map { Conservation.parse(it.text) })
        assertEquals(1, receiver.exchanges.size)
    }

    /**
     * A backfill window whose types could not be read. A receiver treats window_complete as
     * "drop what this window did not carry", so a window read with a type missing must never
     * claim it, and the backfill stops so the user can run it again. Red before the fix: the
     * empty window went out as complete and the backfill reported success.
     */
    @Test
    fun backfillNeverCallsAnUnreadWindowComplete() = runBlocking {
        TestSetup.health(receiver, setOf(STEPS, WEIGHT))
        fixture.assertNoForeignRecords(StepsRecord::class, WeightRecord::class)
        fixture.insert(fixture.steps(10, ago(30), ago(20)), fixture.weight(80.0, ago(20)))
        val slow = SlowHealthConnectClient(HealthConnectClient.getOrCreate(context))
        slow.held += HcCall.READ_RECORDS

        val result = withTimeoutOrNull(60_000) { TestSetup.backfill(days = 1, HealthSyncManager(context, HealthConnectManager(context) { slow })) }

        assertNotNull("the backfill came back within 60 s", result)
        assertTrue("the backfill stops: $result", result is BackfillRun.Failed)
        assertEquals("the stored job stays at the window it could not read", 0, BackfillJobStore.load(context)?.nextWindow)
        val claims = receiver.exchanges.map { (Conservation.parse(it.text)["window_complete"] as? JsonPrimitive)?.content }
        assertTrue("no window claimed complete: $claims", claims.none { it == "true" })
    }
}
