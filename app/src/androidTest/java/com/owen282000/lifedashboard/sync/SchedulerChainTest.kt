package com.owen282000.lifedashboard.sync

import androidx.health.connect.client.records.StepsRecord
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.work.WorkInfo
import androidx.work.testing.TestListenableWorkerBuilder
import com.owen282000.lifedashboard.DeletedRecord
import com.owen282000.lifedashboard.DeletionSummary
import com.owen282000.lifedashboard.HealthDataType.STEPS
import com.owen282000.lifedashboard.HealthSyncWorker
import com.owen282000.lifedashboard.LogType
import com.owen282000.lifedashboard.PendingSyncStore
import com.owen282000.lifedashboard.SyncMode
import com.owen282000.lifedashboard.SyncSchedule
import com.owen282000.lifedashboard.SyncScheduler
import com.owen282000.lifedashboard.appPreferences
import com.owen282000.lifedashboard.harness.AppStateRule
import com.owen282000.lifedashboard.harness.Await
import com.owen282000.lifedashboard.harness.HcFixture
import com.owen282000.lifedashboard.harness.HcFixture.Companion.ago
import com.owen282000.lifedashboard.harness.Receiver
import com.owen282000.lifedashboard.harness.TestSetup
import com.owen282000.lifedashboard.harness.Witness
import com.owen282000.lifedashboard.harness.Work
import com.owen282000.lifedashboard.harness.WorkGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import java.time.LocalTime
import java.util.UUID

/**
 * The scheduling chain (SyncScheduler): a timed run queues the other slot when it finishes,
 * whatever the run's outcome, through the finally block of the worker. The chain broke in
 * 1.18.0 and the fix of 1.18.1 hangs on that finally block, so it is tested with the real
 * worker in the test WorkManager (WorkGate open) rather than with the pure schedule logic.
 */
@RunWith(AndroidJUnit4::class)
class SchedulerChainTest {

    private val receiver = Receiver()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(receiver).around(AppStateRule()).around(Witness.Rule(receiver))

    private val context = TestSetup.context
    private val fixture = HcFixture(context)
    private val prefs get() = context.appPreferences()

    /** A schedule with one fixed time two hours from now, so the queued run always waits. */
    private fun timedSchedule() = SyncSchedule(mode = SyncMode.TIMES, times = listOf(LocalTime.now().plusHours(2).withSecond(0).withNano(0)))

    /** T46. A timed run syncs for real and queues slot B; B in turn queues A, never its own name. */
    @Test
    fun timedRunQueuesTheOtherSlot() {
        TestSetup.health(receiver, setOf(STEPS))
        fixture.assertNoForeignRecords(StepsRecord::class)
        fixture.insert(fixture.steps(321, ago(30), ago(20)))
        prefs.setSyncSchedule(LogType.HEALTH_CONNECT, timedSchedule())
        WorkGate.open = true

        SyncScheduler.reschedule(context, LogType.HEALTH_CONNECT)
        val a = Work.awaitEnqueued(Work.HEALTH_SLOT_A)
        assertTrue(a.tags.containsAll(listOf(Work.TAG_SCHEDULED, Work.TAG_SLOT_A)))
        assertEquals("no periodic chain next to a timed one", emptyList<WorkInfo>(), Work.enqueued(Work.HEALTH_PERIODIC))

        Work.driver.setInitialDelayMet(a.id)
        Work.awaitState(a.id, WorkInfo.State.SUCCEEDED)

        assertEquals("the worker ran performSync", 1, receiver.to(TestSetup.HEALTH_PATH).size)
        val b = Work.awaitEnqueued(Work.HEALTH_SLOT_B)
        assertTrue(b.tags.containsAll(listOf(Work.TAG_SCHEDULED, Work.TAG_SLOT_B)))
        assertNotNull("a scheduled run records itself", prefs.getScheduleLastRun(LogType.HEALTH_CONNECT))
        assertEquals(emptyList<WorkInfo>(), Work.enqueued(Work.HEALTH_SLOT_A))

        Work.driver.setInitialDelayMet(b.id)
        Work.awaitState(b.id, WorkInfo.State.SUCCEEDED)
        val nextA = Work.awaitEnqueued(Work.HEALTH_SLOT_A)
        assertTrue("A is queued afresh", nextA.id != a.id)
        assertEquals(emptyList<WorkInfo>(), Work.enqueued(Work.HEALTH_SLOT_B))
    }

    /** T47. A run whose sync fails (no destination at all) still queues the next slot. */
    @Test
    fun failedRunStillQueuesTheNext() {
        prefs.setHealthEnabledDataTypes(setOf(STEPS))
        prefs.setSyncSchedule(LogType.HEALTH_CONNECT, timedSchedule())
        WorkGate.open = true

        SyncScheduler.reschedule(context, LogType.HEALTH_CONNECT)
        val a = Work.awaitEnqueued(Work.HEALTH_SLOT_A)
        Work.driver.setInitialDelayMet(a.id)

        assertEquals(WorkInfo.State.FAILED, Work.awaitState(a.id, WorkInfo.State.SUCCEEDED, WorkInfo.State.FAILED).state)
        Work.awaitEnqueued(Work.HEALTH_SLOT_B)
        assertEquals(0, receiver.exchanges.size)
    }

    /**
     * T48. A run Android stops while it waits on the receiver queues exactly one successor and
     * loses nothing: the payload, written ahead of its post with the stored deletion in it, waits
     * in the outbox while the watermark has moved past its records, and there is no trace of a
     * failure, because a stop is not a failed sync. The run that follows delivers it, and the
     * chain is still one chain.
     *
     * Until F1 the CancellationException of the stop was caught by WebhookManager's catch-all
     * around the backoff delay and logged as a failed delivery.
     */
    @LargeTest
    @Test
    fun stoppedRunQueuesExactlyOneSuccessorAndLosesNothing() {
        TestSetup.health(receiver, setOf(STEPS))
        fixture.assertNoForeignRecords(StepsRecord::class)
        fixture.insert(fixture.steps(321, ago(30), ago(20)))
        val pending = DeletionSummary(deleted = listOf(DeletedRecord("steps", "deleted-before-the-stop")))
        prefs.setPendingDeletions(pending)
        prefs.setSyncSchedule(LogType.HEALTH_CONNECT, timedSchedule())
        receiver.stall(TestSetup.HEALTH_PATH)

        val worker = TestListenableWorkerBuilder<HealthSyncWorker>(context)
            .setId(UUID.randomUUID())
            .setTags(listOf(Work.TAG_SCHEDULED, Work.TAG_SLOT_A))
            .build()
        val run = worker.startWork()
        receiver.awaitRequests(1)
        val stoppedAt = System.currentTimeMillis()
        run.cancel(true)

        // The finally block queues the successor once the sync has unwound. The call to the
        // receiver is cancelled with the worker (F1b), so that takes no more than a moment; a
        // blocking call would only notice at its 10 s read timeout.
        val b = Work.awaitEnqueued(Work.HEALTH_SLOT_B, timeoutMs = 15_000)
        val unwound = System.currentTimeMillis() - stoppedAt
        assertTrue("unwound within 3 s, took $unwound ms", unwound < 3_000)
        assertEquals(emptyList<WorkInfo>(), Work.enqueued(Work.HEALTH_SLOT_A))
        val queued = PendingSyncStore.forContext(context).peekAll().single()
        assertTrue("the payload waits in the outbox, with the deletion", "deleted-before-the-stop" in queued.payload)
        assertNotNull("the watermark moved past what it holds", prefs.getHealthLastSyncTimestamp(STEPS))
        assertEquals("the deletion left storage for the payload", DeletionSummary.EMPTY, prefs.getPendingDeletions())
        assertEquals("a stop is not a failure", 0, TestSetup.streak("HEALTH_CONNECT"))
        val failures = prefs.getWebhookLogs(LogType.HEALTH_CONNECT).filter { !it.success }
        assertEquals("no failed delivery logged for a stop: ${failures.map { it.errorMessage }}", 0, failures.size)

        // The successor delivers what the stopped run could not, and queues A again: one chain.
        receiver.respond(TestSetup.HEALTH_PATH, 200)
        WorkGate.open = true
        Work.driver.setInitialDelayMet(b.id)
        Work.awaitState(b.id, WorkInfo.State.SUCCEEDED)
        Work.awaitEnqueued(Work.HEALTH_SLOT_A)
        assertEquals(emptyList<WorkInfo>(), Work.enqueued(Work.HEALTH_SLOT_B))
        val delivered = receiver.exchanges.last()
        assertEquals(200, delivered.responseCode)
        assertTrue("the kept deletion goes out", "deleted-before-the-stop" in delivered.text)
    }

    /** T49. A plain interval stays one periodic WorkSpec, before and after a run. */
    @Test
    fun intervalModeStaysPeriodic() {
        TestSetup.health(receiver, setOf(STEPS))
        prefs.setSyncSchedule(LogType.HEALTH_CONNECT, SyncSchedule(mode = SyncMode.INTERVAL, intervalMinutes = 60))
        WorkGate.open = true

        SyncScheduler.reschedule(context, LogType.HEALTH_CONNECT)
        val periodic = Work.awaitEnqueued(Work.HEALTH_PERIODIC)
        assertEquals(emptyList<WorkInfo>(), Work.enqueued(Work.HEALTH_SLOT_A) + Work.enqueued(Work.HEALTH_SLOT_B))

        Work.driver.setPeriodDelayMet(periodic.id)
        // The run records itself in onSyncFinished and then re-plans; a periodic run goes back
        // to ENQUEUED when it is done.
        Await.until("the periodic run to report that it finished", 30_000) { prefs.getScheduleLastRun(LogType.HEALTH_CONNECT) != null }
        Work.awaitState(periodic.id, WorkInfo.State.ENQUEUED)
        val after = Work.infos(Work.HEALTH_PERIODIC).filter { it.state != WorkInfo.State.CANCELLED }
        assertEquals("still exactly one periodic WorkSpec", 1, after.size)
        assertEquals(periodic.id, after.single().id)
        assertEquals(emptyList<WorkInfo>(), Work.enqueued(Work.HEALTH_SLOT_A) + Work.enqueued(Work.HEALTH_SLOT_B))
    }
}
