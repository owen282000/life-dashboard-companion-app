package com.owen282000.lifedashboard.screens.ld

import com.owen282000.lifedashboard.AppUsageData
import com.owen282000.lifedashboard.LogDestination
import com.owen282000.lifedashboard.LogDirection
import com.owen282000.lifedashboard.ScreenTimeData
import com.owen282000.lifedashboard.WebhookLog
import java.time.LocalDate
import java.time.LocalDateTime

/** How the last delivery to a destination went. */
internal data class ScreenTimeDelivery(val timestamp: Long, val success: Boolean, val statusCode: Int?)

/** What the Screen Time tab shows, as read at [readAt]. */
internal data class ScreenTimeHomeData(
    val totalMinutes: Long,
    /** Today's apps, most used first. */
    val apps: List<AppUsageData>,
    /** Oldest first, only the days with usage. */
    val minutesPerDay: List<Long>,
    val lastSyncMillis: Long?,
    val webhookDeliveries: Map<String, ScreenTimeDelivery>,
    val mqttDelivery: ScreenTimeDelivery?,
    val readAt: Long
)

internal object ScreenTimeHomeModel {
    private const val TEST_PING_TYPE = "test"

    /** The day [now] counts for: before the boundary hour it is still yesterday, as ScreenTimeManager has it. */
    fun logicalToday(now: LocalDateTime, useBoundary: Boolean, boundaryHour: Int): LocalDate =
        if (useBoundary && now.hour < boundaryHour) now.toLocalDate().minusDays(1) else now.toLocalDate()

    /**
     * The manager leaves out a day without usage, so the newest day it gives need not be today:
     * today is looked up by date, and is empty when nothing was used yet.
     */
    fun data(
        days: List<ScreenTimeData>,
        today: LocalDate,
        lastSyncMillis: Long?,
        logs: List<WebhookLog>,
        readAt: Long
    ): ScreenTimeHomeData {
        val current = days.firstOrNull { it.date == today }
        val deliveries = logs.filter { it.direction == LogDirection.OUT.name && it.dataType != TEST_PING_TYPE }
        return ScreenTimeHomeData(
            totalMinutes = (current?.totalScreenTimeMs ?: 0L) / 60_000,
            apps = current?.apps.orEmpty().sortedByDescending { it.totalTimeMs },
            minutesPerDay = days.sortedBy { it.date }.map { it.totalScreenTimeMs / 60_000 },
            lastSyncMillis = lastSyncMillis,
            webhookDeliveries = deliveries
                .filter { it.destination == LogDestination.WEBHOOK.name }
                .groupBy { it.url }
                .mapValues { (_, entries) -> entries.maxBy { it.timestamp }.toDelivery() },
            mqttDelivery = deliveries
                .filter { it.destination == LogDestination.MQTT.name }
                .maxByOrNull { it.timestamp }
                ?.toDelivery(),
            readAt = readAt
        )
    }

    private fun WebhookLog.toDelivery() = ScreenTimeDelivery(timestamp, success, statusCode)
}
