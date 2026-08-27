package com.owen282000.lifedashboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

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
}
