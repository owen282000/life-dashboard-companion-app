package com.owen282000.lifedashboard

import androidx.health.connect.client.records.ExerciseLap
import androidx.health.connect.client.records.ExerciseSegment
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.Length
import com.owen282000.lifedashboard.ExerciseSupport.putExercise
import com.owen282000.lifedashboard.ExerciseSupport.toExerciseData
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

/**
 * Exercise sessions with what the source app wrote inside them: a session without segments or
 * laps reads exactly as before, segments and laps go out as optional arrays.
 */
class ExercisePayloadTest {

    private val start = Instant.parse("2026-10-14T18:00:00Z")
    private val phone = Device(manufacturer = "Test", model = "Unit", type = Device.TYPE_PHONE)

    private fun json(session: ExerciseData) = buildJsonObject { putExercise(session) }

    private fun minutes(n: Long): Instant = start.plusSeconds(60 * n)

    @Test
    fun aSessionWithoutSegmentsOrLapsEmitsExactlyTheOriginalKeys() {
        val session = ExerciseData("70", start, minutes(40), Duration.ofMinutes(40), "com.example.lifting", "uuid-1")
        assertEquals(
            setOf("type", "start_time", "end_time", "duration_seconds", "uuid", "source"),
            json(session).keys
        )
    }

    @Test
    fun aStrengthSessionCarriesItsSetsWithTheirRepetitions() {
        val record = ExerciseSessionRecord(
            startTime = start,
            startZoneOffset = ZoneOffset.UTC,
            endTime = minutes(40),
            endZoneOffset = ZoneOffset.UTC,
            metadata = Metadata.manualEntry(phone),
            exerciseType = ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING,
            segments = listOf(
                ExerciseSegment(start, minutes(1), ExerciseSegment.EXERCISE_SEGMENT_TYPE_SQUAT, 5),
                ExerciseSegment(minutes(1), minutes(4), ExerciseSegment.EXERCISE_SEGMENT_TYPE_REST),
                ExerciseSegment(minutes(4), minutes(5), ExerciseSegment.EXERCISE_SEGMENT_TYPE_BENCH_PRESS, 5)
            )
        )
        val obj = json(record.toExerciseData())
        assertEquals("70", obj.getValue("type").jsonPrimitive.content)
        assertEquals(2400, obj.getValue("duration_seconds").jsonPrimitive.int)
        val segments = obj.getValue("segments").jsonArray.map { it.jsonObject }
        assertEquals(3, segments.size)
        assertEquals("51", segments[0].getValue("type").jsonPrimitive.content)
        assertEquals(start.toString(), segments[0].getValue("start_time").jsonPrimitive.content)
        assertEquals(minutes(1).toString(), segments[0].getValue("end_time").jsonPrimitive.content)
        assertEquals(5, segments[0].getValue("repetitions").jsonPrimitive.int)
        assertEquals("44", segments[1].getValue("type").jsonPrimitive.content)
        assertFalse("a rest wrote no repetitions", "repetitions" in segments[1])
        assertEquals("5", segments[2].getValue("type").jsonPrimitive.content)
        assertFalse("no laps were written", "laps" in obj)
    }

    @Test
    fun aSwimCarriesItsLapsInMeters() {
        val record = ExerciseSessionRecord(
            startTime = start,
            startZoneOffset = ZoneOffset.UTC,
            endTime = minutes(10),
            endZoneOffset = ZoneOffset.UTC,
            metadata = Metadata.manualEntry(phone),
            exerciseType = ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_POOL,
            laps = listOf(
                ExerciseLap(start, minutes(1), Length.meters(25.0)),
                ExerciseLap(minutes(1), minutes(2))
            )
        )
        val obj = json(record.toExerciseData())
        val laps = obj.getValue("laps").jsonArray.map { it.jsonObject }
        assertEquals(2, laps.size)
        assertEquals(25.0, laps[0].getValue("meters").jsonPrimitive.double, 1e-9)
        assertFalse("a lap without a length has no meters", "meters" in laps[1])
        assertFalse("no segments were written", "segments" in obj)
    }

    @Test
    fun recordMetadataGoesUnderMetadataAfterTheUuid() {
        val record = ExerciseSessionRecord(
            startTime = start,
            startZoneOffset = ZoneOffset.ofHours(2),
            endTime = minutes(40),
            endZoneOffset = ZoneOffset.ofHours(2),
            metadata = Metadata.manualEntry(clientRecordId = "workout-7", clientRecordVersion = 1),
            exerciseType = ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING
        )
        val data = record.toExerciseData().copy(uuid = "uuid-1", source = "com.example.lifting")
        val obj = buildJsonObject { putExercise(data, RecordMeta.of(record).toJson()) }
        assertEquals("workout-7", obj.getValue("metadata").jsonObject.getValue("client_record_id").jsonPrimitive.content)
        assertEquals(listOf("uuid", "metadata", "source"), obj.keys.filter { it in setOf("uuid", "metadata", "source") })
    }

    @Test
    fun segmentAndLapKeysAreDeclaredInThePublishedSchema() {
        val schema = Json.parseToJsonElement(File("../docs/webhook-schema.json").readText()).jsonObject
        val exercise = schema.getValue("properties").jsonObject
            .getValue("exercise").jsonObject
            .getValue("items").jsonObject
            .getValue("properties").jsonObject
        fun itemKeys(key: String) = exercise.getValue(key).jsonObject
            .getValue("items").jsonObject
            .getValue("properties").jsonObject.keys
        assertTrue("schema missing segments", "segments" in exercise)
        assertTrue("schema missing laps", "laps" in exercise)
        assertEquals(ExerciseSupport.SEGMENT_KEYS.toSet(), itemKeys("segments"))
        assertEquals(ExerciseSupport.LAP_KEYS.toSet(), itemKeys("laps"))
    }
}
