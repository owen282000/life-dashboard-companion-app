package com.owen282000.lifedashboard

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

/**
 * The Screen Time payload as it reaches a receiver, taken from the production builder. The
 * week is re-sent on every sync and a failed one waits in the outbox, so `sequence` is what
 * tells a receiver which of two weeks is the newer one.
 */
class ScreenTimePayloadTest {

    private val week = listOf(
        ScreenTimeData(
            date = LocalDate.parse("2026-09-27"),
            totalScreenTimeMs = 180 * 60_000L,
            apps = listOf(AppUsageData("com.example.app", "Example", 45 * 60_000L, Instant.parse("2026-09-27T11:30:00Z")))
        )
    )

    private fun payload(sequence: Long?) = Json.parseToJsonElement(
        ScreenTimeSyncManager.buildJsonPayload(week, "1.20.0", "Google Pixel 8", sequence)
    ).jsonObject

    @Test
    fun `a sent payload carries its sequence at the top level`() {
        val json = payload(sequence = 42)

        assertEquals(42L, json.getValue("sequence").jsonPrimitive.long)
        assertEquals("screen_time", json.getValue("source").jsonPrimitive.content)
    }

    @Test
    fun `the preview takes no number, so it carries no sequence`() {
        // The field is optional in the schema: absent means unknown, and a preview must not
        // pretend to be a payload the receiver could order against.
        assertFalse(payload(sequence = null).containsKey("sequence"))
    }

    @Test
    fun `the sequence leaves the rest of the payload as it was`() {
        val json = payload(sequence = 7)

        assertEquals("Google Pixel 8", json.getValue("device").jsonPrimitive.content)
        val day = json.getValue("screen_time").jsonArray.single().jsonObject
        assertEquals("2026-09-27", day.getValue("date").jsonPrimitive.content)
        assertEquals(180L, day.getValue("total_screen_time_minutes").jsonPrimitive.long)
        val app = day.getValue("apps").jsonArray.single().jsonObject
        assertEquals(45L, app.getValue("minutes").jsonPrimitive.long)
    }
}
