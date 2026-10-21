package com.owen282000.lifedashboard.sync

import androidx.health.connect.client.records.SpeedRecord
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.owen282000.lifedashboard.HealthDataType.SPEED
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
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith

/**
 * Speed (issue #180) goes out like heart rate: one object per sample of a SpeedRecord, with the
 * record id and the sample time as its uuid, and a deleted record names that id.
 */
@RunWith(AndroidJUnit4::class)
class SpeedTest {

    private val receiver = Receiver()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(receiver).around(AppStateRule()).around(Witness.Rule(receiver))

    private val context = TestSetup.context
    private val fixture = HcFixture(context)

    private fun JsonObject.records(key: String) = arr(key).orEmpty().map { it as JsonObject }

    @Test
    fun everySampleGoesOutAndADeletionNamesTheRecord() = runBlocking {
        TestSetup.health(receiver, setOf(SPEED))
        fixture.assertNoForeignRecords(SpeedRecord::class)
        val times = listOf(ago(20), ago(19), ago(18))
        val (id) = fixture.insert(fixture.speed(times.zip(listOf(2.5, 3.1, 2.9))))

        TestSetup.syncManager().performSync().getOrThrow()
        val post = receiver.exchanges.last()
        assertEquals(emptyList<String>(), Schema.errors(post.text))
        val samples = Conservation.parse(post.text).records("speed")
        assertEquals(listOf(2.5, 3.1, 2.9), samples.map { it.getValue("meters_per_second").jsonPrimitive.double })
        assertEquals(times.map { "$id#${it.toEpochMilli()}" }, samples.map { it.getValue("uuid").jsonPrimitive.content })
        assertEquals(times.map { it.toString() }, samples.map { it.getValue("time").jsonPrimitive.content })

        fixture.delete(SpeedRecord::class, id)
        val mark = receiver.exchanges.size
        TestSetup.syncManager().performSync().getOrThrow()
        val deleted = Conservation.parse(receiver.since(mark).single().text).records("deleted_records")
        assertEquals(listOf("speed" to id), deleted.map { it.getValue("type").jsonPrimitive.content to it.getValue("uuid").jsonPrimitive.content })
    }
}
