package com.owen282000.lifedashboard.harness

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.SpeedRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.health.connect.client.units.Energy
import androidx.health.connect.client.units.Mass
import androidx.health.connect.client.units.Velocity
import com.owen282000.lifedashboard.HealthDataType
import kotlinx.coroutines.runBlocking
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.reflect.KClass

/**
 * Exact, small Health Connect data under the app's own package, for the tests that need it.
 *
 * Never with a clientRecordId: an own record that carries one is what Receive writes, and the
 * sync leaves those out on purpose (ResilientReadLogic.isReceiveWrite). Idempotent by deleting
 * instead: [AppStateRule] deletes the app's own records of every type written here before the
 * next test, and a delete can only ever touch the caller's own records.
 *
 * Times are relative to the phone's clock and always end in the past, since Health Connect
 * refuses records that end in the future.
 */
class HcFixture(private val context: Context) {

    val client: HealthConnectClient = CountingHealthConnectClient(HealthConnectClient.getOrCreate(context))

    private val device = Device(manufacturer = "LdSuite", model = "Fixture Watch", type = Device.TYPE_WATCH)
    private fun meta() = Metadata.autoRecorded(device)

    /** Inserts [records] and returns the metadata ids Health Connect gave them, in order. */
    fun insert(vararg records: Record): List<String> = runBlocking {
        records.forEach { written += it::class }
        client.insertRecords(records.toList()).recordIdsList
    }

    fun steps(count: Long, start: Instant, end: Instant): StepsRecord =
        StepsRecord(start, offset(start), end, offset(end), count, meta())

    fun weight(kilograms: Double, time: Instant): WeightRecord =
        WeightRecord(time, offset(time), Mass.kilograms(kilograms), meta())

    fun heartRate(samples: List<Pair<Instant, Long>>): HeartRateRecord = HeartRateRecord(
        samples.first().first, offset(samples.first().first),
        samples.last().first, offset(samples.last().first),
        samples.map { (time, bpm) -> HeartRateRecord.Sample(time, bpm) },
        meta()
    )

    fun speed(samples: List<Pair<Instant, Double>>): SpeedRecord = SpeedRecord(
        samples.first().first, offset(samples.first().first),
        samples.last().first, offset(samples.last().first),
        samples.map { (time, metersPerSecond) -> SpeedRecord.Sample(time, Velocity.metersPerSecond(metersPerSecond)) },
        meta()
    )

    /** One minute of total calories from [start], the way Fitbit writes them. */
    fun calorieMinute(start: Instant, kilocalories: Double = 1.2): TotalCaloriesBurnedRecord =
        TotalCaloriesBurnedRecord(start, offset(start), start.plusSeconds(60), offset(start), Energy.kilocalories(kilocalories), meta())

    /** A night from [start] to [end] with the given stages, each as (start, end, stage type). */
    fun sleep(start: Instant, end: Instant, stages: List<Triple<Instant, Instant, Int>>): SleepSessionRecord = SleepSessionRecord(
        startTime = start, startZoneOffset = offset(start), endTime = end, endZoneOffset = offset(end),
        metadata = meta(), title = "LdSuite night",
        stages = stages.map { (from, to, stage) -> SleepSessionRecord.Stage(from, to, stage) }
    )

    /** Inserts [records] in batches of [batchSize], each with a modification time of its own. */
    fun insertInBatches(records: List<Record>, batchSize: Int): List<String> = records.chunked(batchSize).flatMap {
        Thread.sleep(15)
        insert(*it.toTypedArray())
    }

    /** Reads every record of [type] in the lookback window the sync uses. */
    fun <T : Record> read(type: KClass<T>, from: Instant = Instant.now().minus(Duration.ofDays(8))): List<T> = runBlocking {
        client.readRecords(ReadRecordsRequest(type, TimeRangeFilter.between(from, Instant.now().plusSeconds(60)))).records
    }

    /**
     * Fails when another app has records of [types] in the sync's lookback window. The suite
     * asserts that everything it receives is what it seeded, so data from, say, the
     * :hc-fixture seeder on this AVD would fail it; this says so plainly instead.
     */
    fun assertNoForeignRecords(vararg types: KClass<out Record>) {
        val foreign = types.flatMap { type -> read(type).map { it.metadata.dataOrigin.packageName } }
            .filter { it != context.packageName }
            .groupingBy { it }.eachCount()
        check(foreign.isEmpty()) {
            "Other apps have Health Connect records on this device ($foreign). The suite needs an AVD where only the " +
                "app writes; clear the fixture's data (ClearFixtureData in :hc-fixture) or wipe ldc-instrumented."
        }
    }

    /** Deletes records of the app by metadata id; a test's way to make Health Connect report a deletion. */
    fun delete(type: KClass<out Record>, vararg ids: String) = runBlocking {
        client.deleteRecords(type, ids.toList(), emptyList())
    }

    companion object {
        /** The record types written through a fixture since the last reset; [AppStateRule] deletes those. */
        val written: MutableSet<KClass<out Record>> = java.util.concurrent.ConcurrentHashMap.newKeySet()

        /**
         * A moment [minutesAgo] before now, whole seconds. Close to midnight the helper waits
         * until 00:03 first, so a test never has "today" change under it halfway.
         */
        fun ago(minutesAgo: Long): Instant = Instant.now().minus(Duration.ofMinutes(minutesAgo)).truncatedTo(ChronoUnit.SECONDS)

        fun awayFromMidnight() {
            while (true) {
                val now = LocalTime.now()
                if (now.isAfter(LocalTime.of(0, 3)) && now.isBefore(LocalTime.of(23, 57))) return
                Thread.sleep(10_000)
            }
        }

        private fun offset(time: Instant) = ZoneId.systemDefault().rules.getOffset(time)

        /** Every record type the app may write, as far as the granted permissions go. */
        fun writableTypes(granted: Set<String>): List<KClass<out Record>> =
            HealthDataType.entries.map { it.recordClass }.distinct()
                .filter { HealthPermission.getWritePermission(it) in granted }
    }
}
