package com.owen282000.lifedashboard.screens.ld

import com.owen282000.lifedashboard.LogDestination
import com.owen282000.lifedashboard.LogDirection
import com.owen282000.lifedashboard.LogType
import com.owen282000.lifedashboard.PartialDelivery
import com.owen282000.lifedashboard.WebhookLog
import com.owen282000.lifedashboard.WebhookLogRow
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

val WebhookLog.isIncoming: Boolean get() = direction == LogDirection.IN.name
val WebhookLog.isMqtt: Boolean get() = destination == LogDestination.MQTT.name

/** A Receive round that wrote nothing is neither a success worth green nor a failure. */
val WebhookLog.isSkippedReceive: Boolean get() = isIncoming && success && (recordCount ?: 0) == 0

/** The fixed column of a row: the status code, the way it went, or a short mark when no answer came. */
val WebhookLog.code: String
    get() = when {
        isIncoming -> "IN"
        isMqtt -> "MQTT"
        statusCode != null -> statusCode.toString()
        success -> "OK"
        else -> "ERR"
    }

/** Where it went, never the full URL: a webhook URL can hold a secret id. A row of a dropped payload names several URLs. */
val WebhookLog.hosts: String get() = PartialDelivery.hosts(url.split(", "))

enum class LogFilter {
    All,
    Failed,
    Webhook,
    Mqtt,
    Health,
    ScreenTime;

    /** A Receive row went in, so it is not a webhook delivery; it is Health data, and it can fail. */
    fun includes(log: WebhookLog): Boolean = when (this) {
        All -> true
        Failed -> !log.success
        Webhook -> !log.isIncoming && !log.isMqtt
        Mqtt -> log.isMqtt
        Health -> log.logType == LogType.HEALTH_CONNECT.name
        ScreenTime -> log.logType == LogType.SCREEN_TIME.name
    }
}

/**
 * The figures above the list. [failed] is every row that failed, as the Failed filter shows them, a
 * Receive round that went wrong included. The rest goes over what went out: what came in through
 * Receive is not a delivery.
 */
data class LogStats(
    val failed: Int,
    val failedDeliveries: Int,
    val delivered: Int,
    val records: Int,
    val lastSuccess: Long?,
    val healthDelivered: Int,
    val health: Int,
    val screenTimeDelivered: Int,
    val screenTime: Int
) {
    val deliveries: Int get() = failedDeliveries + delivered
    val successPercent: Int? get() = if (deliveries > 0) delivered * 100 / deliveries else null

    companion object {
        fun of(logs: List<WebhookLog>): LogStats {
            val outgoing = logs.filter { !it.isIncoming }
            val succeeded = outgoing.filter { it.success }
            fun ofType(type: LogType, from: List<WebhookLog>) = from.count { it.logType == type.name }
            return LogStats(
                failed = logs.count { LogFilter.Failed.includes(it) },
                failedDeliveries = outgoing.size - succeeded.size,
                delivered = succeeded.size,
                records = succeeded.sumOf { it.recordCount ?: 0 },
                lastSuccess = succeeded.maxOfOrNull { it.timestamp },
                healthDelivered = ofType(LogType.HEALTH_CONNECT, succeeded),
                health = ofType(LogType.HEALTH_CONNECT, outgoing),
                screenTimeDelivered = ofType(LogType.SCREEN_TIME, succeeded),
                screenTime = ofType(LogType.SCREEN_TIME, outgoing)
            )
        }
    }
}

/** What the list holds below its heading. */
sealed interface LogLine {
    val key: String

    /** The clock can go back, so the same day can come twice: the key goes by the row below it. */
    data class Day(val date: LocalDate, val firstRowId: String) : LogLine {
        override val key: String get() = "day-$firstRowId"
    }

    data class Row(val row: WebhookLogRow) : LogLine {
        override val key: String get() = row.log.id
    }

    companion object {
        /**
         * The rows show a time only, and the log can go back days: every day but [today] gets a
         * line above its first row.
         */
        fun of(rows: List<WebhookLogRow>, today: LocalDate, zone: ZoneId): List<LogLine> {
            val lines = ArrayList<LogLine>(rows.size + 4)
            var previous: LocalDate? = null
            rows.forEach { row ->
                val date = Instant.ofEpochMilli(row.log.timestamp).atZone(zone).toLocalDate()
                if (date != previous && date != today) lines += Day(date, row.log.id)
                previous = date
                lines += Row(row)
            }
            return lines
        }
    }
}
