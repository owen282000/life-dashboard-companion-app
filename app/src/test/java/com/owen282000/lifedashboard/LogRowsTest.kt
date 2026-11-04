package com.owen282000.lifedashboard

import com.owen282000.lifedashboard.screens.ld.LogFilter
import com.owen282000.lifedashboard.screens.ld.LogLine
import com.owen282000.lifedashboard.screens.ld.LogStats
import com.owen282000.lifedashboard.screens.ld.code
import com.owen282000.lifedashboard.screens.ld.hosts
import com.owen282000.lifedashboard.screens.ld.isSkippedReceive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

/** What the Logs tab says about its rows: the code, the filters, the figures and the day lines. */
class LogRowsTest {

    private fun log(
        id: String = "a",
        timestamp: Long = 0,
        url: String = "https://example.org/hook",
        statusCode: Int? = 200,
        success: Boolean = true,
        recordCount: Int? = 3,
        logType: LogType = LogType.HEALTH_CONNECT,
        destination: LogDestination = LogDestination.WEBHOOK,
        direction: LogDirection = LogDirection.OUT
    ) = WebhookLog(
        id = id,
        timestamp = timestamp,
        url = url,
        statusCode = statusCode,
        success = success,
        errorMessage = null,
        dataType = "health_connect",
        recordCount = recordCount,
        logType = logType.name,
        destination = destination.name,
        direction = direction.name
    )

    private val incoming = log(id = "in", statusCode = null, direction = LogDirection.IN)
    private val mqtt = log(id = "mqtt", statusCode = null, destination = LogDestination.MQTT, url = "mqtt://broker.local:1883/health")
    private val screenTime = log(id = "st", logType = LogType.SCREEN_TIME)
    private val failed = log(id = "failed", statusCode = 422, success = false)

    @Test
    fun theCodeIsTheWayItWentThenTheStatusThenAMark() {
        assertEquals("IN", incoming.code)
        assertEquals("MQTT", mqtt.code)
        assertEquals("422", failed.code)
        assertEquals("OK", log(statusCode = null).code)
        assertEquals("ERR", log(statusCode = null, success = false).code)
    }

    @Test
    fun theRowNamesTheHostAndNotTheSecretInTheAddress() {
        assertEquals("example.org", log().hosts)
        assertEquals("broker.local", mqtt.hosts)
        assertEquals("a.org, b.org", log(url = "https://a.org/x, https://b.org/y").hosts)
    }

    @Test
    fun aReceiveRoundThatWroteNothingIsSkipped() {
        assertTrue(incoming.copy(recordCount = 0).isSkippedReceive)
        assertFalse(incoming.isSkippedReceive)
        assertFalse(incoming.copy(recordCount = 0, success = false).isSkippedReceive)
    }

    @Test
    fun filtersKeepReceiveOutOfTheDeliveriesButNotOutOfHealthOrFailed() {
        val rows = listOf(incoming, mqtt, screenTime, failed)
        fun shown(filter: LogFilter) = rows.filter(filter::includes).map { it.id }
        assertEquals(listOf("in", "mqtt", "st", "failed"), shown(LogFilter.All))
        assertEquals(listOf("failed"), shown(LogFilter.Failed))
        assertEquals(listOf("st", "failed"), shown(LogFilter.Webhook))
        assertEquals(listOf("mqtt"), shown(LogFilter.Mqtt))
        assertEquals(listOf("in", "mqtt", "failed"), shown(LogFilter.Health))
        assertEquals(listOf("st"), shown(LogFilter.ScreenTime))
        assertTrue(LogFilter.Failed.includes(incoming.copy(success = false)))
    }

    @Test
    fun theFiguresLeaveOutWhatCameIn() {
        val stats = LogStats.of(listOf(incoming.copy(timestamp = 99), incoming.copy(success = false), mqtt.copy(timestamp = 5), screenTime.copy(timestamp = 7), failed))
        assertEquals("the heading counts what the Failed filter shows, a failed Receive round too", 2, stats.failed)
        assertEquals(1, stats.failedDeliveries)
        assertEquals(2, stats.delivered)
        assertEquals(3, stats.deliveries)
        assertEquals(66, stats.successPercent)
        assertEquals(6, stats.records)
        assertEquals(7L, stats.lastSuccess)
        assertEquals(1, stats.healthDelivered)
        assertEquals(2, stats.health)
        assertEquals(1, stats.screenTimeDelivered)
        assertEquals(1, stats.screenTime)
    }

    @Test
    fun noDeliveriesHaveNoPercentAndNoLastSuccess() {
        val stats = LogStats.of(listOf(incoming))
        assertEquals(0, stats.deliveries)
        assertNull(stats.successPercent)
        assertNull(stats.lastSuccess)
    }

    @Test
    fun everyDayButTodayGetsALineAboveItsFirstRow() {
        val day = 24 * 60 * 60 * 1000L
        val rows = listOf(10 * day + 5, 10 * day, 9 * day, 9 * day - 1).mapIndexed { index, time ->
            WebhookLogRow(log(id = "r$index", timestamp = time))
        }
        val today = LocalDate.ofEpochDay(10)
        val lines = LogLine.of(rows, today, ZoneOffset.UTC)
        assertEquals(listOf("r0", "r1", "day-r2", "r2", "day-r3", "r3"), lines.map { it.key })
        assertEquals(LocalDate.ofEpochDay(9), (lines[2] as LogLine.Day).date)
    }

    @Test
    fun aDayThatComesTwiceKeepsItsKeysApart() {
        val day = 24 * 60 * 60 * 1000L
        val rows = listOf(2 * day, 3 * day, 2 * day).mapIndexed { index, time ->
            WebhookLogRow(log(id = "r$index", timestamp = time))
        }
        val keys = LogLine.of(rows, LocalDate.ofEpochDay(10), ZoneOffset.UTC).map { it.key }
        assertEquals(keys.size, keys.toSet().size)
    }
}
