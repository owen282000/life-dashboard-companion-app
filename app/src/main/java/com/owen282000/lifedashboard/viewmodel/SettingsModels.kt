package com.owen282000.lifedashboard.viewmodel

import com.owen282000.lifedashboard.BackfillFailure
import com.owen282000.lifedashboard.Destination
import com.owen282000.lifedashboard.Destinations
import com.owen282000.lifedashboard.HealthDataType
import com.owen282000.lifedashboard.InvalidHeaderException
import com.owen282000.lifedashboard.MqttBroker
import com.owen282000.lifedashboard.MqttSectionSettings
import com.owen282000.lifedashboard.MqttSupport
import com.owen282000.lifedashboard.QuietWindow
import com.owen282000.lifedashboard.ScreenTimeAppFilter
import com.owen282000.lifedashboard.SeriesResolution
import com.owen282000.lifedashboard.SyncMode
import com.owen282000.lifedashboard.SyncSchedule
import com.owen282000.lifedashboard.WebhookSupport
import com.owen282000.lifedashboard.WriteBackType
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.time.DayOfWeek
import java.time.LocalTime

/*
 * The settings of the two sync tabs as plain data, so the "is this valid" questions are pure
 * functions the view models, the screens and the tests share. Text fields keep their raw text here
 * (interval, port, day boundary hour) and are parsed on save.
 */

data class WebhookDraft(
    /** The section's destinations, each with its own secret and headers (P2-13). */
    val destinations: List<Destination> = emptyList(),
    /** The section's own secret, which a destination added by hand starts with. */
    val sectionSecret: String = ""
) {
    val urls: List<String> get() = destinations.map { it.url }

    /** The addresses that have a secret: only those can be the source of Receive, whose answers are verified with it. */
    val urlsWithSecret: List<String> get() = destinations.filter { !it.secret.isNullOrBlank() }.map { it.url }

    /** The secret a new destination starts with: the one the others (all but [except]) share, or the section's. */
    fun defaultSecret(except: String? = null): String? =
        Destinations.sharedSecret(destinations.filter { it.id != except }) ?: sectionSecret.takeIf { it.isNotBlank() }

    /**
     * The destination [id] with its address changed to [url]. On the host it was saved with
     * ([saved]) it keeps its headers and secret, also when it comes back there after a detour
     * while typing: a new scheme, port or path is the same receiver. On another host it starts
     * like a new destination, without headers and with the default secret, since those were
     * set for the old host. One that is not saved yet keeps what it has.
     */
    fun withEditedUrl(id: String, url: String, saved: Destination?): WebhookDraft = edit(id) { current ->
        val wasOnSavedHost = saved != null && Destinations.sameHost(current.url, saved.url)
        when {
            saved == null -> current.copy(url = url)
            Destinations.sameHost(url, saved.url) ->
                if (wasOnSavedHost) current.copy(url = url) else current.copy(url = url, headers = saved.headers, secret = saved.secret)
            wasOnSavedHost -> current.copy(url = url, headers = emptyMap(), secret = defaultSecret(except = id))
            else -> current.copy(url = url)
        }
    }

    /** [id] with the headers of [from] in place of its own. */
    fun withHeadersOf(id: String, from: String): WebhookDraft =
        destinations.firstOrNull { it.id == from }?.let { source -> edit(id) { it.copy(headers = source.headers) } } ?: this

    /** [id] with the secret of [from]. */
    fun withSecretOf(id: String, from: String): WebhookDraft =
        destinations.firstOrNull { it.id == from }?.let { source -> edit(id) { it.copy(secret = source.secret) } } ?: this

    fun without(id: String): WebhookDraft = copy(destinations = destinations.filter { it.id != id })

    /** [transform] applied to the destination [id]; the others stay as they are. */
    fun edit(id: String, transform: (Destination) -> Destination): WebhookDraft =
        copy(destinations = destinations.map { if (it.id == id) transform(it) else it })

    /** Every address without the spaces around it, as it is saved. */
    fun trimmed(): WebhookDraft = copy(destinations = destinations.map { it.copy(url = it.url.trim()) })

    /** [destination] in the place of the one with its id, or at the end when it is new. */
    fun withDestination(destination: Destination): WebhookDraft =
        if (destinations.any { it.id == destination.id }) edit(destination.id) { destination }
        else copy(destinations = destinations + destination)
}

/**
 * One destination while the Webhook screen edits it. The screen holds this and nothing is stored
 * until Save, because a half typed address must not reach the settings: [withUrl] on another host
 * drops the headers and the secret, which typing back to the saved host restores from [saved], and
 * only as long as nothing was written in between. Back throws it away.
 */
data class DestinationDraft(
    val id: String,
    /** The section's destinations when the screen opened, with this one's edits. */
    val webhook: WebhookDraft,
    /** What is stored under [id]; null while it is new. */
    val saved: Destination?
) {
    val destination: Destination get() = webhook.destinations.first { it.id == id }
    val isNew: Boolean get() = saved == null
    val url: String get() = destination.url
    val secret: String get() = destination.secret.orEmpty()
    val headers: Map<String, String> get() = destination.headers

    /** The section's other destinations: what headers and a secret can be copied from. */
    val others: List<Destination> get() = webhook.destinations.filter { it.id != id }

    /** What is wrong with the address as typed, for under the field; a saved address that was not changed passes. */
    val urlProblem: UiMessage?
        get() {
            val typed = url.trim()
            if (typed == saved?.url) return null
            return SettingsRules.urlProblem(typed) ?: UiMessage.DuplicateUrl.takeIf { others.any { it.url == typed } }
        }

    fun withUrl(url: String) = copy(webhook = webhook.withEditedUrl(id, url, saved))
    fun withSecret(secret: String) = copy(webhook = webhook.edit(id) { it.copy(secret = secret.ifEmpty { null }) })

    /** Check [SettingsRules.headerProblem] first; the name and value are stored trimmed. */
    fun withHeader(name: String, value: String) =
        copy(webhook = webhook.edit(id) { it.copy(headers = it.headers + (name.trim() to value.trim())) })
    fun withoutHeader(name: String) = copy(webhook = webhook.edit(id) { it.copy(headers = it.headers - name) })
    fun withHeadersOf(otherId: String) = copy(webhook = webhook.withHeadersOf(id, otherId))
    fun withSecretOf(otherId: String) = copy(webhook = webhook.withSecretOf(id, otherId))

    companion object {
        fun existing(webhook: WebhookDraft, id: String) =
            DestinationDraft(id, webhook, webhook.destinations.first { it.id == id })

        /** A new destination at [address], starting with the secret the others share and no headers. */
        fun new(webhook: WebhookDraft, address: String, newId: () -> String = Destinations::newId): DestinationDraft {
            val id = newId()
            return DestinationDraft(id, webhook.copy(destinations = webhook.destinations + Destination(id = id, url = address, secret = webhook.defaultSecret())), null)
        }
    }
}

/**
 * What a screen asks of a section, the same on both tabs. Each save takes what the screen holds in
 * its own draft, stores it or nothing, and returns the problem to show under the field or in a
 * banner; null means it was stored. See ui/ld/README.md.
 */
interface SectionActions {
    /** A draft of the destination [id], or of a new one at [address] when [id] is null or gone. */
    fun destinationDraft(id: String?, address: String? = null): DestinationDraft
    fun saveDestination(draft: DestinationDraft): UiMessage?

    /** Stores the list without [id] at once. An empty list is fine: types can be picked first. */
    fun removeDestination(id: String)

    /** One test ping to the destination [id] as it is stored. The outcome comes as a toast. */
    fun testDestination(id: String)

    /** The section's MQTT settings as they are stored now, for the MQTT screen to edit. */
    fun mqttDraft(): MqttDraft
    fun saveMqtt(mqtt: MqttDraft): UiMessage?

    /** MQTT off, and the address and login gone from the broker unless the other section publishes through it. */
    fun removeMqtt()

    /** Stores [schedule] at once when it differs; an interval that is no interval keeps the stored one. */
    fun setSchedule(schedule: ScheduleDraft)
    fun saveInterval(text: String): UiMessage?

    /** Runs again what the secure storage refused, see the state's `refused`. */
    fun retryRefused()
    fun dismissRefused()
}

data class MqttDraft(
    val section: MqttSectionSettings,
    val sharedBroker: MqttBroker,
    val portText: String
) {
    val activeBroker: MqttBroker get() = if (section.useSharedBroker) sharedBroker else section.ownBroker

    /**
     * Port lives in its own text field; fold it into whichever broker is active before saving. Text that is no port (empty, 0, above 65535) keeps the port the broker had.
     */
    fun withPort(): MqttDraft {
        val port = MqttSupport.portOrNull(portText)
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

    /** With the shared broker as it is stored now: the other tab may have changed it. */
    fun withStoredSharedBroker(stored: MqttBroker): MqttDraft = copy(
        sharedBroker = stored,
        portText = if (section.useSharedBroker) stored.port.toString() else portText
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
    val schedule: ScheduleDraft = ScheduleDraft(),
    /** Resolution per type; absent means [com.owen282000.lifedashboard.DEFAULT_RESOLUTION]. */
    val resolutions: Map<HealthDataType, SeriesResolution> = emptyMap()
) {
    val hasDestination: Boolean get() = webhook.urls.isNotEmpty() || mqtt.section.enabled
}

data class ScreenTimeDraft(
    val webhook: WebhookDraft,
    val dayBoundaryHour: String,
    val useDayBoundary: Boolean,
    val mqtt: MqttDraft,
    val schedule: ScheduleDraft = ScheduleDraft(),
    /** Which apps are sent (issue #63). */
    val appFilter: ScreenTimeAppFilter = ScreenTimeAppFilter.ALL
) {
    val hasDestination: Boolean get() = webhook.urls.isNotEmpty() || mqtt.section.enabled
}

/** A broker without the address and login; the port stays for one added later. */
internal fun MqttBroker.cleared(): MqttBroker = copy(host = "", username = null, password = null)

/** Whether WorkManager has to hear about [next]: the order of the times and doubles among them do not count. */
internal fun SyncSchedule.differsFrom(next: SyncSchedule): Boolean =
    copy(times = times.distinct().sorted()) != next.copy(times = next.times.distinct().sorted())

object SettingsRules {
    const val MIN_INTERVAL_MINUTES = SyncSchedule.MIN_INTERVAL_MINUTES

    /** WorkManager refuses periodic work under 15 minutes, so neither do we. */
    fun intervalOrNull(text: String): Int? = text.toIntOrNull()?.takeIf { it >= MIN_INTERVAL_MINUTES }

    fun dayBoundaryHourOrNull(text: String): Int? = text.toIntOrNull()?.takeIf { it in 0..23 }

    /**
     * An http or https URL with a host, parsed by OkHttp, which sends the payloads. A bare
     * "http://" or a typo like "htp:/host" used to be saved and then failed on every sync.
     */
    fun isValidUrl(url: String): Boolean = url.trim().toHttpUrlOrNull() != null

    /**
     * What is wrong with an address about to be added or saved, or null when it can be: not a
     * valid URL, or one with a credential in it (Destinations.credentialIn).
     */
    fun urlProblem(url: String): UiMessage? = when {
        !isValidUrl(url) -> UiMessage.InvalidUrl
        else -> Destinations.credentialIn(url)?.let { UiMessage.UrlHasCredentials(it.parameter) }
    }

    /**
     * The first problem of an address in [draft] that is not one of [savedUrls]: an address
     * that is new or edited. One saved before the check existed keeps working as it is. Then
     * an address listed twice, by an edit that made it equal to another.
     */
    fun destinationProblem(draft: WebhookDraft, savedUrls: List<String>): UiMessage? =
        draft.destinations.firstNotNullOfOrNull { if (it.url in savedUrls) null else urlProblem(it.url) }
            ?: UiMessage.DuplicateUrl.takeIf { draft.urls.map { it.trim() }.let { urls -> urls.size != urls.distinct().size } }

    /**
     * What is wrong with a header about to be added, or null when it can be: a name that is an
     * HTTP token and a value OkHttp sends, both after trimming, as the tabs save them. A name
     * with a space used to be saved, after which every delivery failed (see WebhookSupport).
     */
    fun headerProblem(name: String, value: String): UiMessage? = when {
        name.isBlank() || value.isBlank() -> UiMessage.HeaderNeedsNameAndValue
        !WebhookSupport.isValidHeaderName(name.trim()) -> UiMessage.InvalidHeaderName
        !WebhookSupport.isValidHeaderValue(value.trim()) -> UiMessage.InvalidHeaderValue
        else -> null
    }

    /**
     * What is wrong with the broker host of [mqtt], as the field shows it. A host is needed once
     * publishing is on; one that is typed is checked even while it is off, as the shared broker
     * may be in use by the other tab.
     */
    fun mqttHostProblem(mqtt: MqttDraft): UiMessage? = brokerHostProblem(mqtt.activeBroker.host, required = mqtt.section.enabled)

    /** What is wrong with the port of [mqtt]: checked whenever the host is, see [mqttHostProblem]. */
    fun mqttPortProblem(mqtt: MqttDraft): UiMessage? =
        if (mqtt.section.enabled || mqtt.activeBroker.host.isNotBlank()) brokerPortProblem(mqtt.portText) else null

    /** A broker host as the settings and the setup wizard check it; blank only counts when [required]. */
    fun brokerHostProblem(host: String, required: Boolean): UiMessage? = when {
        host.isBlank() -> if (required) UiMessage.MqttHostMissing else null
        !MqttSupport.isValidHost(host) -> UiMessage.MqttHostInvalid
        else -> null
    }

    fun brokerPortProblem(portText: String): UiMessage? =
        if (MqttSupport.portOrNull(portText) == null) UiMessage.MqttPortInvalid else null

    /** The first error the MQTT screen shows under a field. Its Save stops on it, also with publishing off. */
    fun mqttFieldProblem(mqtt: MqttDraft): UiMessage? = mqttHostProblem(mqtt) ?: mqttPortProblem(mqtt)
}

/** Health Connect availability on this device, as [hcAvailability] reads the SDK's status. */
enum class HcAvailability { AVAILABLE, NOT_INSTALLED, NEEDS_UPDATE, UNAVAILABLE }

/**
 * What a screen tells the user, as data. The composable turns these into localized strings;
 * the view models never touch resources, which keeps them testable on the JVM.
 */
sealed interface UiMessage {
    data object IntervalTooShort : UiMessage
    data object InvalidDayBoundaryHour : UiMessage
    data object InvalidUrl : UiMessage

    /** The address is in the section already; every payload would go to it twice. */
    data object DuplicateUrl : UiMessage

    /** Nothing was saved: the secure storage cannot keep secrets right now, see DestinationStore.canWrite. */
    data object SecretsNotReady : UiMessage

    /** The address holds a credential: [parameter] is the query parameter, or null for a user name or password before the host. */
    data class UrlHasCredentials(val parameter: String?) : UiMessage
    data object HeaderNeedsNameAndValue : UiMessage
    data object InvalidHeaderName : UiMessage
    data object InvalidHeaderValue : UiMessage

    /** A saved header that cannot be sent kept a delivery from going out; [inValue] when its value is the problem. */
    data class HeaderBlocksDelivery(val name: String, val inValue: Boolean) : UiMessage
    data object MqttHostMissing : UiMessage
    data object MqttHostInvalid : UiMessage
    data object MqttPortInvalid : UiMessage
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

    /** The backfill stopped on [failure]; the screen words it, see BackfillTexts. */
    data class BackfillFailed(val failure: BackfillFailure) : UiMessage
    data object BackfillAlreadyRunning : UiMessage
    data object BackfillNoRecords : UiMessage

    /** Receive (issue #62): the sync line when something was written, and the two things that stop a switch. */
    data class SyncedRecordsWritten(val count: Int, val written: Int) : UiMessage
    data object ReceiveNeedsIntegration : UiMessage

    /**
     * [inner], from a sync that reached [delivered] of [total] webhooks. It counts as delivered,
     * so the others are not queued; the line says so instead of reading as a full success.
     */
    data class PartlyDelivered(val inner: UiMessage, val delivered: Int, val total: Int) : UiMessage

    companion object {
        /** [message] as it stands, or wrapped when some of [total] webhooks missed the payload. */
        fun partly(message: UiMessage, missed: Int, total: Int): UiMessage =
            if (missed > 0 && total > missed) PartlyDelivered(message, total - missed, total) else message

        /** [error] as the header it names when a header kept the request from being built, else null. */
        fun headerProblem(error: Throwable?): UiMessage? =
            (error as? InvalidHeaderException)?.problem?.let { HeaderBlocksDelivery(it.shownName, it.inValue) }
    }
    data class OtherSourceWrites(val source: String, val type: WriteBackType) : UiMessage

    /** True for the messages the sync line paints red. */
    val isFailure: Boolean
        get() = this is SyncFailed || this is HealthConnectUnavailable || this is UsageAccessMissing ||
            this is PingFailed || this is PingFailedWith || this is PreviewFailed || this is ExportFailed ||
            this is PartlyDelivered || this is BackfillFailed || this is HeaderBlocksDelivery || this is SecretsNotReady
}
