package com.owen282000.lifedashboard.screens.ld

import com.owen282000.lifedashboard.BACKFILL_DATA_TYPE
import com.owen282000.lifedashboard.BACKFILL_RUN_DATA_TYPE
import com.owen282000.lifedashboard.Destination
import com.owen282000.lifedashboard.LogDestination
import com.owen282000.lifedashboard.LogDirection
import com.owen282000.lifedashboard.QuietWindow
import com.owen282000.lifedashboard.SyncMode
import com.owen282000.lifedashboard.SyncSchedule
import com.owen282000.lifedashboard.WebhookLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneOffset

class HomeModelTest {

    private val url = "https://example.com/hook"

    private fun log(
        timestamp: Long,
        success: Boolean = true,
        url: String = this.url,
        statusCode: Int? = if (success) 200 else 500,
        dataType: String? = "health_connect",
        error: String? = null,
        destination: LogDestination = LogDestination.WEBHOOK,
        direction: LogDirection = LogDirection.OUT
    ) = WebhookLog(
        id = "$timestamp",
        timestamp = timestamp,
        url = url,
        statusCode = statusCode,
        success = success,
        errorMessage = error,
        dataType = dataType,
        recordCount = 1,
        destination = destination.name,
        direction = direction.name
    )

    private fun state(
        hasDestination: Boolean = true,
        nothingSelected: Boolean = false,
        accessMissing: Boolean = false,
        failing: List<String> = emptyList(),
        overdue: Boolean = false,
        isLoaded: Boolean = true,
        hasDelivery: Boolean = true
    ) = HomeState.of(hasDestination, nothingSelected, accessMissing, failing, overdue, isLoaded, hasDelivery)

    @Test
    fun `the newest row per address is its last delivery`() {
        val deliveries = HomeDeliveries.from(listOf(log(3, success = false), log(2)), listOf(url))
        assertEquals(HomeDelivery(3, false, 500), deliveries.webhooks[url])
        assertEquals(2L, deliveries.lastDelivered)
        assertTrue(deliveries.isLoaded)
    }

    @Test
    fun `a test ping, a backfill summary and what came in are no delivery`() {
        val rows = listOf(
            log(5, success = false, dataType = "test"),
            log(4, success = false, dataType = BACKFILL_RUN_DATA_TYPE),
            log(3, success = false, direction = LogDirection.IN),
            log(2)
        )
        assertEquals(HomeDelivery(2, true, 200), HomeDeliveries.from(rows, listOf(url)).webhooks[url])
    }

    @Test
    fun `a backfill that sent records is a delivery, one that sent none or failed is not`() {
        fun backfill(success: Boolean, records: Int, url: String = this.url) =
            log(4, success = success, url = url, statusCode = null, dataType = BACKFILL_RUN_DATA_TYPE).copy(recordCount = records)
        assertEquals(HomeDelivery(4, true, null), HomeDeliveries.from(listOf(backfill(true, 120)), listOf(url)).webhooks[url])
        assertEquals(4L, HomeDeliveries.from(listOf(backfill(true, 120)), listOf(url)).lastDelivered)
        assertTrue(HomeDeliveries.from(listOf(backfill(true, 0)), listOf(url)).webhooks.isEmpty())
        assertTrue(HomeDeliveries.from(listOf(backfill(false, 120)), listOf(url)).webhooks.isEmpty())
        // Without a webhook the run's row names no address, and it went to the broker.
        val onlyBroker = HomeDeliveries.from(listOf(backfill(true, 120, url = BACKFILL_DATA_TYPE)), emptyList())
        assertEquals(HomeDelivery(4, true, null), onlyBroker.mqtt)
    }

    @Test
    fun `an item dropped from the outbox does not hide a delivery that went fine`() {
        val rows = listOf(
            log(4, success = false, statusCode = null, error = "dropped"),
            log(3, success = false, error = "Refused for a week (HTTP 401), dropped from the outbox"),
            log(2)
        )
        assertTrue(HomeDeliveries.from(rows, listOf(url), droppedReasons = setOf("dropped")).webhooks.getValue(url).success)
    }

    @Test
    fun `MQTT rows belong to MQTT and not to an address`() {
        val rows = listOf(log(2, url = "mqtt://broker:1883/ld", dataType = "mqtt", destination = LogDestination.MQTT), log(1))
        val deliveries = HomeDeliveries.from(rows, listOf(url))
        assertEquals(2L, deliveries.mqtt?.timestamp)
        assertEquals(1L, deliveries.webhooks[url]?.timestamp)
    }

    @Test
    fun `the state goes from what is missing to what arrives`() {
        assertEquals(HomeState.NoDestination, state(hasDestination = false, nothingSelected = true, accessMissing = true))
        assertEquals(HomeState.NothingSelected, state(nothingSelected = true, accessMissing = true))
        assertEquals(HomeState.NoAccess, state(accessMissing = true, failing = listOf("a")))
        assertEquals(HomeState.Problem(listOf("a")), state(failing = listOf("a"), overdue = true))
        assertEquals(HomeState.Overdue, state(overdue = true))
        assertEquals(HomeState.Waiting, state(hasDelivery = false))
        assertEquals(HomeState.Arriving, state())
    }

    @Test
    fun `nothing is claimed before the log has been read`() {
        assertEquals(HomeState.Loading, state(isLoaded = false, hasDelivery = false))
        assertEquals(HomeState.Problem(listOf("no broker")), state(isLoaded = false, hasDelivery = false, failing = listOf("no broker")))
    }

    @Test
    fun `automatic syncs are held by a schedule that never runs, quiet hours and days off`() {
        val monday = LocalDateTime.of(2026, 11, 2, 23, 30)
        assertEquals(SyncHold.Never, SyncHold.of(SyncSchedule(mode = SyncMode.TIMES), monday))
        assertEquals(
            SyncHold.Quiet(LocalTime.of(7, 0)),
            SyncHold.of(SyncSchedule(quietWindow = QuietWindow(LocalTime.of(23, 0), LocalTime.of(7, 0))), monday)
        )
        assertEquals(SyncHold.DayOff, SyncHold.of(SyncSchedule(days = setOf(DayOfWeek.TUESDAY)), monday))
        assertNull(SyncHold.of(SyncSchedule(), monday))
    }

    @Test
    fun `silence counts after two days, or a day past the next sync when that is later`() {
        val monday = LocalDateTime.of(2026, 11, 2, 12, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
        fun overdueAfter(schedule: SyncSchedule, hours: Long) = SyncHealthCheck.isOverdue(
            monday, monday + Duration.ofHours(hours).toMillis(), schedule, hasDestination = true, ZoneOffset.UTC
        )
        assertFalse(overdueAfter(SyncSchedule(), 47))
        assertTrue(overdueAfter(SyncSchedule(), 48))

        val onceAWeek = SyncSchedule(mode = SyncMode.TIMES, times = listOf(LocalTime.of(12, 0)), days = setOf(DayOfWeek.MONDAY))
        assertFalse(overdueAfter(onceAWeek, 7 * 24 + 23))
        assertTrue(overdueAfter(onceAWeek, 8 * 24))
    }

    @Test
    fun `no silence counts without a delivery, a destination or a schedule that runs`() {
        val now = Duration.ofDays(30).toMillis()
        assertFalse(SyncHealthCheck.isOverdue(null, now, SyncSchedule(), hasDestination = true, ZoneOffset.UTC))
        assertFalse(SyncHealthCheck.isOverdue(1, now, SyncSchedule(), hasDestination = false, ZoneOffset.UTC))
        assertFalse(SyncHealthCheck.isOverdue(1, now, SyncSchedule(days = emptySet()), hasDestination = true, ZoneOffset.UTC))
    }

    @Test
    fun `one Home Assistant goes by its name, two by their hosts`() {
        val ha = Destination(id = "a", url = "https://ha.example.com/api/webhook/abc")
        val other = Destination(id = "b", url = "https://stack.example.com/health")
        assertEquals("Home Assistant", DestinationNames.name(ha, listOf(ha, other), "Home Assistant"))
        assertEquals("stack.example.com", DestinationNames.name(other, listOf(ha, other), "Home Assistant"))

        val second = Destination(id = "c", url = "https://hooks.nabu.casa/xyz")
        assertEquals("ha.example.com", DestinationNames.name(ha, listOf(ha, second), "Home Assistant"))
    }
}
