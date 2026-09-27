package com.owen282000.lifedashboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

class MqttSupportTest {

    private val t1 = Instant.parse("2026-01-01T08:00:00Z")
    private val t2 = Instant.parse("2026-01-01T09:00:00Z")

    private fun emptyHealthData() = HealthData(
        steps = emptyList(), sleep = emptyList(), heartRate = emptyList(), distance = emptyList(),
        activeCalories = emptyList(), totalCalories = emptyList(), weight = emptyList(),
        height = emptyList(), bloodPressure = emptyList(), bloodGlucose = emptyList(),
        oxygenSaturation = emptyList(), bodyTemperature = emptyList(), respiratoryRate = emptyList(),
        restingHeartRate = emptyList(), exercise = emptyList(), hydration = emptyList(),
        nutrition = emptyList(), mindfulness = emptyList(), bodyFat = emptyList(),
        leanBodyMass = emptyList(), boneMass = emptyList(), bodyWaterMass = emptyList(),
        hrv = emptyList(), menstruationPeriod = emptyList(), menstruationFlow = emptyList(),
        basalMetabolicRate = emptyList(), vo2Max = emptyList(), skinTemperature = emptyList(),
        basalBodyTemperature = emptyList(), intermenstrualBleeding = emptyList(),
        ovulationTest = emptyList(), cervicalMucus = emptyList(), sexualActivity = emptyList()
    )

    @Test
    fun emptyDataYieldsNoSensors() {
        assertEquals(emptyList<MqttSensor>(), MqttSupport.sensorsFrom(emptyHealthData()))
    }

    @Test
    fun latestRecordWinsPerType() {
        val data = emptyHealthData().copy(
            heartRate = listOf(
                HeartRateData(70, t1, "com.app.a", "u1"),
                HeartRateData(85, t2, "com.app.b", "u2")
            )
        )
        val sensor = MqttSupport.sensorsFrom(data).single()
        assertEquals("heart_rate", sensor.key)
        assertEquals("85", sensor.state)
        assertEquals("com.app.b", sensor.attributes["source"])
        assertEquals(t2.toString(), sensor.attributes["measured_at"])
    }

    @Test
    fun bloodPressureYieldsTwoSensors() {
        val data = emptyHealthData().copy(
            bloodPressure = listOf(BloodPressureData(121.0, 79.0, t1, "com.app.a", "u1"))
        )
        val keys = MqttSupport.sensorsFrom(data).map { it.key }.sorted()
        assertEquals(listOf("blood_pressure_diastolic", "blood_pressure_systolic"), keys)
    }

    @Test
    fun sleepSensorReportsDurationMinutes() {
        val data = emptyHealthData().copy(
            sleep = listOf(SleepData(t2, Duration.ofHours(7).plusMinutes(30), emptyList(), "com.app.a", "u1"))
        )
        val sensor = MqttSupport.sensorsFrom(data).single()
        assertEquals("sleep_duration", sensor.key)
        assertEquals("450", sensor.state)
    }

    @Test
    fun topicsFollowTheExpectedShape() {
        assertEquals("lifedashboard/heart_rate/state", MqttSupport.stateTopic("lifedashboard", "heart_rate"))
        assertEquals("lifedashboard/heart_rate/attributes", MqttSupport.attributesTopic("lifedashboard", "heart_rate"))
        assertEquals(
            "homeassistant/sensor/life_dashboard_companion_heart_rate/config",
            MqttSupport.discoveryTopic("homeassistant", "heart_rate")
        )
    }

    @Test
    fun discoveryConfigContainsRequiredHomeAssistantFields() {
        val sensor = MqttSensor("weight", "Weight", "80.5", "kg", "weight", mapOf("measured_at" to t1.toString()))
        val json = MqttSupport.discoveryConfigJson(sensor, "lifedashboard", "1.8.0")
        for (expected in listOf(
            "\"unique_id\":\"life_dashboard_companion_weight\"",
            "\"state_topic\":\"lifedashboard/weight/state\"",
            "\"json_attributes_topic\":\"lifedashboard/weight/attributes\"",
            "\"unit_of_measurement\":\"kg\"",
            "\"device_class\":\"weight\"",
            "\"sw_version\":\"1.8.0\"",
            "\"identifiers\":[\"life_dashboard_companion\"]"
        )) {
            assertTrue("missing $expected in $json", expected in json)
        }
    }

    // Screen time sensors (issue #52)

    private fun day(date: String, vararg apps: Pair<String, Long>) = ScreenTimeData(
        date = LocalDate.parse(date),
        totalScreenTimeMs = apps.sumOf { it.second } * 60_000,
        apps = apps.map { (name, minutes) -> AppUsageData("com.example.${name.lowercase()}", name, minutes * 60_000, t1) }
    )

    @Test
    fun screenTimeYieldsTodayYesterdayAndTopApp() {
        val sensors = MqttSupport.sensorsFromScreenTime(listOf(
            day("2026-09-12", "Instagram" to 45, "Chrome" to 30),
            day("2026-09-13", "WhatsApp" to 20, "YouTube" to 61)
        ))
        val byKey = sensors.associateBy { it.key }
        assertEquals(listOf("screen_time_today", "screen_time_yesterday", "screen_time_top_app"), sensors.map { it.key })
        assertEquals("81", byKey.getValue("screen_time_today").state)
        assertEquals("2026-09-13", byKey.getValue("screen_time_today").attributes["date"])
        assertEquals("YouTube (61 min), WhatsApp (20 min)", byKey.getValue("screen_time_today").attributes["top_apps"])
        assertEquals("75", byKey.getValue("screen_time_yesterday").state)
        assertEquals("YouTube", byKey.getValue("screen_time_top_app").state)
        assertEquals("com.example.youtube", byKey.getValue("screen_time_top_app").attributes["package"])
        assertEquals("61", byKey.getValue("screen_time_top_app").attributes["minutes"])
    }

    @Test
    fun yesterdaySensorOnlyForTheDayBeforeToday() {
        val sensors = MqttSupport.sensorsFromScreenTime(listOf(
            day("2026-09-10", "Chrome" to 30),
            day("2026-09-13", "Chrome" to 10)
        ))
        assertFalse(sensors.any { it.key == "screen_time_yesterday" })
    }

    @Test
    fun topAppIsATextSensorWithoutStateClass() {
        val top = MqttSupport.sensorsFromScreenTime(listOf(day("2026-09-13", "Chrome" to 10)))
            .single { it.key == "screen_time_top_app" }
        assertNull(top.stateClass)
        assertNull(top.unit)
        val config = MqttSupport.discoveryConfigJson(top, "lifedashboard", "1.0")
        assertFalse("text sensors must not declare state_class: $config", "state_class" in config)
        val numeric = MqttSupport.sensorsFromScreenTime(listOf(day("2026-09-13", "Chrome" to 10)))
            .single { it.key == "screen_time_today" }
        assertTrue("state_class" in MqttSupport.discoveryConfigJson(numeric, "lifedashboard", "1.0"))
    }

    @Test
    fun noScreenTimeDaysYieldsNoSensors() {
        assertEquals(emptyList<MqttSensor>(), MqttSupport.sensorsFromScreenTime(emptyList()))
    }

    @Test
    fun `cumulative types publish today's totals, not the latest record`() {
        val totals = listOf(
            DailyTotals(date = "2026-09-13", steps = 8000, distanceMeters = 6000.0),
            DailyTotals(date = "2026-09-14", steps = 1234, distanceMeters = 950.4, activeCalories = 210.6, totalCalories = 1800.2)
        )
        val sensors = MqttSupport.sensorsFrom(emptyHealthData(), totals)
        val byKey = sensors.associateBy { it.key }
        assertEquals("1234", byKey.getValue("steps_today").state)
        assertEquals("950", byKey.getValue("distance_today").state)
        assertEquals("211", byKey.getValue("active_calories_today").state)
        assertEquals("1800", byKey.getValue("total_calories_today").state)
        assertEquals("total_increasing", byKey.getValue("steps_today").stateClass)
        assertEquals("2026-09-14", byKey.getValue("steps_today").attributes["date"])
        assertNull(byKey["steps"])
        assertNull(byKey["distance"])
    }

    @Test
    fun `merging keeps cached sensors and lets fresh values win`() {
        val cached = listOf(
            MqttSensor("weight", "Weight", "80.0", "kg"),
            MqttSensor("steps_today", "Steps Today", "100", "steps")
        )
        val fresh = listOf(MqttSensor("steps_today", "Steps Today", "1234", "steps"))
        val merged = MqttSupport.mergeSensors(cached, fresh).associateBy { it.key }
        assertEquals(2, merged.size)
        assertEquals("80.0", merged.getValue("weight").state)
        assertEquals("1234", merged.getValue("steps_today").state)
    }

    @Test
    fun `sensors round-trip through JSON for the publish cache`() {
        val sensors = listOf(MqttSensor("weight", "Weight", "80.0", "kg", "weight", mapOf("source" to "app")))
        val json = kotlinx.serialization.json.Json.encodeToString(kotlinx.serialization.builtins.ListSerializer(MqttSensor.serializer()), sensors)
        val back = kotlinx.serialization.json.Json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(MqttSensor.serializer()), json)
        assertEquals(sensors, back)
    }

    @Test
    fun `merging drops sensors that older versions published under retired keys`() {
        val cached = listOf(MqttSensor("steps", "Steps (latest record)", "7", "steps"), MqttSensor("weight", "Weight", "80.0", "kg"))
        val merged = MqttSupport.mergeSensors(cached, emptyList()).map { it.key }
        assertEquals(listOf("weight"), merged)
    }

    @Test
    fun `discovery config tells Home Assistant to show the decimals the state carries`() {
        assertEquals(0, MqttSupport.displayPrecision("5921"))
        assertEquals(1, MqttSupport.displayPrecision("78.2"))
        assertEquals(2, MqttSupport.displayPrecision("5.55"))
        assertEquals(null, MqttSupport.displayPrecision("Life Dashboard"))

        val distance = MqttSupport.discoveryConfigJson(
            MqttSensor("distance_today", "Distance Today", "5921", "m", "distance", emptyMap(), "total_increasing"),
            "lifedashboard", "1.0"
        )
        assertTrue(distance.contains("\"suggested_display_precision\":0"))
        val topApp = MqttSupport.discoveryConfigJson(
            MqttSensor("screen_time_top_app", "Screen Time Top App Today", "Life Dashboard", null, null, emptyMap(), null),
            "lifedashboard", "1.0"
        )
        assertFalse(topApp.contains("suggested_display_precision"))
    }

    // Phone name (issue #62, phase 1): two phones on one broker

    @Test
    fun `without a phone name every topic and id is exactly what it always was`() {
        assertNull(MqttSupport.phoneSlug(null))
        assertNull(MqttSupport.phoneSlug("   "))
        assertEquals("lifedashboard/weight/state", MqttSupport.stateTopic("lifedashboard", "weight", null))
        assertEquals("lifedashboard/weight/attributes", MqttSupport.attributesTopic("lifedashboard", "weight", null))
        assertEquals("homeassistant/sensor/life_dashboard_companion_weight/config", MqttSupport.discoveryTopic("homeassistant", "weight", null))
        assertEquals("life_dashboard_companion", MqttSupport.deviceId(null))
        assertEquals("Life Dashboard Companion", MqttSupport.deviceName(null))
        val json = MqttSupport.discoveryConfigJson(MqttSensor("weight", "Weight", "80.5", "kg"), "lifedashboard", "1.20.0", phoneName = null)
        assertTrue(json.contains("\"unique_id\":\"life_dashboard_companion_weight\""))
        assertTrue(json.contains("\"identifiers\":[\"life_dashboard_companion\"]"))
        assertTrue(json.contains("\"name\":\"Life Dashboard Companion\""))
    }

    @Test
    fun `a phone name becomes a slug of lower case letters, digits and underscores`() {
        assertEquals("pixel_8", MqttSupport.phoneSlug("Pixel 8"))
        assertEquals("zoe_s_phone", MqttSupport.phoneSlug("  Zoë's phone  "))
        assertEquals("owen_phone", MqttSupport.phoneSlug("Owen--Phone!"))
        assertEquals("a_b", MqttSupport.phoneSlug("a_b"))
        assertNull("a name with nothing usable in it is no name", MqttSupport.phoneSlug("!!!"))
    }

    @Test
    fun `with a phone name the topics, the ids and the device carry it`() {
        val slug = MqttSupport.phoneSlug("Pixel 8")
        assertEquals("lifedashboard/pixel_8/weight/state", MqttSupport.stateTopic("lifedashboard", "weight", slug))
        assertEquals("lifedashboard/pixel_8/weight/attributes", MqttSupport.attributesTopic("lifedashboard", "weight", slug))
        assertEquals("homeassistant/sensor/life_dashboard_companion_pixel_8_weight/config", MqttSupport.discoveryTopic("homeassistant", "weight", slug))
        assertEquals("life_dashboard_companion_pixel_8", MqttSupport.deviceId(slug))
        assertEquals("Life Dashboard Companion (Pixel 8)", MqttSupport.deviceName("Pixel 8"))

        val json = MqttSupport.discoveryConfigJson(MqttSensor("weight", "Weight", "80.5", "kg"), "lifedashboard", "1.20.0", phoneName = "Pixel 8")
        for (expected in listOf(
            "\"unique_id\":\"life_dashboard_companion_pixel_8_weight\"",
            "\"state_topic\":\"lifedashboard/pixel_8/weight/state\"",
            "\"json_attributes_topic\":\"lifedashboard/pixel_8/weight/attributes\"",
            "\"identifiers\":[\"life_dashboard_companion_pixel_8\"]",
            "\"name\":\"Life Dashboard Companion (Pixel 8)\""
        )) {
            assertTrue("missing $expected in $json", expected in json)
        }
    }

    @Test
    fun `renaming the phone clears every topic of the old device first, and only then`() {
        val keys = listOf("weight", "steps_today")
        assertEquals(emptyList<String>(), MqttSupport.topicsToClearOnRename("lifedashboard", "homeassistant", keys, null, null))
        assertEquals(emptyList<String>(), MqttSupport.topicsToClearOnRename("lifedashboard", "homeassistant", keys, "pixel_8", "pixel_8"))

        // Nameless for months, then named: the nameless device goes.
        assertEquals(
            listOf(
                "lifedashboard/weight/state",
                "lifedashboard/weight/attributes",
                "homeassistant/sensor/life_dashboard_companion_weight/config",
                "lifedashboard/steps_today/state",
                "lifedashboard/steps_today/attributes",
                "homeassistant/sensor/life_dashboard_companion_steps_today/config"
            ),
            MqttSupport.topicsToClearOnRename("lifedashboard", "homeassistant", keys, previousSlug = null, currentSlug = "pixel_8")
        )
        // Renamed again: the previous name's device goes, the nameless topics are left alone.
        assertEquals(
            listOf(
                "lifedashboard/pixel_8/weight/state",
                "lifedashboard/pixel_8/weight/attributes",
                "homeassistant/sensor/life_dashboard_companion_pixel_8_weight/config"
            ),
            MqttSupport.topicsToClearOnRename("lifedashboard", "homeassistant", listOf("weight"), previousSlug = "pixel_8", currentSlug = "zoe")
        )
        // Name removed: back to nameless, the named device goes.
        assertEquals(
            listOf("lifedashboard/zoe/weight/state", "lifedashboard/zoe/weight/attributes", "homeassistant/sensor/life_dashboard_companion_zoe_weight/config"),
            MqttSupport.topicsToClearOnRename("lifedashboard", "homeassistant", listOf("weight"), previousSlug = "zoe", currentSlug = null)
        )
    }

    @Test
    fun `numeric states are rounded to sensible decimals`() {
        assertEquals("78.2", MqttSupport.num(78.2006048685296))
        assertEquals("5.55", MqttSupport.num(5.5499, 2))
        assertEquals("1650", MqttSupport.num(1650.4, 0))
        assertEquals("36.6", MqttSupport.num(36.6))
    }

    @Test
    fun aBrokerOnTheHomeNetworkOrAVpnIsPrivate() {
        listOf(
            "192.168.1.10", "10.0.0.2", "172.16.0.1", "172.31.255.1", "127.0.0.1", "169.254.1.1",
            "100.101.102.103", "homeassistant", "localhost", "ha.local", "broker.lan", "mqtt.home.arpa",
            "pi.ts.net", "::1", "[fd00::1]", "fe80::1", " 192.168.1.10 ", "HA.LOCAL."
        ).forEach { assertTrue(it, MqttSupport.isPrivateHost(it)) }
    }

    @Test
    fun aBrokerAcrossTheInternetIsNot() {
        listOf(
            "broker.hivemq.com", "mqtt.example.com", "8.8.8.8", "172.32.0.1", "100.128.0.1",
            "192.169.1.1", "2001:db8::1", "local.example.com"
        ).forEach { assertFalse(it, MqttSupport.isPrivateHost(it)) }
    }
}
