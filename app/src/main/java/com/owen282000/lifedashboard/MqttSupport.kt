package com.owen282000.lifedashboard

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.time.Instant

/** A single Home Assistant sensor derived from the most recent synced record of a type. */
@Serializable
data class MqttSensor(
    val key: String,
    val name: String,
    val state: String,
    val unit: String? = null,
    val deviceClass: String? = null,
    val attributes: Map<String, String> = emptyMap(),
    /** Home Assistant state_class; null for text sensors, which must not declare one. */
    val stateClass: String? = "measurement"
)

/**
 * Pure MQTT/Home Assistant mapping logic, kept free of Android and network types so it can be
 * unit tested on the JVM. Point-in-time types (heart rate, weight, blood pressure) map to the
 * LATEST record; cumulative types (steps, distance, calories) map to TODAY'S TOTAL from the
 * deduplicated daily aggregate, which is what a Home Assistant dashboard wants to show.
 * Retained MQTT states mean Home Assistant always shows the last value even after restarts.
 */
object MqttSupport {

    const val DEFAULT_BASE_TOPIC = "lifedashboard"
    const val DEFAULT_DISCOVERY_PREFIX = "homeassistant"
    const val DEVICE_ID = "life_dashboard_companion"

    fun stateTopic(baseTopic: String, key: String) = "$baseTopic/$key/state"
    fun attributesTopic(baseTopic: String, key: String) = "$baseTopic/$key/attributes"
    fun discoveryTopic(discoveryPrefix: String, key: String) =
        "$discoveryPrefix/sensor/${DEVICE_ID}_$key/config"

    /**
     * Maps the latest record of each sensor-like data type to an MQTT sensor. Event-like types
     * (exercise, nutrition, mindfulness, cycle tracking) are intentionally not mapped; they do
     * not fit Home Assistant's single-value sensor model and remain webhook-only.
     */
    fun sensorsFrom(data: HealthData, dailyTotals: List<DailyTotals> = emptyList()): List<MqttSensor> {
        val sensors = mutableListOf<MqttSensor>()

        // Today's totals for the cumulative types. A single steps record is a few dozen steps
        // and meaningless on a dashboard; the day total is the number people look for.
        dailyTotals.maxByOrNull { it.date }?.let { today ->
            val dayAttrs = mapOf("date" to today.date)
            today.steps?.let {
                sensors += MqttSensor("steps_today", "Steps Today", it.toString(), "steps", null, dayAttrs, "total_increasing")
            }
            today.distanceMeters?.let {
                sensors += MqttSensor("distance_today", "Distance Today", String.format(java.util.Locale.ROOT, "%.0f", it),
                    "m", "distance", dayAttrs, "total_increasing")
            }
            today.activeCalories?.let {
                sensors += MqttSensor("active_calories_today", "Active Calories Today", String.format(java.util.Locale.ROOT, "%.0f", it),
                    "kcal", null, dayAttrs, "total_increasing")
            }
            today.totalCalories?.let {
                sensors += MqttSensor("total_calories_today", "Total Calories Today", String.format(java.util.Locale.ROOT, "%.0f", it),
                    "kcal", null, dayAttrs, "total_increasing")
            }
        }

        fun <T> latest(records: List<T>, timeOf: (T) -> Instant): T? = records.maxByOrNull(timeOf)

        fun attrs(time: Instant, source: String?, uuid: String?): Map<String, String> = buildMap {
            put("measured_at", time.toString())
            source?.let { put("source", it) }
            uuid?.let { put("uuid", it) }
        }

        latest(data.heartRate) { it.time }?.let {
            sensors += MqttSensor("heart_rate", "Heart Rate", it.bpm.toString(),
                "bpm", null, attrs(it.time, it.source, it.uuid))
        }
        latest(data.restingHeartRate) { it.time }?.let {
            sensors += MqttSensor("resting_heart_rate", "Resting Heart Rate", it.bpm.toString(),
                "bpm", null, attrs(it.time, it.source, it.uuid))
        }
        latest(data.hrv) { it.time }?.let {
            sensors += MqttSensor("heart_rate_variability", "Heart Rate Variability",
                it.heartRateVariabilityMillis.toString(), "ms", null, attrs(it.time, it.source, it.uuid))
        }
        latest(data.sleep) { it.sessionEndTime }?.let {
            sensors += MqttSensor("sleep_duration", "Last Sleep Duration",
                (it.duration.toMinutes()).toString(), "min", "duration",
                attrs(it.sessionEndTime, it.source, it.uuid))
        }
        latest(data.weight) { it.time }?.let {
            sensors += MqttSensor("weight", "Weight", it.kilograms.toString(),
                "kg", "weight", attrs(it.time, it.source, it.uuid))
        }
        latest(data.bloodPressure) { it.time }?.let {
            sensors += MqttSensor("blood_pressure_systolic", "Blood Pressure Systolic",
                it.systolic.toString(), "mmHg", null, attrs(it.time, it.source, it.uuid))
            sensors += MqttSensor("blood_pressure_diastolic", "Blood Pressure Diastolic",
                it.diastolic.toString(), "mmHg", null, attrs(it.time, it.source, it.uuid))
        }
        latest(data.bloodGlucose) { it.time }?.let {
            sensors += MqttSensor("blood_glucose", "Blood Glucose", it.mmolPerLiter.toString(),
                "mmol/L", null, attrs(it.time, it.source, it.uuid))
        }
        latest(data.oxygenSaturation) { it.time }?.let {
            sensors += MqttSensor("oxygen_saturation", "Oxygen Saturation", it.percentage.toString(),
                "%", null, attrs(it.time, it.source, it.uuid))
        }
        latest(data.bodyTemperature) { it.time }?.let {
            sensors += MqttSensor("body_temperature", "Body Temperature", it.celsius.toString(),
                "°C", "temperature", attrs(it.time, it.source, it.uuid))
        }
        latest(data.skinTemperature) { it.time }?.let {
            sensors += MqttSensor("skin_temperature_delta", "Skin Temperature Delta",
                it.deltaCelsius.toString(), "°C", "temperature", attrs(it.time, it.source, it.uuid))
        }
        latest(data.basalBodyTemperature) { it.time }?.let {
            sensors += MqttSensor("basal_body_temperature", "Basal Body Temperature",
                it.celsius.toString(), "°C", "temperature", attrs(it.time, it.source, it.uuid))
        }
        latest(data.respiratoryRate) { it.time }?.let {
            sensors += MqttSensor("respiratory_rate", "Respiratory Rate", it.rate.toString(),
                "breaths/min", null, attrs(it.time, it.source, it.uuid))
        }
        latest(data.hydration) { it.endTime }?.let {
            sensors += MqttSensor("hydration", "Hydration (latest record)", it.liters.toString(),
                "L", "volume", attrs(it.endTime, it.source, it.uuid))
        }
        latest(data.bodyFat) { it.time }?.let {
            sensors += MqttSensor("body_fat", "Body Fat", it.percentage.toString(),
                "%", null, attrs(it.time, it.source, it.uuid))
        }
        latest(data.leanBodyMass) { it.time }?.let {
            sensors += MqttSensor("lean_body_mass", "Lean Body Mass", it.kilograms.toString(),
                "kg", "weight", attrs(it.time, it.source, it.uuid))
        }
        latest(data.boneMass) { it.time }?.let {
            sensors += MqttSensor("bone_mass", "Bone Mass", it.kilograms.toString(),
                "kg", "weight", attrs(it.time, it.source, it.uuid))
        }
        latest(data.bodyWaterMass) { it.time }?.let {
            sensors += MqttSensor("body_water_mass", "Body Water Mass", it.kilograms.toString(),
                "kg", "weight", attrs(it.time, it.source, it.uuid))
        }
        latest(data.basalMetabolicRate) { it.time }?.let {
            sensors += MqttSensor("basal_metabolic_rate", "Basal Metabolic Rate",
                it.kilocaloriesPerDay.toString(), "kcal/d", null, attrs(it.time, it.source, it.uuid))
        }
        latest(data.vo2Max) { it.time }?.let {
            sensors += MqttSensor("vo2_max", "VO2 Max", it.vo2MillilitersPerMinuteKilogram.toString(),
                "mL/min/kg", null, attrs(it.time, it.source, it.uuid))
        }
        latest(data.height) { it.time }?.let {
            sensors += MqttSensor("height", "Height", it.meters.toString(),
                "m", "distance", attrs(it.time, it.source, it.uuid))
        }
        return sensors
    }

    /**
     * Screen time sensors (issue #52): today's and yesterday's total minutes plus today's most
     * used app. "Today" is the newest day in the list, which follows the configured day
     * boundary. Per-app detail travels as attributes; the top app is a text sensor.
     */
    fun sensorsFromScreenTime(days: List<ScreenTimeData>): List<MqttSensor> {
        val today = days.maxByOrNull { it.date } ?: return emptyList()
        val yesterday = days.firstOrNull { it.date == today.date.minusDays(1) }

        fun dayAttrs(day: ScreenTimeData): Map<String, String> = buildMap {
            put("date", day.date.toString())
            put("app_count", day.apps.size.toString())
            put("top_apps", day.apps.sortedByDescending { it.totalTimeMs }.take(5)
                .joinToString(", ") { "${it.appName} (${it.totalTimeMs / 60000} min)" })
        }

        val sensors = mutableListOf<MqttSensor>()
        sensors += MqttSensor("screen_time_today", "Screen Time Today",
            (today.totalScreenTimeMs / 60000).toString(), "min", "duration", dayAttrs(today))
        yesterday?.let {
            sensors += MqttSensor("screen_time_yesterday", "Screen Time Yesterday",
                (it.totalScreenTimeMs / 60000).toString(), "min", "duration", dayAttrs(it))
        }
        today.apps.maxByOrNull { it.totalTimeMs }?.let {
            sensors += MqttSensor("screen_time_top_app", "Screen Time Top App Today", it.appName,
                null, null, mapOf(
                    "package" to it.packageName,
                    "minutes" to (it.totalTimeMs / 60000).toString(),
                    "date" to today.date.toString()
                ), stateClass = null)
        }
        return sensors
    }

    /**
     * The set to publish: everything published before, with fresh values on top. A sync only
     * carries the types that had new records, but the broker should hold every sensor the app
     * knows, so a new broker or a fresh Home Assistant sees the whole device at once.
     */
    fun mergeSensors(cached: List<MqttSensor>, fresh: List<MqttSensor>): List<MqttSensor> =
        (cached.associateBy { it.key } + fresh.associateBy { it.key }).values
            .filterNot { it.key in RETIRED_SENSOR_KEYS }

    /**
     * Sensor keys that older versions published and that no longer exist. Their retained
     * discovery configs would keep a stale entity alive in Home Assistant forever, so every
     * publish clears them (an empty retained payload on the config topic removes the entity).
     */
    val RETIRED_SENSOR_KEYS: Set<String> = setOf("steps", "distance", "active_calories", "total_calories")

    /** Home Assistant MQTT Discovery config payload for a sensor (published retained). */
    fun discoveryConfigJson(sensor: MqttSensor, baseTopic: String, appVersion: String): String {
        return buildJsonObject {
            put("name", sensor.name)
            put("unique_id", "${DEVICE_ID}_${sensor.key}")
            put("state_topic", stateTopic(baseTopic, sensor.key))
            put("json_attributes_topic", attributesTopic(baseTopic, sensor.key))
            sensor.unit?.let { put("unit_of_measurement", it) }
            sensor.deviceClass?.let { put("device_class", it) }
            sensor.stateClass?.let { put("state_class", it) }
            putJsonObject("device") {
                putJsonArray("identifiers") { add(kotlinx.serialization.json.JsonPrimitive(DEVICE_ID)) }
                put("name", "Life Dashboard Companion")
                put("manufacturer", "owen282000")
                put("model", "Android app")
                put("sw_version", appVersion)
            }
        }.toString()
    }

    fun attributesJson(sensor: MqttSensor): String {
        return buildJsonObject {
            sensor.attributes.forEach { (k, v) -> put(k, v) }
        }.toString()
    }
}
