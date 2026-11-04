package com.owen282000.lifedashboard.screens.ld

import com.owen282000.lifedashboard.BACKFILL_DATA_TYPE
import com.owen282000.lifedashboard.BACKFILL_RUN_DATA_TYPE
import com.owen282000.lifedashboard.Destination
import com.owen282000.lifedashboard.Destinations
import com.owen282000.lifedashboard.LogDestination
import com.owen282000.lifedashboard.LogDirection
import com.owen282000.lifedashboard.SyncSchedule
import com.owen282000.lifedashboard.WebhookLog
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/*
 * What Home says, as plain data: its states come from the settings and the log, nothing from a
 * state of its own. Free of Android, so the rules are tested on the JVM.
 */

/** The last delivery to one destination. */
data class HomeDelivery(val timestamp: Long, val success: Boolean, val statusCode: Int?)

/** The last delivery per destination, from the log. */
data class HomeDeliveries(
    val webhooks: Map<String, HomeDelivery> = emptyMap(),
    val mqtt: HomeDelivery? = null,
    /** The newest delivery that arrived anywhere. */
    val lastDelivered: Long? = null,
    /** False for the moment the log takes to read, so nothing on screen says something untrue. */
    val isLoaded: Boolean = false
) {
    companion object {
        /** [logs] is newest first, so the first row that counts is the last delivery. [droppedReasons] are the outbox's own texts. */
        fun from(logs: List<WebhookLog>, urls: List<String>, droppedReasons: Set<String> = emptySet()): HomeDeliveries {
            val rows = logs.filter { isDelivery(it, droppedReasons) }
            return HomeDeliveries(
                webhooks = urls.mapNotNull { url -> rows.firstOrNull { !it.isMqtt && it.url == url }?.let { url to it.asDelivery() } }.toMap(),
                // A backfill without a webhook has only the broker to have gone to.
                mqtt = rows.firstOrNull { it.isMqtt || it.url == BACKFILL_DATA_TYPE }?.asDelivery(),
                lastDelivered = rows.firstOrNull { it.success }?.timestamp,
                isLoaded = true
            )
        }

        /**
         * Not a delivery: a test ping, which the destination's page reports itself; what came in
         * from Home Assistant; a backfill that failed or sent nothing, which Backfill reports
         * itself; and an item dropped from the outbox, whose row can be newer than a delivery that
         * went fine, and which lists every address it was queued for. A backfill that sent records
         * is one: its chunks log no row of their own, and that destination's page says Delivered.
         */
        internal fun isDelivery(log: WebhookLog, droppedReasons: Set<String>): Boolean {
            if (log.dataType == "test") return false
            if (log.dataType == BACKFILL_RUN_DATA_TYPE && (!log.success || (log.recordCount ?: 0) == 0)) return false
            if (log.direction != LogDirection.OUT.name) return false
            val error = log.errorMessage ?: return true
            return error !in droppedReasons && !error.startsWith(REFUSED_AND_DROPPED)
        }

        /** How PendingDrainer starts the row of a payload it gave up on; that text is not translated. */
        private const val REFUSED_AND_DROPPED = "Refused for a week"

        private val WebhookLog.isMqtt: Boolean get() = destination == LogDestination.MQTT.name

        private fun WebhookLog.asDelivery() = HomeDelivery(timestamp, success, statusCode)
    }
}

/** The state Home's heading and line say. */
sealed interface HomeState {
    /** The log is still being read, and the answer decides between the states below it. */
    data object Loading : HomeState
    data object NoDestination : HomeState
    data object NothingSelected : HomeState

    /** Types are selected and a destination is set, but Health Connect has given no access. */
    data object NoAccess : HomeState

    /** The names of the destinations that need attention. */
    data class Problem(val names: List<String>) : HomeState

    /** Nothing delivered for longer than the schedule explains. */
    data object Overdue : HomeState

    /** Set up, and the first delivery is still to come. */
    data object Waiting : HomeState
    data object Arriving : HomeState

    companion object {
        fun of(
            hasDestination: Boolean,
            nothingSelected: Boolean,
            accessMissing: Boolean,
            failing: List<String>,
            overdue: Boolean,
            isLoaded: Boolean,
            hasDelivery: Boolean
        ): HomeState = when {
            !hasDestination -> NoDestination
            nothingSelected -> NothingSelected
            accessMissing -> NoAccess
            !isLoaded && failing.isEmpty() -> Loading
            failing.isNotEmpty() -> Problem(failing)
            overdue -> Overdue
            !hasDelivery -> Waiting
            else -> Arriving
        }
    }
}

/** Why automatic syncs are held at a moment; Sync Now still works through all of them. */
sealed interface SyncHold {
    data object Never : SyncHold
    data class Quiet(val until: LocalTime) : SyncHold
    data object DayOff : SyncHold

    companion object {
        fun of(schedule: SyncSchedule, now: LocalDateTime): SyncHold? {
            if (schedule.isNeverRunning) return Never
            val quiet = schedule.quietWindow
            if (quiet != null && quiet.contains(now.toLocalTime())) return Quiet(quiet.to)
            if (now.dayOfWeek !in schedule.days) return DayOff
            return null
        }
    }
}

/** When silence counts as a problem, as on iOS. */
object SyncHealthCheck {
    /** At least two days, so a phone left at home over a weekend does not count. */
    private val MINIMUM_QUIET: Duration = Duration.ofDays(2)

    /** A day past the next sync the schedule expects, for a schedule that runs less than daily. */
    private val GRACE: Duration = Duration.ofDays(1)

    /** The later of two days on and a day past the next scheduled sync; null when no automatic sync is expected at all. */
    fun overdueFrom(lastDelivered: Long, schedule: SyncSchedule, zone: ZoneId): Instant? {
        val last = Instant.ofEpochMilli(lastDelivered)
        val zoned = last.atZone(zone)
        val next = schedule.delayFrom(zoned, zoned) ?: return null
        return maxOf(last.plus(MINIMUM_QUIET), last.plus(next).plus(GRACE))
    }

    fun isOverdue(lastDelivered: Long?, now: Long, schedule: SyncSchedule, hasDestination: Boolean, zone: ZoneId): Boolean {
        if (lastDelivered == null || !hasDestination) return false
        val due = overdueFrom(lastDelivered, schedule, zone) ?: return false
        return !Instant.ofEpochMilli(now).isBefore(due)
    }
}

/** What a destination is called on Home and at the top of its page. */
object DestinationNames {
    private const val NABU_CASA_HOST = "hooks.nabu.casa"
    private const val WEBHOOK_PATH = "/api/webhook/"

    fun looksLikeHomeAssistant(url: String): Boolean {
        val lower = url.trim().lowercase()
        return lower.contains("://$NABU_CASA_HOST") || lower.contains(WEBHOOK_PATH)
    }

    /** [homeAssistant] for the one Home Assistant among [section]; two of them are told apart by their host. */
    fun name(destination: Destination, section: List<Destination>, homeAssistant: String): String {
        val integrations = section.count { looksLikeHomeAssistant(it.url) }
        return if (looksLikeHomeAssistant(destination.url) && integrations == 1) homeAssistant else Destinations.label(destination, section)
    }
}
