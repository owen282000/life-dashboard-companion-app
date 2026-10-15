package com.owen282000.lifedashboard

import androidx.health.connect.client.records.ExerciseSessionRecord
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.time.Duration

/**
 * ExerciseSessionRecord to payload mapping. The session's own keys are the ones the app always
 * sent; what the source app wrote inside the session goes out as two optional arrays: `segments`
 * (each exercise or set, such as five squats or a rest, with its repetitions) and `laps`. Without
 * them a backend sees that a strength workout happened but not what was lifted. Kept out of
 * HealthConnectManager and HealthSyncManager so the JSON writer can be unit tested without
 * Android, like NutritionSupport.
 */
object ExerciseSupport {

    /** The keys a segment and a lap can carry, for the schema lockstep test. */
    val SEGMENT_KEYS = listOf("type", "start_time", "end_time", "repetitions")
    val LAP_KEYS = listOf("start_time", "end_time", "meters")

    fun ExerciseSessionRecord.toExerciseData(): ExerciseData = ExerciseData(
        type = exerciseType.toString(),
        startTime = startTime,
        endTime = endTime,
        duration = Duration.between(startTime, endTime),
        source = metadata.dataOrigin.packageName,
        uuid = metadata.id,
        segments = segments.map { ExerciseSegmentData(it.segmentType, it.startTime, it.endTime, it.repetitions) },
        laps = laps.map { ExerciseLapData(it.startTime, it.endTime, it.length?.inMeters) }
    )

    /**
     * A session as the payload carries it. The arrays are left out when the source app wrote no
     * segments or laps, so such a session reads exactly as before; so is a segment's
     * `repetitions` when it is 0, Health Connect's value for none written.
     */
    fun JsonObjectBuilder.putExercise(session: ExerciseData, metadata: JsonObject? = null) {
        put("type", session.type)
        put("start_time", session.startTime.toString())
        put("end_time", session.endTime.toString())
        put("duration_seconds", session.duration.seconds)
        session.uuid?.let { put("uuid", it) }
        metadata?.let { put("metadata", it) }
        session.source?.let { put("source", it) }
        if (session.segments.isNotEmpty()) {
            putJsonArray("segments") {
                session.segments.forEach { segment ->
                    addJsonObject {
                        put("type", segment.type.toString())
                        put("start_time", segment.startTime.toString())
                        put("end_time", segment.endTime.toString())
                        if (segment.repetitions > 0) put("repetitions", segment.repetitions)
                    }
                }
            }
        }
        if (session.laps.isNotEmpty()) {
            putJsonArray("laps") {
                session.laps.forEach { lap ->
                    addJsonObject {
                        put("start_time", lap.startTime.toString())
                        put("end_time", lap.endTime.toString())
                        lap.meters?.let { put("meters", it) }
                    }
                }
            }
        }
    }
}
