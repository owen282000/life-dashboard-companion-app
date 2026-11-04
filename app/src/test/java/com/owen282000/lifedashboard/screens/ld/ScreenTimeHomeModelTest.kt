package com.owen282000.lifedashboard.screens.ld

import com.owen282000.lifedashboard.AppUsageData
import com.owen282000.lifedashboard.LogDestination
import com.owen282000.lifedashboard.LogDirection
import com.owen282000.lifedashboard.LogType
import com.owen282000.lifedashboard.ScreenTimeData
import com.owen282000.lifedashboard.WebhookLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime

class ScreenTimeHomeModelTest {
    private val today = LocalDate.of(2026, 11, 4)

    private fun app(name: String, minutes: Long) = AppUsageData("pkg.$name", name, minutes * 60_000, Instant.EPOCH)

    private fun day(date: LocalDate, vararg apps: AppUsageData) = ScreenTimeData(date, apps.sumOf { it.totalTimeMs }, apps.toList())

    private fun log(
        timestamp: Long,
        url: String = "https://a.example/hook",
        success: Boolean = true,
        statusCode: Int? = 200,
        dataType: String? = "screen_time",
        destination: LogDestination = LogDestination.WEBHOOK,
        direction: LogDirection = LogDirection.OUT
    ) = WebhookLog(
        id = "$timestamp",
        timestamp = timestamp,
        url = url,
        statusCode = statusCode,
        success = success,
        errorMessage = null,
        dataType = dataType,
        recordCount = 1,
        logType = LogType.SCREEN_TIME.name,
        destination = destination.name,
        direction = direction.name
    )

    private fun data(days: List<ScreenTimeData> = emptyList(), logs: List<WebhookLog> = emptyList()) =
        ScreenTimeHomeModel.data(days, today, null, logs, readAt = 1000)

    @Test
    fun beforeTheBoundaryItIsStillYesterday() {
        val early = LocalDateTime.of(2026, 11, 4, 3, 59)
        assertEquals(today.minusDays(1), ScreenTimeHomeModel.logicalToday(early, useBoundary = true, boundaryHour = 4))
        assertEquals(today, ScreenTimeHomeModel.logicalToday(early.plusMinutes(1), useBoundary = true, boundaryHour = 4))
        assertEquals(today, ScreenTimeHomeModel.logicalToday(early, useBoundary = false, boundaryHour = 4))
    }

    @Test
    fun aDayWithoutUsageIsNotShownAsToday() {
        val result = data(listOf(day(today.minusDays(1), app("Old", 90))))
        assertEquals(0L, result.totalMinutes)
        assertTrue(result.apps.isEmpty())
        assertEquals(listOf(90L), result.minutesPerDay)
    }

    @Test
    fun todayGivesTotalAndAppsMostUsedFirst() {
        val result = data(listOf(day(today, app("Small", 10), app("Big", 200))))
        assertEquals(210L, result.totalMinutes)
        assertEquals(listOf("Big", "Small"), result.apps.map { it.appName })
    }

    @Test
    fun minutesPerDayRunOldestFirst() {
        val result = data(listOf(day(today, app("A", 5)), day(today.minusDays(2), app("A", 7)), day(today.minusDays(1), app("A", 6))))
        assertEquals(listOf(7L, 6L, 5L), result.minutesPerDay)
    }

    @Test
    fun theNewestDeliveryPerAddressCounts() {
        val result = data(
            logs = listOf(
                log(timestamp = 100, success = true),
                log(timestamp = 200, success = false, statusCode = 500),
                log(timestamp = 300, url = "https://b.example/hook")
            )
        )
        val first = result.webhookDeliveries.getValue("https://a.example/hook")
        assertEquals(200L, first.timestamp)
        assertFalse(first.success)
        assertEquals(500, first.statusCode)
        assertEquals(300L, result.webhookDeliveries.getValue("https://b.example/hook").timestamp)
        assertNull(result.mqttDelivery)
    }

    @Test
    fun mqttCountsOnItsOwn() {
        val result = data(logs = listOf(log(timestamp = 100), log(timestamp = 50, url = "mqtt://host:1883/base", destination = LogDestination.MQTT)))
        assertEquals(50L, result.mqttDelivery?.timestamp)
        assertEquals(setOf("https://a.example/hook"), result.webhookDeliveries.keys)
    }

    @Test
    fun aTestPingOrAReceivedRowIsNoDelivery() {
        val result = data(logs = listOf(log(timestamp = 100, dataType = "test"), log(timestamp = 200, direction = LogDirection.IN)))
        assertTrue(result.webhookDeliveries.isEmpty())
    }
}
