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
    const val DEVICE_NAME = "Life Dashboard Companion"

    /** Numeric states with a sensible number of decimals; raw doubles like 78.2006048685296 help nobody. */
    fun num(value: Double, decimals: Int = 1): String = String.format(java.util.Locale.ROOT, "%.${decimals}f", value)

    /**
     * The phone name as it appears in topics and ids: lower case letters, digits and
     * underscores, nothing else. Accents are stripped rather than replaced, so "Zoë" is "zoe".
     * Null for a blank name, which is the signal that this phone has no name and everything
     * stays exactly as it was before names existed.
     */
    fun phoneSlug(name: String?): String? {
        if (name.isNullOrBlank()) return null
        val plain = java.text.Normalizer.normalize(name.trim(), java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .lowercase(java.util.Locale.ROOT)
        return plain.replace(Regex("[^a-z0-9_]+"), "_").trim('_').ifEmpty { null }
    }

    /**
     * Whether a broker host is on the home network or a VPN, going by its name or address
     * alone (no DNS lookup): private and loopback IPv4 ranges, Tailscale's 100.64.0.0/10,
     * IPv6 loopback, unique local and link-local, a name without a dot, and the usual LAN
     * suffixes. Anything else may be across the internet, where plain MQTT on 1883 carries
     * the password and the retained health values unencrypted, so the settings warn about it.
     */
    fun isPrivateHost(host: String): Boolean {
        val name = host.trim().lowercase(java.util.Locale.ROOT).removeSurrounding("[", "]").removeSuffix(".")
        if (name.isEmpty() || name == "localhost") return true
        if (':' in name) {
            return name == "::1" || name.startsWith("fc") || name.startsWith("fd") ||
                listOf("fe8", "fe9", "fea", "feb").any { name.startsWith(it) }
        }
        val octets = name.split('.').map { it.toIntOrNull() }
        if (octets.size == 4 && octets.all { it != null && it in 0..255 }) {
            val (a, b) = octets.map { it!! }
            return a == 10 || a == 127 || (a == 172 && b in 16..31) || (a == 192 && b == 168) ||
                (a == 169 && b == 254) || (a == 100 && b in 64..127)
        }
        if ('.' !in name) return true
        return PRIVATE_SUFFIXES.any { name.endsWith(it) }
    }

    private val PRIVATE_SUFFIXES = listOf(".local", ".lan", ".home", ".internal", ".home.arpa", ".localdomain", ".ts.net")

    /** The Home Assistant device id: the fixed one, or with the phone's slug behind it. */
    fun deviceId(slug: String?): String = if (slug == null) DEVICE_ID else "${DEVICE_ID}_$slug"

    /** The device name Home Assistant shows: with the phone's name in brackets when it has one. */
    fun deviceName(phoneName: String?): String {
        val name = phoneName?.trim().orEmpty()
        return if (name.isEmpty()) DEVICE_NAME else "$DEVICE_NAME ($name)"
    }

    /**
     * Topics carry the phone's slug between the base topic and the sensor key, so two phones on
     * one broker never publish over each other. Without a slug they are the topics every
     * receiver has been reading since 1.8.
     */
    fun stateTopic(baseTopic: String, key: String, slug: String? = null) =
        if (slug == null) "$baseTopic/$key/state" else "$baseTopic/$slug/$key/state"
    fun attributesTopic(baseTopic: String, key: String, slug: String? = null) =
        if (slug == null) "$baseTopic/$key/attributes" else "$baseTopic/$slug/$key/attributes"
    fun discoveryTopic(discoveryPrefix: String, key: String, slug: String? = null) =
        "$discoveryPrefix/sensor/${deviceId(slug)}_$key/config"

    /**
     * Every retained topic the sensors with [keys] occupy under [slug]: state and attributes
     * first, the discovery config last, which is the order that clears an entity cleanly
     * (an empty attributes payload on a still-living entity makes Home Assistant log
     * "Erroneous JSON"; the config clear removes it). Used to retire old sensor keys and, when
     * the phone's name changes, to take the old device off the broker.
     */
    fun topicsFor(baseTopic: String, discoveryPrefix: String, keys: Collection<String>, slug: String?): List<String> =
        keys.flatMap { key ->
            listOf(stateTopic(baseTopic, key, slug), attributesTopic(baseTopic, key, slug), discoveryTopic(discoveryPrefix, key, slug))
        }

    /**
     * What to clear before publishing under [currentSlug] when the phone last published under
     * [previousSlug]: nothing while the name is unchanged, otherwise every topic of every
     * known sensor under the old slug, so the old device does not live on with frozen values
     * next to the new one. A phone that never recorded a slug published nameless.
     */
    fun topicsToClearOnRename(
        baseTopic: String,
        discoveryPrefix: String,
        keys: Collection<String>,
        previousSlug: String?,
        currentSlug: String?
    ): List<String> = if (previousSlug == currentSlug) emptyList() else topicsFor(baseTopic, discoveryPrefix, keys, previousSlug)

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
                num(it.heartRateVariabilityMillis), "ms", null, attrs(it.time, it.source, it.uuid))
        }
        latest(data.sleep) { it.sessionEndTime }?.let {
            sensors += MqttSensor("sleep_duration", "Last Sleep Duration",
                (it.duration.toMinutes()).toString(), "min", "duration",
                attrs(it.sessionEndTime, it.source, it.uuid))
        }
        latest(data.weight) { it.time }?.let {
            sensors += MqttSensor("weight", "Weight", num(it.kilograms),
                "kg", "weight", attrs(it.time, it.source, it.uuid))
        }
        latest(data.bloodPressure) { it.time }?.let {
            sensors += MqttSensor("blood_pressure_systolic", "Blood Pressure Systolic",
                it.systolic.toString(), "mmHg", null, attrs(it.time, it.source, it.uuid))
            sensors += MqttSensor("blood_pressure_diastolic", "Blood Pressure Diastolic",
                it.diastolic.toString(), "mmHg", null, attrs(it.time, it.source, it.uuid))
        }
        latest(data.bloodGlucose) { it.time }?.let {
            sensors += MqttSensor("blood_glucose", "Blood Glucose", num(it.mmolPerLiter, 2),
                "mmol/L", null, attrs(it.time, it.source, it.uuid))
        }
        latest(data.oxygenSaturation) { it.time }?.let {
            sensors += MqttSensor("oxygen_saturation", "Oxygen Saturation", num(it.percentage),
                "%", null, attrs(it.time, it.source, it.uuid))
        }
        latest(data.bodyTemperature) { it.time }?.let {
            sensors += MqttSensor("body_temperature", "Body Temperature", num(it.celsius),
                "°C", "temperature", attrs(it.time, it.source, it.uuid))
        }
        latest(data.skinTemperature) { it.time }?.let {
            sensors += MqttSensor("skin_temperature_delta", "Skin Temperature Delta",
                num(it.deltaCelsius, 2), "°C", "temperature", attrs(it.time, it.source, it.uuid))
        }
        latest(data.basalBodyTemperature) { it.time }?.let {
            sensors += MqttSensor("basal_body_temperature", "Basal Body Temperature",
                num(it.celsius), "°C", "temperature", attrs(it.time, it.source, it.uuid))
        }
        latest(data.respiratoryRate) { it.time }?.let {
            sensors += MqttSensor("respiratory_rate", "Respiratory Rate", num(it.rate),
                "breaths/min", null, attrs(it.time, it.source, it.uuid))
        }
        latest(data.hydration) { it.endTime }?.let {
            sensors += MqttSensor("hydration", "Hydration (latest record)", num(it.liters, 2),
                "L", "volume", attrs(it.endTime, it.source, it.uuid))
        }
        latest(data.bodyFat) { it.time }?.let {
            sensors += MqttSensor("body_fat", "Body Fat", num(it.percentage),
                "%", null, attrs(it.time, it.source, it.uuid))
        }
        latest(data.leanBodyMass) { it.time }?.let {
            sensors += MqttSensor("lean_body_mass", "Lean Body Mass", num(it.kilograms),
                "kg", "weight", attrs(it.time, it.source, it.uuid))
        }
        latest(data.boneMass) { it.time }?.let {
            sensors += MqttSensor("bone_mass", "Bone Mass", num(it.kilograms),
                "kg", "weight", attrs(it.time, it.source, it.uuid))
        }
        latest(data.bodyWaterMass) { it.time }?.let {
            sensors += MqttSensor("body_water_mass", "Body Water Mass", num(it.kilograms),
                "kg", "weight", attrs(it.time, it.source, it.uuid))
        }
        latest(data.basalMetabolicRate) { it.time }?.let {
            sensors += MqttSensor("basal_metabolic_rate", "Basal Metabolic Rate",
                num(it.kilocaloriesPerDay, 0), "kcal/d", null, attrs(it.time, it.source, it.uuid))
        }
        latest(data.vo2Max) { it.time }?.let {
            sensors += MqttSensor("vo2_max", "VO2 Max", num(it.vo2MillilitersPerMinuteKilogram),
                "mL/min/kg", null, attrs(it.time, it.source, it.uuid))
        }
        latest(data.height) { it.time }?.let {
            sensors += MqttSensor("height", "Height", num(it.meters, 2),
                "m", "distance", attrs(it.time, it.source, it.uuid))
        }
        return sensors
    }

    /**
     * Screen time sensors (issue #52): today's and yesterday's total minutes plus today's most
     * used app. "Today" is the newest day in the list, which follows the configured day
     * boundary. Per-app detail travels as attributes; the top app is a text sensor.
     *
     * With an app filter on (issue #63) the sensors follow it: the minutes are those of the
     * apps that are sent, the top app is the most used one among them, and the real total of
     * every app is an attribute, `all_apps_minutes`, so a dashboard can show both.
     */
    fun sensorsFromScreenTime(days: List<ScreenTimeData>): List<MqttSensor> {
        val today = days.maxByOrNull { it.date } ?: return emptyList()
        val yesterday = days.firstOrNull { it.date == today.date.minusDays(1) }

        fun minutes(day: ScreenTimeData) = ((day.filteredScreenTimeMs ?: day.totalScreenTimeMs) / 60000).toString()
        fun dayAttrs(day: ScreenTimeData): Map<String, String> = buildMap {
            put("date", day.date.toString())
            put("app_count", day.apps.size.toString())
            put("top_apps", day.apps.sortedByDescending { it.totalTimeMs }.take(5)
                .joinToString(", ") { "${it.appName} (${it.totalTimeMs / 60000} min)" })
            if (day.filteredScreenTimeMs != null) put("all_apps_minutes", (day.totalScreenTimeMs / 60000).toString())
        }

        val sensors = mutableListOf<MqttSensor>()
        sensors += MqttSensor("screen_time_today", "Screen Time Today",
            minutes(today), "min", "duration", dayAttrs(today))
        yesterday?.let {
            sensors += MqttSensor("screen_time_yesterday", "Screen Time Yesterday",
                minutes(it), "min", "duration", dayAttrs(it))
        }
        val top = today.apps.maxByOrNull { it.totalTimeMs }
        if (top != null) {
            sensors += MqttSensor("screen_time_top_app", "Screen Time Top App Today", top.appName,
                null, null, mapOf(
                    "package" to top.packageName,
                    "minutes" to (top.totalTimeMs / 60000).toString(),
                    "date" to today.date.toString()
                ), stateClass = null)
        } else if (today.filteredScreenTimeMs != null) {
            // Every app of today was filtered out. The broker keeps the last top app otherwise
            // (mergeSensors), which may be one the user has just left out.
            sensors += MqttSensor("screen_time_top_app", "Screen Time Top App Today", NO_TOP_APP,
                null, null, mapOf("date" to today.date.toString()), stateClass = null)
        }
        return sensors
    }

    /**
     * What a sync reports for its MQTT publish: the failure the sync ends with, or null when the
     * sync's own outcome stands. With a webhook in the section the webhook decides, as it always
     * did, and a broker that is down shows only on the MQTT status line and in the logs. Without
     * one the broker is the only place the data went, so a failed publish is a failed sync: the
     * sync line, the dashboard and the failure streak say so, instead of a green sync that
     * delivered nothing. [publish] is null when nothing was published. [describe] turns the
     * publish's reason into the sync's, in the user's language (R.string.mqtt_sync_failed).
     */
    fun syncFailure(hasWebhooks: Boolean, publish: Result<Int>?, describe: (reason: String) -> String): Exception? {
        if (hasWebhooks) return null
        val error = publish?.exceptionOrNull() ?: return null
        return Exception(describe(error.message ?: error.javaClass.simpleName), error)
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

    /** The top app's state when the app filter left no app of today. */
    const val NO_TOP_APP = "none"

    /**
     * Home Assistant MQTT Discovery config payload for a sensor (published retained). With a
     * [phoneName] the unique ids, the topics and the device all carry it, so a second phone
     * becomes a second device instead of overwriting the first.
     */
    fun discoveryConfigJson(sensor: MqttSensor, baseTopic: String, appVersion: String, phoneName: String? = null): String {
        val slug = phoneSlug(phoneName)
        return buildJsonObject {
            put("name", sensor.name)
            put("unique_id", "${deviceId(slug)}_${sensor.key}")
            put("state_topic", stateTopic(baseTopic, sensor.key, slug))
            put("json_attributes_topic", attributesTopic(baseTopic, sensor.key, slug))
            sensor.unit?.let { put("unit_of_measurement", it) }
            sensor.deviceClass?.let { put("device_class", it) }
            sensor.stateClass?.let { put("state_class", it) }
            // Home Assistant defaults numeric sensors with a convertible device class (distance,
            // weight, duration) to two decimals, which turns 5921 m into "5,921.00 m". The state
            // already carries the decimals we want, so tell HA to show exactly those.
            displayPrecision(sensor.state)?.let { put("suggested_display_precision", it) }
            putJsonObject("device") {
                putJsonArray("identifiers") { add(kotlinx.serialization.json.JsonPrimitive(deviceId(slug))) }
                put("name", deviceName(phoneName))
                put("manufacturer", "owen282000")
                put("model", "Android app")
                put("sw_version", appVersion)
            }
        }.toString()
    }

    /** Decimals in a numeric state ("78.2" gives 1, "8002" gives 0), null for text states. */
    fun displayPrecision(state: String): Int? {
        if (state.toDoubleOrNull() == null) return null
        return state.substringAfter('.', "").length
    }

    fun attributesJson(sensor: MqttSensor): String {
        return buildJsonObject {
            sensor.attributes.forEach { (k, v) -> put(k, v) }
        }.toString()
    }
}
