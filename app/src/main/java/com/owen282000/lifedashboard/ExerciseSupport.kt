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
 * sent, with a readable `name` next to the type number and the session's `title` when the
 * recording app wrote one; what the source app wrote inside the session goes out as two optional arrays: `segments`
 * (each exercise or set, such as five squats or a rest, with its repetitions) and `laps`. Without
 * them a backend sees that a strength workout happened but not what was lifted. Kept out of
 * HealthConnectManager and HealthSyncManager so the JSON writer can be unit tested without
 * Android, like NutritionSupport.
 */
object ExerciseSupport {

    /** The keys a segment and a lap can carry, for the schema lockstep test. */
    val SEGMENT_KEYS = listOf("type", "start_time", "end_time", "repetitions")
    val LAP_KEYS = listOf("start_time", "end_time", "meters")

    /**
     * A readable name next to the type number: Health Connect's own name for the constant, in
     * lower case (`56` is `running`, `36` `high_intensity_interval_training`), so a receiver
     * needs no lookup table and the names match Google's documentation one for one. A number
     * from a newer Health Connect than this list gets no name; its `type` still goes out.
     */
    val EXERCISE_TYPE_NAMES: Map<Int, String> = mapOf(
        ExerciseSessionRecord.EXERCISE_TYPE_OTHER_WORKOUT to "other_workout",
        ExerciseSessionRecord.EXERCISE_TYPE_BADMINTON to "badminton",
        ExerciseSessionRecord.EXERCISE_TYPE_BASEBALL to "baseball",
        ExerciseSessionRecord.EXERCISE_TYPE_BASKETBALL to "basketball",
        ExerciseSessionRecord.EXERCISE_TYPE_BIKING to "biking",
        ExerciseSessionRecord.EXERCISE_TYPE_BIKING_STATIONARY to "biking_stationary",
        ExerciseSessionRecord.EXERCISE_TYPE_BOOT_CAMP to "boot_camp",
        ExerciseSessionRecord.EXERCISE_TYPE_BOXING to "boxing",
        ExerciseSessionRecord.EXERCISE_TYPE_CALISTHENICS to "calisthenics",
        ExerciseSessionRecord.EXERCISE_TYPE_CRICKET to "cricket",
        ExerciseSessionRecord.EXERCISE_TYPE_DANCING to "dancing",
        ExerciseSessionRecord.EXERCISE_TYPE_ELLIPTICAL to "elliptical",
        ExerciseSessionRecord.EXERCISE_TYPE_EXERCISE_CLASS to "exercise_class",
        ExerciseSessionRecord.EXERCISE_TYPE_FENCING to "fencing",
        ExerciseSessionRecord.EXERCISE_TYPE_FOOTBALL_AMERICAN to "football_american",
        ExerciseSessionRecord.EXERCISE_TYPE_FOOTBALL_AUSTRALIAN to "football_australian",
        ExerciseSessionRecord.EXERCISE_TYPE_FRISBEE_DISC to "frisbee_disc",
        ExerciseSessionRecord.EXERCISE_TYPE_GOLF to "golf",
        ExerciseSessionRecord.EXERCISE_TYPE_GUIDED_BREATHING to "guided_breathing",
        ExerciseSessionRecord.EXERCISE_TYPE_GYMNASTICS to "gymnastics",
        ExerciseSessionRecord.EXERCISE_TYPE_HANDBALL to "handball",
        ExerciseSessionRecord.EXERCISE_TYPE_HIGH_INTENSITY_INTERVAL_TRAINING to "high_intensity_interval_training",
        ExerciseSessionRecord.EXERCISE_TYPE_HIKING to "hiking",
        ExerciseSessionRecord.EXERCISE_TYPE_ICE_HOCKEY to "ice_hockey",
        ExerciseSessionRecord.EXERCISE_TYPE_ICE_SKATING to "ice_skating",
        ExerciseSessionRecord.EXERCISE_TYPE_MARTIAL_ARTS to "martial_arts",
        ExerciseSessionRecord.EXERCISE_TYPE_PADDLING to "paddling",
        ExerciseSessionRecord.EXERCISE_TYPE_PARAGLIDING to "paragliding",
        ExerciseSessionRecord.EXERCISE_TYPE_PILATES to "pilates",
        ExerciseSessionRecord.EXERCISE_TYPE_RACQUETBALL to "racquetball",
        ExerciseSessionRecord.EXERCISE_TYPE_ROCK_CLIMBING to "rock_climbing",
        ExerciseSessionRecord.EXERCISE_TYPE_ROLLER_HOCKEY to "roller_hockey",
        ExerciseSessionRecord.EXERCISE_TYPE_ROWING to "rowing",
        ExerciseSessionRecord.EXERCISE_TYPE_ROWING_MACHINE to "rowing_machine",
        ExerciseSessionRecord.EXERCISE_TYPE_RUGBY to "rugby",
        ExerciseSessionRecord.EXERCISE_TYPE_RUNNING to "running",
        ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL to "running_treadmill",
        ExerciseSessionRecord.EXERCISE_TYPE_SAILING to "sailing",
        ExerciseSessionRecord.EXERCISE_TYPE_SCUBA_DIVING to "scuba_diving",
        ExerciseSessionRecord.EXERCISE_TYPE_SKATING to "skating",
        ExerciseSessionRecord.EXERCISE_TYPE_SKIING to "skiing",
        ExerciseSessionRecord.EXERCISE_TYPE_SNOWBOARDING to "snowboarding",
        ExerciseSessionRecord.EXERCISE_TYPE_SNOWSHOEING to "snowshoeing",
        ExerciseSessionRecord.EXERCISE_TYPE_SOCCER to "soccer",
        ExerciseSessionRecord.EXERCISE_TYPE_SOFTBALL to "softball",
        ExerciseSessionRecord.EXERCISE_TYPE_SQUASH to "squash",
        ExerciseSessionRecord.EXERCISE_TYPE_STAIR_CLIMBING to "stair_climbing",
        ExerciseSessionRecord.EXERCISE_TYPE_STAIR_CLIMBING_MACHINE to "stair_climbing_machine",
        ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING to "strength_training",
        ExerciseSessionRecord.EXERCISE_TYPE_STRETCHING to "stretching",
        ExerciseSessionRecord.EXERCISE_TYPE_SURFING to "surfing",
        ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_OPEN_WATER to "swimming_open_water",
        ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_POOL to "swimming_pool",
        ExerciseSessionRecord.EXERCISE_TYPE_TABLE_TENNIS to "table_tennis",
        ExerciseSessionRecord.EXERCISE_TYPE_TENNIS to "tennis",
        ExerciseSessionRecord.EXERCISE_TYPE_VOLLEYBALL to "volleyball",
        ExerciseSessionRecord.EXERCISE_TYPE_WALKING to "walking",
        ExerciseSessionRecord.EXERCISE_TYPE_WATER_POLO to "water_polo",
        ExerciseSessionRecord.EXERCISE_TYPE_WEIGHTLIFTING to "weightlifting",
        ExerciseSessionRecord.EXERCISE_TYPE_WHEELCHAIR to "wheelchair",
        ExerciseSessionRecord.EXERCISE_TYPE_YOGA to "yoga"
    )

    fun ExerciseSessionRecord.toExerciseData(): ExerciseData = ExerciseData(
        type = exerciseType.toString(),
        title = title?.trim()?.takeIf { it.isNotEmpty() },
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
        session.type.toIntOrNull()?.let { EXERCISE_TYPE_NAMES[it] }?.let { put("name", it) }
        // What the user or the recording app called the session, such as "Leg day"; left out
        // when it wrote none.
        session.title?.let { put("title", it) }
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
