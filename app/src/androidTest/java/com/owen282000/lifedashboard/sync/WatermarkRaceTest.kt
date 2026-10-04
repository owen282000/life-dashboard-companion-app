package com.owen282000.lifedashboard.sync

import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.metadata.DataOrigin
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import com.owen282000.lifedashboard.HealthDataType.STEPS
import com.owen282000.lifedashboard.harness.AppStateRule
import com.owen282000.lifedashboard.harness.Conservation
import com.owen282000.lifedashboard.harness.HcFixture
import com.owen282000.lifedashboard.harness.HcFixture.Companion.ago
import com.owen282000.lifedashboard.harness.Receiver
import com.owen282000.lifedashboard.harness.TestSetup
import com.owen282000.lifedashboard.harness.Witness
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList

/**
 * P2-12: is a record ever stamped with a modification time below one the sync has already
 * read? The sync reads records modified after its watermark, the newest modification time it
 * delivered. If Health Connect stamped a record when a write started and made it visible only
 * when the write finished, a small write by another app in between could be read first and move
 * the watermark past the big write, whose records would then never be read.
 *
 * Two writers at once: the :hc-fixture app inserts 5000 records in one call, while this app
 * writes single records and syncs in a loop. Measured on the API 36 emulator on 4 October 2026:
 * the call takes about a second, small writes go on during most of it with rising stamps, then
 * all 5000 records are stamped within a few milliseconds and the next small write waits for
 * them, stamped after. Health Connect stamps a write inside the transaction that makes it
 * visible, and writes from different apps take turns, so no small write is ever visible before
 * the large one with a stamp above it. The watermark needs no overlap; this test keeps watching
 * that, and that every record of both writers goes out.
 */
@RunWith(AndroidJUnit4::class)
class WatermarkRaceTest {

    private val receiver = Receiver()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(receiver).around(AppStateRule()).around(Witness.Rule(receiver))

    private val context = TestSetup.context
    private val fixture = HcFixture(context)

    private fun shell(command: String): ParcelFileDescriptor =
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)

    private fun read(fd: ParcelFileDescriptor): String = ParcelFileDescriptor.AutoCloseInputStream(fd).bufferedReader().use { it.readText() }

    private fun clearFixture() {
        read(shell("am instrument -w --no-hidden-api-checks -e class $FIXTURE.ClearFixtureData $FIXTURE.test/androidx.test.runner.AndroidJUnitRunner"))
    }

    private fun delivered(): Set<String> = receiver.exchanges.map { Conservation.parse(it.text) }
        .flatMap { (it["steps"] as? JsonArray).orEmpty() }
        .mapNotNull { ((it as JsonObject)["uuid"] as? JsonPrimitive)?.content }
        .toSet()

    /** Every steps record of the last eight days, all pages. */
    private suspend fun allSteps(): List<StepsRecord> {
        val out = mutableListOf<StepsRecord>()
        var token: String? = null
        do {
            val page = fixture.client.readRecords(
                ReadRecordsRequest(StepsRecord::class, TimeRangeFilter.after(ago(8 * 24 * 60)), pageSize = 5000, pageToken = token)
            )
            out += page.records
            token = page.pageToken
        } while (token != null)
        return out
    }

    /**
     * One small write by this app: when it was written, the modification time it got, and
     * whether the large write was visible right after this one was. A small write that was
     * visible while the large one was not, yet is stamped above the large write's records, is
     * exactly the case that would let a sync move its watermark past them.
     */
    private class Small(val id: String, val writtenAt: Long, val modified: Instant, val largeVisibleAfter: Boolean)

    private suspend fun largeVisible(): Boolean = fixture.client.readRecords(
        ReadRecordsRequest(StepsRecord::class, TimeRangeFilter.after(ago(8 * 24 * 60)), dataOriginFilter = setOf(DataOrigin(FIXTURE)), pageSize = 1)
    ).records.isNotEmpty()

    @LargeTest
    @Test
    fun aLargeWriteNextToSmallOnesLosesNothing() = runBlocking {
        TestSetup.health(receiver, setOf(STEPS))
        fixture.assertNoForeignRecords(StepsRecord::class)
        val count = 5000
        val small = CopyOnWriteArrayList<Small>()
        var firstForeignSeenAt = 0L
        try {
            // One insert call of `count` minute records: the large write.
            val startedAt = System.currentTimeMillis()
            val large = shell(
                "am instrument -w --no-hidden-api-checks -e class $FIXTURE.ReviseForSuite -e kind steps " +
                    "-e at ${ago(5 * 24 * 60).epochSecond} -e count $count -e chunk $count -e ops insert " +
                    "$FIXTURE.test/androidx.test.runner.AndroidJUnitRunner"
            )
            val largeOutput = async(Dispatchers.IO) { read(large) }

            // Small writes and syncs side by side until the large write is visible and a while after.
            val writer = launch(Dispatchers.IO) {
                var i = 0
                while (isActive) {
                    val time = ago(60).plusSeconds(i.toLong())
                    val id = fixture.insert(fixture.steps(1, time, time.plusSeconds(1))).single()
                    val modified = fixture.read(StepsRecord::class, ago(61)).single { it.metadata.id == id }.metadata.lastModifiedTime
                    val visible = largeVisible()
                    small += Small(id, System.currentTimeMillis(), modified, visible)
                    if (firstForeignSeenAt == 0L && visible) firstForeignSeenAt = System.currentTimeMillis()
                    i++
                    delay(5)
                }
            }
            val syncer = launch(Dispatchers.IO) {
                while (isActive) {
                    TestSetup.syncManager().performSync()
                    delay(10)
                }
            }
            val output = largeOutput.await()
            val largeDoneAt = System.currentTimeMillis()
            delay(1500)
            writer.cancel()
            syncer.cancel()
            writer.join()
            syncer.join()
            assertTrue("the fixture ran: $output", "OK (1 test)" in output)

            // Drain whatever is left.
            repeat(8) { TestSetup.syncManager().performSync() }

            val foreign = allSteps().filter { it.metadata.dataOrigin.packageName == FIXTURE }
            assertEquals(count, foreign.size)
            val stamps = foreign.map { it.metadata.lastModifiedTime }
            val minStamp = stamps.min()
            val maxStamp = stamps.max()
            val writtenBeforeVisible = small.filter { !it.largeVisibleAfter }
            val stampedAbove = writtenBeforeVisible.filter { it.modified > minStamp }
            Log.i(
                TAG,
                "large write: started $startedAt, done $largeDoneAt (${largeDoneAt - startedAt} ms), first seen " +
                    "$firstForeignSeenAt, stamps ${stamps.distinct().size} distinct from $minStamp to $maxStamp; small writes " +
                    "${small.size}, ${writtenBeforeVisible.size} visible before the large write, ${stampedAbove.size} of those " +
                    "stamped above its earliest record: ${stampedAbove.take(3).map { it.modified }}"
            )

            // The timeline of the small writes while the large one ran: a gap means they waited for it.
            small.filter { it.writtenAt in (startedAt - 200)..(largeDoneAt + 200) }.forEach {
                Log.i(TAG, "small write at ${Instant.ofEpochMilli(it.writtenAt)} stamped ${it.modified}, large visible after it: ${it.largeVisibleAfter}")
            }

            assertTrue("a small write visible before the large one but stamped above it: ${stampedAbove.map { it.modified }}", stampedAbove.isEmpty())
            val sent = delivered()
            val missingLarge = foreign.map { it.metadata.id }.filterNot { it in sent }
            val missingSmall = small.map { it.id }.filterNot { it in sent }
            assertTrue("large write records never sent: ${missingLarge.size} of $count", missingLarge.isEmpty())
            assertTrue("small writes never sent: ${missingSmall.size} of ${small.size}", missingSmall.isEmpty())
        } finally {
            clearFixture()
        }
    }

    private companion object {
        const val FIXTURE = "com.owen282000.lifedashboard.fixture"
        const val TAG = "WatermarkRace"
    }
}
