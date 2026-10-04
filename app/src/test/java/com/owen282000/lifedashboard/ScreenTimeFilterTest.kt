package com.owen282000.lifedashboard

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

/**
 * Issue #63: which apps Screen Time sends. An app the user leaves out never leaves the phone,
 * the day's real total stays, and the sum of what is sent goes out as its own field.
 */
class ScreenTimeFilterTest {

    private fun app(pkg: String, minutes: Long) = AppUsageData(pkg, pkg.substringAfterLast('.'), minutes * 60_000L, Instant.parse("2026-10-04T10:00:00Z"))

    private val today = ScreenTimeData(
        date = LocalDate.parse("2026-10-04"),
        totalScreenTimeMs = 200 * 60_000L,
        apps = listOf(app("com.google.android.youtube", 90), app("com.whatsapp", 60), app("com.android.chrome", 50))
    )
    private val yesterday = today.copy(date = LocalDate.parse("2026-10-03"), totalScreenTimeMs = 30 * 60_000L, apps = listOf(app("com.android.chrome", 30)))
    private val week = listOf(today, yesterday)

    private val noYoutube = ScreenTimeAppFilter(AppFilterMode.BLOCKLIST, setOf("com.google.android.youtube"))
    private val onlySocial = ScreenTimeAppFilter(AppFilterMode.ALLOWLIST, setOf("com.whatsapp", "com.instagram.android"))

    @Test
    fun `without a filter the days go out untouched`() {
        assertSame(week, ScreenTimeAppFilter.ALL.apply(week))
        assertNull(ScreenTimeAppFilter.ALL.apply(week).first().filteredScreenTimeMs)
    }

    @Test
    fun `a list kept with the mode at all filters nothing`() {
        val kept = ScreenTimeAppFilter(AppFilterMode.ALL, setOf("com.whatsapp"))
        assertFalse(kept.active)
        assertEquals(week, kept.apply(week))
    }

    @Test
    fun `a blocklist leaves its apps out and keeps the real total`() {
        val day = noYoutube.apply(week).first()
        assertEquals(listOf("com.whatsapp", "com.android.chrome"), day.apps.map { it.packageName })
        assertEquals(200 * 60_000L, day.totalScreenTimeMs)
        assertEquals(110 * 60_000L, day.filteredScreenTimeMs)
    }

    @Test
    fun `an allowlist sends only its apps`() {
        val days = onlySocial.apply(week)
        assertEquals(listOf("com.whatsapp"), days[0].apps.map { it.packageName })
        assertEquals(60 * 60_000L, days[0].filteredScreenTimeMs)
    }

    @Test
    fun `a day whose apps are all filtered out stays, with its total and a sum of 0`() {
        val day = onlySocial.apply(week)[1]
        assertTrue(day.apps.isEmpty())
        assertEquals(30 * 60_000L, day.totalScreenTimeMs)
        assertEquals(0L, day.filteredScreenTimeMs)
    }

    @Test
    fun `an empty allowlist sends no app at all`() {
        val days = ScreenTimeAppFilter(AppFilterMode.ALLOWLIST).apply(week)
        assertTrue(days.all { it.apps.isEmpty() && it.filteredScreenTimeMs == 0L })
    }

    @Test
    fun `an unknown stored mode sends every app`() {
        assertEquals(AppFilterMode.ALL, AppFilterMode.from("SOMETHING_NEWER"))
        assertEquals(AppFilterMode.ALL, AppFilterMode.from(null))
        assertEquals(AppFilterMode.BLOCKLIST, AppFilterMode.from("BLOCKLIST"))
    }

    // ==================== The payload ====================

    private fun payload(filter: ScreenTimeAppFilter) =
        ScreenTimeSyncManager.buildJsonPayload(filter.apply(week), "1.23.0", "Google Pixel 8", 1, appFilter = filter.mode)

    @Test
    fun `a filtered payload names the mode and carries the sum next to the real total`() {
        val json = Json.parseToJsonElement(payload(noYoutube)).jsonObject
        assertEquals("blocklist", json.getValue("app_filter").jsonPrimitive.content)
        val day = json.getValue("screen_time").jsonArray.first().jsonObject
        assertEquals(200L, day.getValue("total_screen_time_minutes").jsonPrimitive.long)
        assertEquals(110L, day.getValue("filtered_screen_time_minutes").jsonPrimitive.long)
        assertEquals(2, day.getValue("apps").jsonArray.size)
    }

    @Test
    fun `an app left out is nowhere in the payload, not even by name`() {
        val text = payload(noYoutube)
        assertFalse(text.contains("youtube"))
    }

    @Test
    fun `without a filter the payload is what it always was`() {
        val json = Json.parseToJsonElement(payload(ScreenTimeAppFilter.ALL)).jsonObject
        assertFalse(json.containsKey("app_filter"))
        val day = json.getValue("screen_time").jsonArray.first().jsonObject
        assertFalse(day.containsKey("filtered_screen_time_minutes"))
        assertEquals(3, day.getValue("apps").jsonArray.size)
    }

    // ==================== MQTT ====================

    private fun sensors(filter: ScreenTimeAppFilter) = MqttSupport.sensorsFromScreenTime(filter.apply(week)).associateBy { it.key }

    @Test
    fun `the sensors follow the filter and keep the real total as an attribute`() {
        val s = sensors(noYoutube)
        assertEquals("110", s.getValue("screen_time_today").state)
        assertEquals("200", s.getValue("screen_time_today").attributes["all_apps_minutes"])
        assertEquals("whatsapp", s.getValue("screen_time_top_app").state)
        assertFalse(s.getValue("screen_time_today").attributes.getValue("top_apps").contains("youtube"))
    }

    @Test
    fun `without a filter the sensors are what they always were`() {
        val s = sensors(ScreenTimeAppFilter.ALL)
        assertEquals("200", s.getValue("screen_time_today").state)
        assertFalse(s.getValue("screen_time_today").attributes.containsKey("all_apps_minutes"))
        assertEquals("youtube", s.getValue("screen_time_top_app").state)
    }

    @Test
    fun `a filter that leaves no app of today names none, so no old top app stays on the broker`() {
        val s = sensors(ScreenTimeAppFilter(AppFilterMode.ALLOWLIST, setOf("com.instagram.android")))
        assertEquals("0", s.getValue("screen_time_today").state)
        assertEquals(MqttSupport.NO_TOP_APP, s.getValue("screen_time_top_app").state)
        assertFalse(s.getValue("screen_time_top_app").attributes.containsKey("package"))
    }

    // ==================== The settings backup ====================

    @Test
    fun `the filter survives a backup round trip`() {
        val config = AppFilterConfig.from(noYoutube)
        assertEquals("BLOCKLIST", config.mode)
        assertEquals(noYoutube, config.toFilter())
        val json = Json.encodeToString(OptionsConfig.serializer(), OptionsConfig(screenTimeAppFilter = config))
        assertTrue(json.contains("\"screen_time_app_filter\""))
        assertEquals(noYoutube, Json.decodeFromString(OptionsConfig.serializer(), json).screenTimeAppFilter?.toFilter())
    }

    @Test
    fun `a backup without the filter leaves it alone`() {
        assertNull(Json { ignoreUnknownKeys = true }.decodeFromString(OptionsConfig.serializer(), "{}").screenTimeAppFilter)
    }
}
