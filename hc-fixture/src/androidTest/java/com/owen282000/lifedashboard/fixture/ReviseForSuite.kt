package com.owen282000.lifedashboard.fixture

import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.Energy
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/**
 * Writes, deletes and writes again the way Fitbit revises a night or a day of calories: under
 * the same client record ids, so Health Connect gives the records their old ids back (issues
 * #71 and #72). Started by the app's ReinsertedRecordTest through the shell, with arguments:
 *
 * - `kind`: `sleep` (one night with stages), `calories` or `steps` (`count` minute records)
 * - `at`: the start, in epoch seconds
 * - `ops`: a comma list of `insert`, `delete` and `reinsert` (the first `keep` records only),
 *   run in that order
 * - `version`: the client record version to write (default 1). Writing the same records again
 *   with a higher one is an upsert: the ids stay and the modification time moves, the way a
 *   source that re-exports its last hour on every sync rewrites what it wrote before
 * - `per`: the steps in each minute record (default 10)
 * - `chunk`: records per insert call (default 100); one call is one write to Health Connect
 */
@RunWith(AndroidJUnit4::class)
class ReviseForSuite {

    @Test
    fun revise() = runBlocking {
        SelfGrant.ensure()
        val args = InstrumentationRegistry.getArguments()
        val client = HealthConnectClient.getOrCreate(InstrumentationRegistry.getInstrumentation().targetContext)
        val at = Instant.ofEpochSecond(args.getString("at")!!.toLong())
        val count = args.getString("count")?.toInt() ?: 1
        val keep = args.getString("keep")?.toInt() ?: count
        val offset = ZoneId.systemDefault().rules.getOffset(at)
        val watch = Device(manufacturer = "LdFixture", model = "Watch", type = Device.TYPE_WATCH)
        val version = args.getString("version")?.toLong() ?: 1L
        val perMinute = args.getString("per")?.toLong() ?: 10L
        val chunk = args.getString("chunk")?.toInt() ?: 100

        val records: List<Record> = when (args.getString("kind")) {
            "sleep" -> {
                val end = at.plus(Duration.ofHours(6))
                listOf(
                    SleepSessionRecord(
                        startTime = at, startZoneOffset = offset, endTime = end, endZoneOffset = offset,
                        metadata = Metadata.autoRecorded(watch, "ldsuite-night", version),
                        stages = listOf(
                            SleepSessionRecord.Stage(at, at.plus(Duration.ofHours(3)), SleepSessionRecord.STAGE_TYPE_LIGHT),
                            SleepSessionRecord.Stage(at.plus(Duration.ofHours(3)), end, SleepSessionRecord.STAGE_TYPE_DEEP)
                        )
                    )
                )
            }
            "steps" -> List(count) { i ->
                val start = at.plusSeconds(60L * i)
                StepsRecord(start, offset, start.plusSeconds(60), offset, perMinute, Metadata.autoRecorded(watch, "ldsuite-steps-$i", version))
            }
            else -> List(count) { i ->
                val start = at.plusSeconds(60L * i)
                TotalCaloriesBurnedRecord(
                    start, offset, start.plusSeconds(60), offset, Energy.kilocalories(1.0 + (i % 5) / 10.0),
                    Metadata.autoRecorded(watch, "ldsuite-cal-$i", version)
                )
            }
        }
        val type = records.first()::class
        for (op in args.getString("ops")!!.split(",")) {
            when (op) {
                "insert" -> records.chunked(chunk).forEach { client.insertRecords(it) }
                "reinsert" -> records.take(keep).chunked(chunk).forEach { client.insertRecords(it) }
                "delete" -> client.deleteRecords(type, emptyList(), records.map { it.metadata.clientRecordId!! })
                else -> error("unknown op $op")
            }
        }
        println("Revised: ${args.getString("ops")}")
    }
}
