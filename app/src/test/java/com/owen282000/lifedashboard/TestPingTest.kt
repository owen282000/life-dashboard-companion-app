package com.owen282000.lifedashboard

import io.github.optimumcode.json.schema.JsonSchema
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.Instant

/** The test ping: what it carries, and that the published schema accepts it. */
class TestPingTest {

    private val now = Instant.parse("2026-10-03T12:00:00Z")

    @Test
    fun thePingCarriesWhatEveryPayloadCarries() {
        val ping = Json.parseToJsonElement(TestPing.payload(LogType.HEALTH_CONNECT, "1.24.0", now)).jsonObject
        assertEquals(true, ping.getValue("test").jsonPrimitive.boolean)
        assertEquals(TestPing.MESSAGE, ping.getValue("message").jsonPrimitive.content)
        assertEquals("2026-10-03T12:00:00Z", ping.getValue("timestamp").jsonPrimitive.content)
        assertEquals("1.24.0", ping.getValue("app_version").jsonPrimitive.content)
        assertEquals("health_connect", ping.getValue("source").jsonPrimitive.content)
        assertEquals(setOf("test", "message", "timestamp", "app_version", "source"), ping.keys)
    }

    @Test
    fun thePingNamesTheSectionItTests() {
        assertEquals("health_connect", TestPing.source(LogType.HEALTH_CONNECT))
        assertEquals("screen_time", TestPing.source(LogType.SCREEN_TIME))
    }

    @Test
    fun thePublishedSchemaAcceptsThePing() {
        val schema = JsonSchema.fromDefinition(File("../docs/webhook-schema.json").readText())
        for (logType in LogType.entries) {
            val errors = mutableListOf<String>()
            schema.validate(Json.parseToJsonElement(TestPing.payload(logType, "1.24.0", now))) { errors += "${it.objectPath}: ${it.message}" }
            assertTrue("$logType: $errors", errors.isEmpty())
        }
    }

    @Test
    fun theSchemaRefusesAPingWithoutAppVersion() {
        // What the ping sent before: the schema requires app_version, so it was refused.
        val schema = JsonSchema.fromDefinition(File("../docs/webhook-schema.json").readText())
        val old = """{"test":true,"message":"${TestPing.MESSAGE}","timestamp":"2026-10-03T12:00:00Z","source":"health_connect"}"""
        val errors = mutableListOf<String>()
        schema.validate(Json.parseToJsonElement(old)) { errors += it.message }
        assertTrue(errors.isNotEmpty())
    }
}
