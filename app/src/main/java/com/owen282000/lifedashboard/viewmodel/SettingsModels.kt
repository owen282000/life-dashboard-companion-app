package com.owen282000.lifedashboard.viewmodel

import com.owen282000.lifedashboard.HealthDataType
import com.owen282000.lifedashboard.MqttBroker
import com.owen282000.lifedashboard.MqttSectionSettings
import com.owen282000.lifedashboard.QuietWindow
import com.owen282000.lifedashboard.SyncMode
import com.owen282000.lifedashboard.SyncSchedule
import java.time.DayOfWeek
import java.time.LocalTime

/*
 * The editable settings of the two sync tabs as plain data, so the "has this changed" and
 * "is this valid" questions are pure functions the view models and tests share. Text fields
 * keep their raw text here (interval, port, day boundary hour) and are parsed on save.
 */

data class WebhookDraft(
    val urls: List<String> = emptyList(),
    val headers: Map<String, String> = emptyMap(),
    val secret: String = ""
)

data class MqttDraft(
    val section: MqttSectionSettings,
    val sharedBroker: MqttBroker,
    val portText: String
) {
    val activeBroker: MqttBroker get() = if (section.useSharedBroker) sharedBroker else section.ownBroker

    /** Port lives in its own text field; fold it into whichever broker is active before comparing or saving. */
    fun withPort(): MqttDraft {
        val port = portText.toIntOrNull()
        return if (section.useSharedBroker) {
            copy(sharedBroker = sharedBroker.copy(port = port ?: sharedBroker.port))
        } else {
            copy(section = section.copy(ownBroker = section.ownBroker.copy(port = port ?: section.ownBroker.port)))
        }
    }

    /** Edits the broker the section currently points at: the shared one, or its own. */
    fun withActiveBroker(broker: MqttBroker): MqttDraft =
        if (section.useSharedBroker) copy(sharedBroker = broker) else copy(section = section.copy(ownBroker = broker))

    /** Switching between shared and own broker also swaps the port text to that broker's port. */
    fun withShared(shared: Boolean): MqttDraft = copy(
        section = section.copy(useSharedBroker = shared),
        portText = (if (shared) sharedBroker.port else section.ownBroker.port).toString()
    )

    companion object {
        fun from(section: MqttSectionSettings, sharedBroker: MqttBroker) = MqttDraft(
            section = section,
            sharedBroker = sharedBroker,
            portText = (if (section.useSharedBroker) sharedBroker.port else section.ownBroker.port).toString()
        )
    }
}

/**
 * The schedule of one tab while it is being edited. Times live as a list of [LocalTime] (the
 * picker produces nothing else), the quiet window as two optional times, so the only parsing
 * left on save is none at all. [toSchedule] is the single conversion point to the domain type.
 */
data class ScheduleDraft(
    val mode: SyncMode = SyncMode.INTERVAL,
    val intervalText: String = SyncSchedule.DEFAULT_INTERVAL_MINUTES.toString(),
    val times: List<LocalTime> = emptyList(),
    val days: Set<DayOfWeek> = DayOfWeek.entries.toSet(),
    val quietFrom: LocalTime? = null,
    val quietTo: LocalTime? = null
) {
    val quietWindow: QuietWindow?
        get() = if (quietFrom != null && quietTo != null) QuietWindow(quietFrom, quietTo) else null

    val quietEnabled: Boolean get() = quietWindow != null

    /** The domain schedule, using [fallbackInterval] when the interval field is mid-edit. */
    fun toSchedule(fallbackInterval: Int = SyncSchedule.DEFAULT_INTERVAL_MINUTES) = SyncSchedule(
        mode = mode,
        intervalMinutes = SettingsRules.intervalOrNull(intervalText) ?: fallbackInterval,
        times = times,
        days = days,
        quietWindow = quietWindow
    )

    /** True when these settings would stop the syncing entirely, which the UI warns about. */
    fun wouldNeverRun(): Boolean = toSchedule().isNeverRunning

    companion object {
        fun from(schedule: SyncSchedule) = ScheduleDraft(
            mode = schedule.mode,
            intervalText = schedule.intervalMinutes.toString(),
            times = schedule.times,
            days = schedule.days,
            quietFrom = schedule.quietWindow?.from,
            quietTo = schedule.quietWindow?.to
        )
    }
}

data class HealthDraft(
    val webhook: WebhookDraft,
    val enabledTypes: Set<HealthDataType>,
    val mqtt: MqttDraft,
    val schedule: ScheduleDraft = ScheduleDraft()
) {
    val hasDestination: Boolean get() = webhook.urls.isNotEmpty() || mqtt.section.enabled

    fun differsFrom(saved: HealthDraft): Boolean =
        webhook != saved.webhook ||
            enabledTypes != saved.enabledTypes ||
            scheduleDiffers(schedule, saved.schedule) ||
            mqtt.withPort().let { it.section to it.sharedBroker } != saved.mqtt.withPort().let { it.section to it.sharedBroker }
}

data class ScreenTimeDraft(
    val webhook: WebhookDraft,
    val dayBoundaryHour: String,
    val useDayBoundary: Boolean,
    val mqtt: MqttDraft,
    val schedule: ScheduleDraft = ScheduleDraft()
) {
    val hasDestination: Boolean get() = webhook.urls.isNotEmpty() || mqtt.section.enabled

    /** Same rule the old screen used for the hour: parse what can be parsed, fall back to the saved value, then compare. */
    fun differsFrom(saved: ScreenTimeDraft): Boolean {
        val hour = dayBoundaryHour.toIntOrNull() ?: saved.dayBoundaryHour.toIntOrNull()
        return hour != saved.dayBoundaryHour.toIntOrNull() ||
            useDayBoundary != saved.useDayBoundary ||
            webhook != saved.webhook ||
            scheduleDiffers(schedule, saved.schedule) ||
            mqtt.withPort().let { it.section to it.sharedBroker } != saved.mqtt.withPort().let { it.section to it.sharedBroker }
    }
}

/**
 * Compares two schedule drafts the way the save button should: an interval being retyped only
 * counts once it parses, and in interval mode the times are irrelevant (and vice versa), so
 * switching mode back and forth does not light up the save bar.
 */
private fun scheduleDiffers(draft: ScheduleDraft, saved: ScheduleDraft): Boolean {
    if (draft.mode != saved.mode) return true
    if (draft.days != saved.days || draft.quietWindow != saved.quietWindow) return true
    return when (draft.mode) {
        SyncMode.INTERVAL -> {
            val interval = SettingsRules.intervalOrNull(draft.intervalText)
                ?: SettingsRules.intervalOrNull(saved.intervalText)
            interval != SettingsRules.intervalOrNull(saved.intervalText)
        }
        SyncMode.TIMES -> draft.times.distinct().sorted() != saved.times.distinct().sorted()
    }
}

object SettingsRules {
    const val MIN_INTERVAL_MINUTES = SyncSchedule.MIN_INTERVAL_MINUTES

    /** WorkManager refuses periodic work under 15 minutes, so neither do we. */
    fun intervalOrNull(text: String): Int? = text.toIntOrNull()?.takeIf { it >= MIN_INTERVAL_MINUTES }

    fun dayBoundaryHourOrNull(text: String): Int? = text.toIntOrNull()?.takeIf { it in 0..23 }

    fun isValidUrl(url: String): Boolean = url.isNotBlank() && url.startsWith("http")

    /**
     * What is wrong with a schedule, or null when it can be saved. The interval floor only
     * applies in interval mode: a schedule that runs at 08:00 and 08:05 is the user's call,
     * not WorkManager's, because fixed times run as one-time work.
     */
    fun scheduleProblem(schedule: ScheduleDraft): UiMessage? = when {
        schedule.mode == SyncMode.INTERVAL && intervalOrNull(schedule.intervalText) == null -> UiMessage.IntervalTooShort
        schedule.mode == SyncMode.TIMES && schedule.times.isEmpty() -> UiMessage.NoSyncTimes
        schedule.wouldNeverRun() -> UiMessage.ScheduleNeverRuns
        else -> null
    }
}

/** Health Connect availability on this device, as the SDK reports it. */
enum class HcAvailability { AVAILABLE, NOT_INSTALLED, NEEDS_UPDATE, UNAVAILABLE }

/**
 * What a screen tells the user, as data. The composable turns these into localized strings;
 * the view models never touch resources, which keeps them testable on the JVM.
 */
sealed interface UiMessage {
    data object Saved : UiMessage
    data object IntervalTooShort : UiMessage
    data object NoSyncTimes : UiMessage
    data object ScheduleNeverRuns : UiMessage
    data object NoDestination : UiMessage
    data object InvalidDayBoundaryHour : UiMessage
    data object InvalidUrl : UiMessage
    data object HeaderNeedsNameAndValue : UiMessage
    data object NoNewData : UiMessage
    data class SyncedRecords(val count: Int) : UiMessage
    data class QueuedRecords(val count: Int) : UiMessage
    data class SyncedApps(val count: Int) : UiMessage
    data class QueuedApps(val count: Int) : UiMessage
    data class SyncFailed(val reason: String) : UiMessage
    data object HealthConnectUnavailable : UiMessage
    data object UsageAccessMissing : UiMessage
    data class PreviewFailed(val reason: String?) : UiMessage
    data class ExportFailed(val reason: String?) : UiMessage
    data object PingDelivered : UiMessage
    data object PingFailed : UiMessage
    data class PingFailedWith(val reason: String) : UiMessage
    data class BackfillComplete(val count: Int) : UiMessage
    data object BackfillNeedsWebhook : UiMessage

    /** True for the messages the sync line paints red. */
    val isFailure: Boolean
        get() = this is SyncFailed || this is HealthConnectUnavailable || this is UsageAccessMissing ||
            this is PingFailed || this is PingFailedWith || this is PreviewFailed || this is ExportFailed
}
