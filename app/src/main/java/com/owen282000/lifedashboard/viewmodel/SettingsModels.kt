package com.owen282000.lifedashboard.viewmodel

import com.owen282000.lifedashboard.HealthDataType
import com.owen282000.lifedashboard.MqttBroker
import com.owen282000.lifedashboard.MqttSectionSettings

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

data class HealthDraft(
    val syncInterval: String,
    val webhook: WebhookDraft,
    val enabledTypes: Set<HealthDataType>,
    val mqtt: MqttDraft
) {
    val hasDestination: Boolean get() = webhook.urls.isNotEmpty() || mqtt.section.enabled

    /** Same rule the old screen used: parse what can be parsed, fall back to the saved value, then compare. */
    fun differsFrom(saved: HealthDraft): Boolean {
        val interval = syncInterval.toIntOrNull() ?: saved.syncInterval.toIntOrNull()
        return interval != saved.syncInterval.toIntOrNull() ||
            webhook != saved.webhook ||
            enabledTypes != saved.enabledTypes ||
            mqtt.withPort().let { it.section to it.sharedBroker } != saved.mqtt.withPort().let { it.section to it.sharedBroker }
    }
}

data class ScreenTimeDraft(
    val syncInterval: String,
    val webhook: WebhookDraft,
    val dayBoundaryHour: String,
    val useDayBoundary: Boolean,
    val mqtt: MqttDraft
) {
    val hasDestination: Boolean get() = webhook.urls.isNotEmpty() || mqtt.section.enabled

    fun differsFrom(saved: ScreenTimeDraft): Boolean {
        val interval = syncInterval.toIntOrNull() ?: saved.syncInterval.toIntOrNull()
        val hour = dayBoundaryHour.toIntOrNull() ?: saved.dayBoundaryHour.toIntOrNull()
        return interval != saved.syncInterval.toIntOrNull() ||
            hour != saved.dayBoundaryHour.toIntOrNull() ||
            useDayBoundary != saved.useDayBoundary ||
            webhook != saved.webhook ||
            mqtt.withPort().let { it.section to it.sharedBroker } != saved.mqtt.withPort().let { it.section to it.sharedBroker }
    }
}

object SettingsRules {
    const val MIN_INTERVAL_MINUTES = 15

    /** WorkManager refuses periodic work under 15 minutes, so neither do we. */
    fun intervalOrNull(text: String): Int? = text.toIntOrNull()?.takeIf { it >= MIN_INTERVAL_MINUTES }

    fun dayBoundaryHourOrNull(text: String): Int? = text.toIntOrNull()?.takeIf { it in 0..23 }

    fun isValidUrl(url: String): Boolean = url.isNotBlank() && url.startsWith("http")
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

    /** True for the messages the sync line paints red. */
    val isFailure: Boolean
        get() = this is SyncFailed || this is HealthConnectUnavailable || this is UsageAccessMissing ||
            this is PingFailed || this is PingFailedWith || this is PreviewFailed || this is ExportFailed
}
