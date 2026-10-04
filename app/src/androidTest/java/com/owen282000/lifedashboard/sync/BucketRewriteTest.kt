package com.owen282000.lifedashboard.sync

import android.os.ParcelFileDescriptor
import androidx.health.connect.client.records.StepsRecord
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.owen282000.lifedashboard.BackfillRun
import com.owen282000.lifedashboard.HealthDataType.STEPS
import com.owen282000.lifedashboard.HealthSyncResult
import com.owen282000.lifedashboard.SeriesBucketing
import com.owen282000.lifedashboard.SeriesResolution
import com.owen282000.lifedashboard.appPreferences
import com.owen282000.lifedashboard.harness.AppStateRule
import com.owen282000.lifedashboard.harness.Conservation
import com.owen282000.lifedashboard.harness.HcFixture
import com.owen282000.lifedashboard.harness.Receiver
import com.owen282000.lifedashboard.harness.TestSetup
import com.owen282000.lifedashboard.harness.Witness
import com.owen282000.lifedashboard.harness.num
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * A bucketed series whose window was already sent, and a source that writes that window again
 * (P2-16). A source that re-exports its last hour on every sync writes the same records again
 * under the same client record ids with a higher version: Health Connect keeps the ids and moves
 * the modification time, so the next sync reads them as changed. For raw records that is
 * harmless, a receiver deduplicates on uuid. For a bucketed window the receiver has only the
 * bucket, so what the app sends must leave it with the window Health Connect holds.
 *
 * The :hc-fixture app plays the source (ReviseForSuite with `kind=steps`), because what the app
 * itself writes with a client record id is a Receive write and never goes out.
 */
@RunWith(AndroidJUnit4::class)
class BucketRewriteTest {

    private val receiver = Receiver()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(receiver).around(AppStateRule()).around(Witness.Rule(receiver))

    private val context = TestSetup.context
    private val fixture = HcFixture(context)

    /** A whole UTC hour that closed well before now, so every sync treats its window as complete. */
    private val hour: Instant = Instant.now().truncatedTo(ChronoUnit.HOURS).minus(Duration.ofHours(3))

    private fun revise(vararg args: Pair<String, String>) {
        val extras = args.joinToString("") { (key, value) -> " -e $key $value" }
        val output = shell("am instrument -w --no-hidden-api-checks -e class $FIXTURE.ReviseForSuite$extras $FIXTURE.test/androidx.test.runner.AndroidJUnitRunner")
        assertTrue("the fixture ran: $output", "OK (1 test)" in output)
    }

    private fun clearFixture() {
        shell("am instrument -w --no-hidden-api-checks -e class $FIXTURE.ClearFixtureData $FIXTURE.test/androidx.test.runner.AndroidJUnitRunner")
    }

    private fun shell(command: String): String =
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
            .let { fd -> ParcelFileDescriptor.AutoCloseInputStream(fd).bufferedReader().use { it.readText() } }

    private fun steps(vararg extra: Pair<String, String>) =
        arrayOf("kind" to "steps", "at" to "${hour.epochSecond}", "count" to "60", *extra)

    /** Syncs until the app has nothing new, and returns the payloads that went out. */
    private fun syncAll(): List<JsonObject> = runBlocking {
        val mark = receiver.exchanges.size
        for (round in 1..6) {
            if (TestSetup.syncManager().performSync().getOrThrow() == HealthSyncResult.NoData) break
        }
        receiver.since(mark).map { Conservation.parse(it.text) }
    }

    /**
     * What a receiver that follows docs/webhook.md holds per window after every payload so far,
     * in sequence order: a bucket marked `complete` replaces the stored window, one without the
     * mark (1.22.0 and older) is combined with it by adding the totals.
     */
    private fun held(): Map<Instant, Double> {
        val windows = mutableMapOf<Instant, Double>()
        receiver.exchanges.map { Conservation.parse(it.text) }
            .sortedBy { it.num("sequence")?.toLong() ?: 0 }
            .forEach { payload ->
                (payload["steps"] as? JsonArray).orEmpty().map { it as JsonObject }.filter { "bucket_start" in it }.forEach { bucket ->
                    val start = Instant.parse((bucket["bucket_start"] as JsonPrimitive).content)
                    val total = (bucket["total"] as JsonPrimitive).doubleOrNull!!
                    val complete = (bucket["complete"] as? JsonPrimitive)?.booleanOrNull == true
                    windows[start] = if (complete) total else (windows[start] ?: 0.0) + total
                }
            }
        return windows
    }

    /** What Health Connect holds in the hour, from every source: what a receiver should end up with. */
    private fun truth(): Double =
        fixture.read(StepsRecord::class).filter { !it.startTime.isBefore(hour) && it.startTime.isBefore(hour.plus(Duration.ofHours(1))) }
            .sumOf { it.count }.toDouble()

    private fun bucketedHourly() {
        TestSetup.health(receiver, setOf(STEPS))
        context.appPreferences().setSeriesResolutions(mapOf(STEPS to SeriesResolution.HOURLY))
        fixture.assertNoForeignRecords(StepsRecord::class)
    }

    /** The same hour written again unchanged: the window still holds 600 steps, not 1200. */
    @Test
    fun aRewrittenHourIsNotCountedTwice() {
        bucketedHourly()
        try {
            revise(*steps("ops" to "insert"))
            syncAll()
            assertEquals("first sync", 600.0, held()[hour]!!, 0.0)

            revise(*steps("version" to "2", "ops" to "insert"))
            val second = syncAll()
            assertTrue("the rewrite was read again", second.isNotEmpty())
            assertEquals("Health Connect", 600.0, truth(), 0.0)
            assertEquals("a receiver holds the hour as Health Connect has it", truth(), held()[hour]!!, 0.0)
        } finally {
            clearFixture()
        }
    }

    /** The hour written again with other values, as an edit: the receiver ends with the new 720. */
    @Test
    fun anEditedHourReplacesTheOldValues() {
        bucketedHourly()
        try {
            revise(*steps("ops" to "insert"))
            syncAll()
            revise(*steps("version" to "2", "per" to "12", "ops" to "insert"))
            syncAll()
            assertEquals("Health Connect", 720.0, truth(), 0.0)
            assertEquals(truth(), held()[hour]!!, 0.0)
        } finally {
            clearFixture()
        }
    }

    /** Thirty more minutes from another source, late for the sent hour: they are added, nothing is lost. */
    @Test
    fun aLateRecordForASentHourIsAdded() {
        bucketedHourly()
        try {
            revise(*steps("ops" to "insert"))
            syncAll()
            fixture.insert(fixture.steps(300, hour.plus(Duration.ofMinutes(10)), hour.plus(Duration.ofMinutes(40))))
            syncAll()
            assertEquals("Health Connect", 900.0, truth(), 0.0)
            assertEquals(truth(), held()[hour]!!, 0.0)
        } finally {
            clearFixture()
        }
    }

    /**
     * A backfill splits its range into chunks of three days ending at the moment it started, so
     * a bound falls inside an hour. That hour went out from the second chunk with only its
     * minutes after the bound; the first chunk held its own minutes back and dropped them.
     * Reading a bucketed type from bucket bound to bucket bound sends every hour once, whole.
     */
    @Test
    fun aBackfillSendsTheHourAcrossAChunkBoundOnceAndWhole() = runBlocking {
        TestSetup.health(receiver, setOf(STEPS))
        context.appPreferences().setSeriesResolutions(mapOf(STEPS to SeriesResolution.HOURLY))
        // Three hours of minutes around the bound four days back, between the first two chunks.
        val bound = Instant.now().minus(Duration.ofDays(4))
        val firstHour = SeriesBucketing.alignDown(bound, SeriesResolution.HOURLY).minus(Duration.ofHours(1))
        fixture.insert(*Array(180) { i -> fixture.steps(10, firstHour.plusSeconds(60L * i), firstHour.plusSeconds(60L * i + 60)) })

        TestSetup.backfill(7) as BackfillRun.Done

        val buckets = receiver.exchanges.map { Conservation.parse(it.text) }
            .flatMap { (it["steps"] as? JsonArray).orEmpty() }.map { it as JsonObject }
        val starts = buckets.map { Instant.parse((it["bucket_start"] as JsonPrimitive).content) }
        assertEquals("each hour once: $starts", starts.distinct(), starts)
        assertEquals((0L..2L).map { firstHour.plus(Duration.ofHours(it)) }, starts.sorted())
        buckets.forEach { bucket ->
            assertEquals("a whole hour: $bucket", 600.0, (bucket["total"] as JsonPrimitive).doubleOrNull!!, 0.0)
            assertEquals(true, (bucket["complete"] as? JsonPrimitive)?.booleanOrNull)
        }
    }

    private companion object {
        const val FIXTURE = "com.owen282000.lifedashboard.fixture"
    }
}
