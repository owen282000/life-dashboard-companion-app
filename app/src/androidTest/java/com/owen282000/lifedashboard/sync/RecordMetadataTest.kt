package com.owen282000.lifedashboard.sync

import android.os.ParcelFileDescriptor
import androidx.health.connect.client.records.StepsRecord
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.owen282000.lifedashboard.HealthDataType.HEART_RATE
import com.owen282000.lifedashboard.HealthDataType.STEPS
import com.owen282000.lifedashboard.HealthDataType.WEIGHT
import com.owen282000.lifedashboard.appPreferences
import com.owen282000.lifedashboard.harness.AppStateRule
import com.owen282000.lifedashboard.harness.Conservation
import com.owen282000.lifedashboard.harness.HcFixture
import com.owen282000.lifedashboard.harness.HcFixture.Companion.ago
import com.owen282000.lifedashboard.harness.Receiver
import com.owen282000.lifedashboard.harness.Schema
import com.owen282000.lifedashboard.harness.TestSetup
import com.owen282000.lifedashboard.harness.Witness
import com.owen282000.lifedashboard.harness.arr
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import java.time.Instant
import java.time.ZoneId

/**
 * P2-7: with Record metadata on, every record carries Health Connect's metadata for it, read
 * from records Health Connect really holds; off, the payload is what it always was.
 */
@RunWith(AndroidJUnit4::class)
class RecordMetadataTest {

    private val receiver = Receiver()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(receiver).around(AppStateRule()).around(Witness.Rule(receiver))

    private val context = TestSetup.context
    private val fixture = HcFixture(context)

    private fun syncOnce(): JsonObject = runBlocking {
        TestSetup.syncManager().performSync().getOrThrow()
        val post = receiver.exchanges.last()
        assertEquals(emptyList<String>(), Schema.errors(post.text))
        Conservation.parse(post.text)
    }

    private fun JsonObject.records(key: String) = arr(key).orEmpty().map { it as JsonObject }

    private fun seed() {
        fixture.insert(
            fixture.steps(120, ago(90), ago(80)),
            fixture.weight(72.4, ago(70)),
            fixture.heartRate(listOf(ago(60) to 61L, ago(59) to 64L))
        )
    }

    @Test
    fun offTheRecordsCarryNoMetadata() {
        TestSetup.health(receiver, setOf(STEPS, WEIGHT))
        seed()
        val payload = syncOnce()
        assertTrue(payload.records("steps").isNotEmpty())
        assertTrue(payload.records("steps").all { "metadata" !in it } && payload.records("weight").all { "metadata" !in it })
    }

    @Test
    fun onEveryRecordCarriesWhatHealthConnectHasForIt() {
        TestSetup.health(receiver, setOf(STEPS, WEIGHT, HEART_RATE))
        context.appPreferences().setIncludeRecordMetadata(true)
        seed()
        val payload = syncOnce()
        val offset = ZoneId.systemDefault().rules.getOffset(Instant.now()).id

        val steps = payload.records("steps").single().getValue("metadata").jsonObject
        assertEquals("automatic", steps.getValue("recording_method").jsonPrimitive.content)
        assertEquals("Fixture Watch", steps.getValue("device").jsonObject.getValue("model").jsonPrimitive.content)
        assertEquals("watch", steps.getValue("device").jsonObject.getValue("type").jsonPrimitive.content)
        assertEquals(offset, steps.getValue("start_zone_offset").jsonPrimitive.content)
        assertEquals(offset, steps.getValue("end_zone_offset").jsonPrimitive.content)
        assertTrue("written just now", Instant.parse(steps.getValue("last_modified").jsonPrimitive.content).isAfter(Instant.now().minusSeconds(300)))
        assertNull("the suite writes without a client record id", steps["client_record_id"])

        val weight = payload.records("weight").single().getValue("metadata").jsonObject
        assertEquals(offset, weight.getValue("zone_offset").jsonPrimitive.content)
        assertNull(weight["start_zone_offset"])

        val beats = payload.records("heart_rate")
        assertEquals(2, beats.size)
        assertTrue("each sample carries its record's metadata", beats.all { it.getValue("metadata").jsonObject.getValue("device").jsonObject.isNotEmpty() })
    }

    /** A source's own id and version for a record, written again with a higher version. */
    @Test
    fun aSourcesOwnIdAndVersionGoOut() {
        TestSetup.health(receiver, setOf(STEPS))
        context.appPreferences().setIncludeRecordMetadata(true)
        fixture.assertNoForeignRecords(StepsRecord::class)
        try {
            revise("kind" to "steps", "at" to "${ago(3 * 60).epochSecond}", "count" to "1", "version" to "3", "ops" to "insert")
            val step = syncOnce().records("steps").single { it["source"]?.jsonPrimitive?.content == FIXTURE }
            val meta = step.getValue("metadata").jsonObject
            assertEquals("ldsuite-steps-0", meta.getValue("client_record_id").jsonPrimitive.content)
            assertEquals(3L, meta.getValue("client_record_version").jsonPrimitive.long)
        } finally {
            shell("am instrument -w --no-hidden-api-checks -e class $FIXTURE.ClearFixtureData $FIXTURE.test/androidx.test.runner.AndroidJUnitRunner")
        }
    }

    private fun revise(vararg args: Pair<String, String>) {
        val extras = args.joinToString("") { (key, value) -> " -e $key $value" }
        val output = shell("am instrument -w --no-hidden-api-checks -e class $FIXTURE.ReviseForSuite$extras $FIXTURE.test/androidx.test.runner.AndroidJUnitRunner")
        assertTrue("the fixture ran: $output", "OK (1 test)" in output)
    }

    private fun shell(command: String): String =
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
            .let { fd -> ParcelFileDescriptor.AutoCloseInputStream(fd).bufferedReader().use { it.readText() } }

    private companion object {
        const val FIXTURE = "com.owen282000.lifedashboard.fixture"
    }
}
