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
}
