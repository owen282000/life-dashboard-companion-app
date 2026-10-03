package com.owen282000.lifedashboard.sync

import androidx.health.connect.client.records.WeightRecord
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.owen282000.lifedashboard.BackfillRun
import com.owen282000.lifedashboard.HealthDataType.WEIGHT
import com.owen282000.lifedashboard.HealthSyncResult
import com.owen282000.lifedashboard.Watermark
import com.owen282000.lifedashboard.appPreferences
import com.owen282000.lifedashboard.harness.AppStateRule
import com.owen282000.lifedashboard.harness.Conservation
import com.owen282000.lifedashboard.harness.HcFixture
import com.owen282000.lifedashboard.harness.HcFixture.Companion.ago
import com.owen282000.lifedashboard.harness.Receiver
import com.owen282000.lifedashboard.harness.Schema
import com.owen282000.lifedashboard.harness.TestSetup
import com.owen282000.lifedashboard.harness.Witness
import com.owen282000.lifedashboard.harness.num
import com.owen282000.lifedashboard.harness.obj
import com.owen282000.lifedashboard.harness.str
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * How far back a sync reads (LookbackWindow): a week before the last sync that
 * read the whole type, 30 days at most, with what did not fit named in the payload rather
 * than skipped in silence. The JVM tests cover the arithmetic; these cover the wiring on the
 * sync path, where the range has to reach Health Connect and the anchor has to be stored.
 */
@RunWith(AndroidJUnit4::class)
class LookbackTest {

    private val receiver = Receiver()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(receiver).around(AppStateRule()).around(Witness.Rule(receiver))

    private val context = TestSetup.context
    private val fixture = HcFixture(context)
    private val prefs get() = context.appPreferences()
    private val week = Duration.ofDays(7)

    private fun daysAgo(days: Long): Instant = Instant.now().minus(Duration.ofDays(days)).truncatedTo(ChronoUnit.SECONDS)

    private fun weightDiagnostics(body: JsonObject): JsonObject = body.obj("_diagnostics")!!.obj("weight")!!

    /** As if the last sync that read weight ran at [at] and nothing has run since. */
    private fun pausedSince(at: Instant) {
        prefs.setHealthCoveredUntil(WEIGHT, at)
        prefs.setHealthWatermark(WEIGHT, Watermark(at))
    }

    /**
     * Ten days without a sync, and the watch wrote a weight for the day before the pause only
     * now. The range starts a week before the pause, so it is read and sent; with a range a
     * week before now it was lost while the watermark moved past it.
     */
    @Test
    fun aPauseOfTenDaysStillSendsWhatWasWrittenBeforeIt() = runBlocking {
        TestSetup.health(receiver, setOf(WEIGHT))
        fixture.assertNoForeignRecords(WeightRecord::class)
        val pausedAt = daysAgo(10)
        pausedSince(pausedAt)
        val (id) = fixture.insert(fixture.weight(71.5, daysAgo(9)))

        TestSetup.syncManager().performSync().getOrThrow()

        val post = receiver.exchanges.single()
        assertEquals(emptyList<String>(), Schema.errors(post.text))
        val body = Conservation.parse(post.text)
        Conservation.assertExactlyOnce(setOf(id), listOf(body))
        val diag = weightDiagnostics(body)
        assertEquals(pausedAt - week, Instant.parse(diag.str("read_from")))
        assertNull(diag.str("lookback_gap_from"))
        assertTrue("the anchor moved to this sync", prefs.getHealthCoveredUntil(WEIGHT)!! > daysAgo(1))
    }

    /**
     * Forty days without a sync: the range stops at 30 days and the payload names where it
     * should have started. A sync that finds nothing new sends no payload, so it keeps the old
     * anchor and the next payload names the same gap.
     */
    @Test
    fun aPauseBeyondThirtyDaysNamesTheGapUntilAPayloadCarriesIt() = runBlocking {
        TestSetup.health(receiver, setOf(WEIGHT))
        fixture.assertNoForeignRecords(WeightRecord::class)
        val pausedAt = daysAgo(40)
        pausedSince(pausedAt)

        assertEquals(HealthSyncResult.NoData, TestSetup.syncManager().performSync().getOrThrow())
        assertEquals(0, receiver.exchanges.size)
        assertEquals("no payload named the gap, so the anchor stays", pausedAt, prefs.getHealthCoveredUntil(WEIGHT))

        val (id) = fixture.insert(fixture.weight(72.0, ago(30)))
        TestSetup.syncManager().performSync().getOrThrow()

        val body = Conservation.parse(receiver.exchanges.single().text)
        Conservation.assertExactlyOnce(setOf(id), listOf(body))
        val diag = weightDiagnostics(body)
        assertEquals(pausedAt - week, Instant.parse(diag.str("lookback_gap_from")))
        val readFrom = Instant.parse(diag.str("read_from"))
        assertTrue("read_from $readFrom is 30 days back", Duration.between(daysAgo(30), readFrom).abs() < Duration.ofMinutes(2))
        assertTrue("the payload named it, so the anchor moved", prefs.getHealthCoveredUntil(WEIGHT)!! > daysAgo(1))
    }

    /**
     * Syncs run normally, and a weight timestamped ten days back appears: the range starts a
     * week back, so the read cannot see it. The changes feed does, and the payload says so,
     * with the range a backfill needs.
     */
    @Test
    fun aRecordWrittenMoreThanAWeekLateIsNamed() = runBlocking {
        TestSetup.health(receiver, setOf(WEIGHT))
        fixture.assertNoForeignRecords(WeightRecord::class)
        val (first) = fixture.insert(fixture.weight(70.0, ago(60)))
        TestSetup.syncManager().performSync().getOrThrow()
        Conservation.assertExactlyOnce(setOf(first), listOf(Conservation.parse(receiver.exchanges.single().text)))
        val late = daysAgo(10)
        fixture.insert(fixture.weight(69.0, late))
        val mark = receiver.exchanges.size

        TestSetup.syncManager().performSync().getOrThrow()

        val post = receiver.since(mark).single()
        assertEquals(emptyList<String>(), Schema.errors(post.text))
        val body = Conservation.parse(post.text)
        assertEquals("the record itself is not in it", emptyList<Any>(), Conservation.records(body))
        val entry = body.obj("records_outside_window")?.obj("weight")
        assertNotNull("records_outside_window names weight: $body", entry)
        assertEquals("1", entry!!.num("count"))
        assertEquals(late, Instant.parse(entry.str("from")))
        assertTrue(Instant.parse(entry.str("until")) > late)
        assertTrue("delivered, so no longer carried", prefs.getPendingDeletions().isEmpty)
    }

    /** A backfill reads history on its own terms and moves nothing of the sync's, the anchor included. */
    @Test
    fun aBackfillLeavesTheAnchorAlone() = runBlocking {
        TestSetup.health(receiver, setOf(WEIGHT))
        val anchor = daysAgo(3)
        prefs.setHealthCoveredUntil(WEIGHT, anchor)
        fixture.insert(fixture.weight(70.0, ago(60)))

        assertTrue(TestSetup.backfill(3) is BackfillRun.Done)

        assertEquals(anchor, prefs.getHealthCoveredUntil(WEIGHT))
    }

    /** Looking is not sending: the preview reads with the stored anchor and stores none. */
    @Test
    fun thePreviewStoresNoAnchor() = runBlocking {
        TestSetup.health(receiver, setOf(WEIGHT))
        fixture.insert(fixture.weight(70.0, ago(60)))

        TestSetup.syncManager().previewData().getOrThrow()

        assertNull(prefs.getHealthCoveredUntil(WEIGHT))
    }
}
