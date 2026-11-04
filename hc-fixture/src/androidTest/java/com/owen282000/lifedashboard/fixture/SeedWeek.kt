package com.owen282000.lifedashboard.fixture

import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.aggregate.AggregateMetric
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.MindfulnessSessionRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.health.connect.client.units.Energy
import androidx.health.connect.client.units.Length
import androidx.health.connect.client.units.Mass
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import kotlin.random.Random

/**
 * Seeds a week of believable Health Connect data from this fixture app, a data source of its
 * own, for the eight essential types plus weight and mindfulness: hourly steps, distance and
 * active calories, daily total calories, a heart rate sample every ten minutes, resting heart
 * rate, weight, sleep with stages, and meditation sessions. Not a test: a data generator for a
 * development emulator, so Life Dashboard Companion has something from another app to sync.
 *
 *   ./gradlew :hc-fixture:assembleDebug :hc-fixture:assembleDebugAndroidTest
 *   adb install -r -t hc-fixture/build/outputs/apk/debug/hc-fixture-debug.apk
 *   adb install -r -t hc-fixture/build/outputs/apk/androidTest/debug/hc-fixture-debug-androidTest.apk
 *   adb shell am instrument -w --no-hidden-api-checks -e class com.owen282000.lifedashboard.fixture.SeedWeek \
 *     com.owen282000.lifedashboard.fixture.test/androidx.test.runner.AndroidJUnitRunner
 *
 * Idempotent: every record carries a client record id derived from its day and slot, so a rerun
 * on the same day updates the records instead of adding a second copy. (The seeder this
 * replaces set `metadata.id`, which Health Connect ignores on insert, and doubled every day it
 * was run on.) The permissions are granted through the same route Health Connect's dialog
 * takes, which also makes the fixture count in aggregates. Never run it on the suite's own AVD
 * `ldc-instrumented`: the suite asserts that every record it receives is one it seeded.
 */
@RunWith(AndroidJUnit4::class)
class SeedWeek {

    @Test
    fun seedSevenDays() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val route = SelfGrant.ensure()
        println("Health Connect permissions: $route")
        val client = HealthConnectClient.getOrCreate(context)

        val zone = ZoneId.systemDefault()
        val offset = zone.rules.getOffset(Instant.now())
        val random = Random(20260914)
        val today = LocalDate.now(zone)
        // Health Connect rejects records that end in the future, so today stops at now.
        val now = Instant.now().minusSeconds(60)

        // Recorded "by a watch": Health Connect's aggregates leave manual entries out on some
        // versions, and a watch is what real data looks like anyway.
        val watch = Device(manufacturer = "Seed", model = "Emulator Watch", type = Device.TYPE_WATCH)
        fun meta(id: String) = Metadata.autoRecorded(watch, clientRecordId = id, clientRecordVersion = 1)
        fun at(date: LocalDate, time: LocalTime): Instant = date.atTime(time).atZone(zone).toInstant()

        val records = mutableListOf<Record>()

        for (dayOffset in 6 downTo 0) {
            val date = today.minusDays(dayOffset.toLong())
            val d = date.toString()

            // Steps and distance in hourly chunks between 07:00 and 22:00, more around commutes.
            var daySteps = 0L
            for (hour in 7..21) {
                val weight = when (hour) { 8, 17, 18 -> 3.0; 12, 13 -> 1.8; else -> 1.0 }
                val steps = (random.nextInt(150, 900) * weight).toLong()
                daySteps += steps
                val start = at(date, LocalTime.of(hour, 0))
                val end = at(date, LocalTime.of(hour, 59))
                if (end.isAfter(now)) continue
                records += StepsRecord(start, offset, end, offset, steps, meta("seed-steps-$d-$hour"))
                records += DistanceRecord(start, offset, end, offset, Length.meters(steps * 0.74), meta("seed-dist-$d-$hour"))
                records += ActiveCaloriesBurnedRecord(start, offset, end, offset, Energy.kilocalories(steps * 0.04), meta("seed-acal-$d-$hour"))
            }
            records += TotalCaloriesBurnedRecord(
                at(date, LocalTime.of(0, 0)), offset, minOf(at(date, LocalTime.of(23, 59)), now), offset,
                Energy.kilocalories(1650.0 + daySteps * 0.04), meta("seed-tcal-$d")
            )

            // Heart rate: a sample every 10 minutes while awake, faster around the commutes.
            val samples = mutableListOf<HeartRateRecord.Sample>()
            for (minute in 7 * 60 until 22 * 60 step 10) {
                val active = (minute / 60) in listOf(8, 17, 18)
                val bpm = if (active) random.nextLong(95, 140) else random.nextLong(58, 82)
                val t = at(date, LocalTime.of(minute / 60, minute % 60))
                if (t.isBefore(now)) samples += HeartRateRecord.Sample(t, bpm)
            }
            if (samples.size >= 2) {
                records += HeartRateRecord(samples.first().time, offset, samples.last().time, offset, samples, meta("seed-hr-$d"))
            }
            if (at(date, LocalTime.of(6, 30)).isBefore(now)) {
                records += RestingHeartRateRecord(at(date, LocalTime.of(6, 30)), offset, random.nextLong(52, 61), meta("seed-rhr-$d"))
            }

            // Weight every morning, drifting slowly.
            if (at(date, LocalTime.of(6, 45)).isBefore(now)) {
                records += WeightRecord(
                    at(date, LocalTime.of(6, 45)), offset,
                    Mass.kilograms(78.4 - dayOffset * 0.05 + random.nextDouble(-0.3, 0.3)), meta("seed-weight-$d")
                )
            }

            // Sleep from around 23:15 the night before to 06:50, with stages.
            val sleepStart = at(date.minusDays(1), LocalTime.of(22, 40).plusMinutes(random.nextLong(0, 70)))
            val sleepEnd = at(date, LocalTime.of(6, 50))
            val stages = mutableListOf<SleepSessionRecord.Stage>()
            var cursor = sleepStart
            val cycle = listOf(
                SleepSessionRecord.STAGE_TYPE_LIGHT, SleepSessionRecord.STAGE_TYPE_DEEP,
                SleepSessionRecord.STAGE_TYPE_LIGHT, SleepSessionRecord.STAGE_TYPE_REM
            )
            var i = 0
            while (cursor.isBefore(sleepEnd)) {
                val next = minOf(cursor.plus(Duration.ofMinutes(random.nextLong(25, 55))), sleepEnd)
                stages += SleepSessionRecord.Stage(cursor, next, cycle[i % cycle.size])
                cursor = next
                i++
            }
            if (sleepEnd.isBefore(now)) {
                records += SleepSessionRecord(sleepStart, offset, sleepEnd, offset, stages = stages, title = "Night", metadata = meta("seed-sleep-$d"))
            }

            // A short meditation most evenings.
            if (dayOffset % 2 == 0 && at(date, LocalTime.of(21, 30)).isBefore(now)) {
                val start = at(date, LocalTime.of(21, 10))
                records += MindfulnessSessionRecord(
                    start, offset, start.plus(Duration.ofMinutes(random.nextLong(8, 16))), offset,
                    mindfulnessSessionType = MindfulnessSessionRecord.MINDFULNESS_SESSION_TYPE_MEDITATION,
                    title = "Evening", metadata = meta("seed-mind-$d")
                )
            }
        }

        // Insert in chunks; Health Connect rejects very large single calls.
        records.chunked(300).forEach { client.insertRecords(it) }
        println("Seeded ${records.size} Health Connect records over 7 days")
        // A rerun must not add to this: the client record ids make the insert an update.
        val stored = client.readRecords(
            ReadRecordsRequest(StepsRecord::class, TimeRangeFilter.between(today.minusDays(7).atStartOfDay(), LocalDateTime.now()))
        ).records.count { it.metadata.dataOrigin.packageName == context.packageName }
        println("Steps records from this fixture in Health Connect: $stored")

        // Prove the aggregate sees them: this is what the app's daily totals and the MQTT
        // "today" sensors read.
        val agg = client.aggregate(
            AggregateRequest(
                metrics = setOf<AggregateMetric<*>>(
                    StepsRecord.COUNT_TOTAL, DistanceRecord.DISTANCE_TOTAL,
                    ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL, TotalCaloriesBurnedRecord.ENERGY_TOTAL
                ),
                timeRangeFilter = TimeRangeFilter.between(today.atStartOfDay(), LocalDateTime.now())
            )
        )
        println(
            "Aggregate today: steps=${agg[StepsRecord.COUNT_TOTAL]} distance=${agg[DistanceRecord.DISTANCE_TOTAL]?.inMeters} " +
                "active=${agg[ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL]?.inKilocalories} total=${agg[TotalCaloriesBurnedRecord.ENERGY_TOTAL]?.inKilocalories}"
        )
    }
}
