package com.owen282000.lifedashboard

import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.aggregate.AggregateMetric
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.MindfulnessSessionRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.health.connect.client.units.Energy
import androidx.health.connect.client.units.Length
import androidx.health.connect.client.units.Mass
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.random.Random

/**
 * Seeds a week of believable Health Connect data on a device that has none (an emulator), for
 * the eight essential types plus mindfulness. Not a test of the app: a data generator that
 * happens to run inside the app's process so it can use the debug build's write permissions.
 *
 * Grant the write permissions through Health Connect's own dialog, not with `adb shell pm grant`:
 * a `pm grant` makes inserts succeed but never registers the app as a data source, and only
 * data sources count in aggregates, so the daily totals (and the app's "today" sensors) stay
 * empty while the records are visibly there. The test opens the dialog when a permission is
 * missing; accept it on the device. Then, with the test APK installed next to the debug app:
 *
 *   adb shell am instrument -w -e class com.owen282000.lifedashboard.SeedHealthConnect \
 *     com.owen282000.lifedashboard.test/androidx.test.runner.AndroidJUnitRunner
 *
 * (`connectedDebugAndroidTest` reinstalls the app and drops the grants, so install both APKs
 * with adb and use `am instrument`.) Deterministic (fixed seed) and idempotent: running it
 * again updates the same records because Health Connect deduplicates on client record ids,
 * which also makes them count as new for the app's watermarks.
 */
@RunWith(AndroidJUnit4::class)
class SeedHealthConnect {

    @Test
    fun seedSevenDays() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val client = HealthConnectClient.getOrCreate(context)

        // Ask Health Connect itself for the write permissions instead of relying on `pm grant`:
        // a grant through its own dialog also registers the app as a data source, and only
        // data sources count in aggregates (the daily totals the app and its MQTT sensors use).
        val writePermissions = setOf(
            StepsRecord::class, SleepSessionRecord::class, HeartRateRecord::class, RestingHeartRateRecord::class,
            DistanceRecord::class, ActiveCaloriesBurnedRecord::class, TotalCaloriesBurnedRecord::class,
            WeightRecord::class, MindfulnessSessionRecord::class
        ).map { HealthPermission.getWritePermission(it) }.toSet()
        if (!client.permissionController.getGrantedPermissions().containsAll(writePermissions)) {
            // Health permissions are runtime permissions on API 34+; requesting them from an
            // Activity opens Health Connect's own dialog, which the seeding script accepts.
            val wanted = (writePermissions + HealthConnectManager.ALL_PERMISSIONS).toTypedArray()
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { it.requestPermissions(wanted, 4711) }
                println("Waiting for the Health Connect permission dialog to be accepted")
                repeat(90) {
                    Thread.sleep(1000)
                    if (client.permissionController.getGrantedPermissions().containsAll(writePermissions)) return@repeat
                }
            }
            check(client.permissionController.getGrantedPermissions().containsAll(writePermissions)) { "Write permissions were not granted" }
        }
        val zone = ZoneId.systemDefault()
        val offset = zone.rules.getOffset(Instant.now())
        val random = Random(20260914)
        val today = LocalDate.now(zone)
        // Health Connect rejects records that end in the future, so today stops at now.
        val now = Instant.now().minusSeconds(60)

        // Recorded "by a watch": Health Connect's aggregates (daily totals) leave manual entries
        // out on some versions, and a watch is what real data looks like anyway.
        val watch = Device(manufacturer = "Seed", model = "Emulator Watch", type = Device.TYPE_WATCH)
        fun meta(id: String) = Metadata.autoRecordedWithId(id, watch)
        fun at(date: LocalDate, time: LocalTime): Instant = date.atTime(time).atZone(zone).toInstant()

        val records = mutableListOf<androidx.health.connect.client.records.Record>()

        for (dayOffset in 6 downTo 0) {
            val date = today.minusDays(dayOffset.toLong())
            val d = date.toString()

            // Steps and distance in hourly chunks between 07:00 and 22:00, more around commutes.
            var daySteps = 0L
            for (hour in 7..21) {
                val weight = when (hour) { 8, 17, 18 -> 3.0; 12, 13 -> 1.8; else -> 1.0 }
                val steps = (random.nextInt(150, 900) * weight).toLong()
                daySteps += steps
                val start = at(date, LocalTime.of(hour, 0)); val end = at(date, LocalTime.of(hour, 59))
                if (end.isAfter(now)) continue
                records += StepsRecord(start, offset, end, offset, steps, meta("seed-steps-$d-$hour"))
                records += DistanceRecord(start, offset, end, offset, Length.meters(steps * 0.74), meta("seed-dist-$d-$hour"))
                records += ActiveCaloriesBurnedRecord(start, offset, end, offset, Energy.kilocalories(steps * 0.04), meta("seed-acal-$d-$hour"))
            }
            records += TotalCaloriesBurnedRecord(
                at(date, LocalTime.of(0, 0)), offset, minOf(at(date, LocalTime.of(23, 59)), now), offset,
                Energy.kilocalories(1650.0 + daySteps * 0.04), meta("seed-tcal-$d")
            )

            // Heart rate: a sample every 10 minutes while awake, resting in the morning.
            val samples = mutableListOf<HeartRateRecord.Sample>()
            for (minute in 7 * 60 until 22 * 60 step 10) {
                val active = (minute / 60) in listOf(8, 17, 18)
                val bpm = if (active) random.nextLong(95, 140) else random.nextLong(58, 82)
                val t = at(date, LocalTime.of(minute / 60, minute % 60))
                if (t.isBefore(now)) samples += HeartRateRecord.Sample(t, bpm)
            }
            if (samples.size >= 2) records += HeartRateRecord(samples.first().time, offset, samples.last().time, offset, samples, meta("seed-hr-$d"))
            if (at(date, LocalTime.of(6, 30)).isBefore(now)) records += RestingHeartRateRecord(at(date, LocalTime.of(6, 30)), offset, random.nextLong(52, 61), meta("seed-rhr-$d"))

            // Weight every morning, drifting slowly.
            if (at(date, LocalTime.of(6, 45)).isBefore(now)) records += WeightRecord(at(date, LocalTime.of(6, 45)), offset, Mass.kilograms(78.4 - dayOffset * 0.05 + random.nextDouble(-0.3, 0.3)), meta("seed-weight-$d"))

            // Sleep from 23:15 the night before to 06:50, with stages.
            val sleepStart = at(date.minusDays(1), LocalTime.of(23, 15))
            val sleepEnd = at(date, LocalTime.of(6, 50))
            val stages = mutableListOf<SleepSessionRecord.Stage>()
            var cursor = sleepStart
            val cycle = listOf(SleepSessionRecord.STAGE_TYPE_LIGHT, SleepSessionRecord.STAGE_TYPE_DEEP, SleepSessionRecord.STAGE_TYPE_LIGHT, SleepSessionRecord.STAGE_TYPE_REM)
            var i = 0
            while (cursor.isBefore(sleepEnd)) {
                val next = minOf(cursor.plus(Duration.ofMinutes(random.nextLong(25, 55))), sleepEnd)
                stages += SleepSessionRecord.Stage(cursor, next, cycle[i % cycle.size]); cursor = next; i++
            }
            if (sleepEnd.isBefore(now)) records += SleepSessionRecord(sleepStart, offset, sleepEnd, offset, stages = stages, title = "Night", metadata = meta("seed-sleep-$d"))

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

        // Prove the aggregate sees them: this is what the app's daily totals and the MQTT
        // "today" sensors read.
        val agg = client.aggregate(
            AggregateRequest(
                metrics = setOf<AggregateMetric<*>>(StepsRecord.COUNT_TOTAL, DistanceRecord.DISTANCE_TOTAL, ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL, TotalCaloriesBurnedRecord.ENERGY_TOTAL),
                timeRangeFilter = TimeRangeFilter.between(today.atStartOfDay(), java.time.LocalDateTime.now())
            )
        )
        println("Aggregate today: steps=${agg[StepsRecord.COUNT_TOTAL]} distance=${agg[DistanceRecord.DISTANCE_TOTAL]?.inMeters} active=${agg[ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL]?.inKilocalories} total=${agg[TotalCaloriesBurnedRecord.ENERGY_TOTAL]?.inKilocalories}")
    }
}
