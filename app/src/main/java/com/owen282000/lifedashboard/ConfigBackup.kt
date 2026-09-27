package com.owen282000.lifedashboard

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * A portable snapshot of everything the user configured: webhook URLs and headers, signing
 * secrets, MQTT brokers, the enabled data types and the sync options.
 *
 * This exists because secrets are deliberately excluded from Android's cloud backup (they are
 * encrypted with a key that never leaves the device), so moving to a new phone would otherwise
 * mean re-entering every setting by hand.
 *
 * Sync state (last-sync watermarks, logs, statistics) is NOT part of a backup: it describes
 * this install's progress against Health Connect, and restoring it elsewhere would silently
 * skip records.
 */
@Serializable
data class ConfigBackup(
    /** Bumped only when the shape changes incompatibly; [CURRENT_VERSION] is what we write. */
    val version: Int = CURRENT_VERSION,
    @SerialName("exported_at") val exportedAt: String? = null,
    @SerialName("app_version") val appVersion: String? = null,
    val health: SectionConfig = SectionConfig(),
    @SerialName("screen_time") val screenTime: SectionConfig = SectionConfig(),
    val mqtt: MqttConfig = MqttConfig(),
    val options: OptionsConfig = OptionsConfig()
) {
    companion object {
        const val CURRENT_VERSION = 1

        /** Lenient so a file written by a newer build still imports what it understands. */
        val json = Json {
            prettyPrint = true
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        fun decode(text: String): ConfigBackup = json.decodeFromString(text)
    }

    fun encode(): String = json.encodeToString(this)

    /** True when anything in this backup could expose credentials if shared unprotected. */
    fun containsSecrets(): Boolean =
        health.containsSecrets() ||
            screenTime.containsSecrets() ||
            mqtt.containsSecrets()

    /** A copy with every credential removed, for sharing a setup without handing over access. */
    fun withoutSecrets(): ConfigBackup = copy(
        health = health.withoutSecrets(),
        screenTime = screenTime.withoutSecrets(),
        mqtt = mqtt.withoutSecrets()
    )

    /** Short human-readable lines describing what an import would replace. */
    fun summarise(): List<String> = buildList {
        add("Health webhooks: ${health.webhookUrls.size}")
        add("Screen time webhooks: ${screenTime.webhookUrls.size}")
        add("Enabled data types: ${options.enabledDataTypes.size}")
        val brokers = listOfNotNull(
            mqtt.shared.host.takeIf { it.isNotBlank() },
            mqtt.healthOwnBroker.host.takeIf { it.isNotBlank() },
            mqtt.screenTimeOwnBroker.host.takeIf { it.isNotBlank() }
        )
        add("MQTT brokers: ${brokers.size}")
        add(if (containsSecrets()) "Includes secrets" else "No secrets included")
    }
}

/** Webhook configuration of one section (Health Connect or Screen Time). */
@Serializable
data class SectionConfig(
    @SerialName("webhook_urls") val webhookUrls: List<String> = emptyList(),
    val headers: Map<String, String> = emptyMap(),
    @SerialName("signing_secret") val signingSecret: String? = null,
    @SerialName("sync_interval_minutes") val syncIntervalMinutes: Int? = null,
    /**
     * Schedule beyond the plain interval, all optional so a backup written before 1.14.0
     * restores exactly as it used to: absent fields leave the stored schedule alone.
     */
    @SerialName("sync_mode") val syncMode: String? = null,
    @SerialName("sync_times") val syncTimes: String? = null,
    @SerialName("sync_days") val syncDays: String? = null,
    @SerialName("quiet_from") val quietFrom: String? = null,
    @SerialName("quiet_to") val quietTo: String? = null,
    /**
     * The URLs QR pairing added, which get none of [headers] (see WebhookSupport.headersFor).
     * Not a secret, so it stays in an export without secrets. Absent in an older backup, which
     * reads as empty: that version sent the headers to every URL.
     */
    @SerialName("urls_without_headers") val urlsWithoutHeaders: List<String> = emptyList()
) {
    fun containsSecrets(): Boolean = !signingSecret.isNullOrBlank() || headers.isNotEmpty()

    fun withoutSecrets(): SectionConfig = copy(headers = emptyMap(), signingSecret = null)

    /**
     * Which of this backup's URLs get no custom headers once it is imported. Headers in the
     * backup were set for its own URLs, so its own list holds. A backup without headers keeps
     * the ones already on the device, which were set for the device's URLs: an imported URL
     * those did not go to before gets none of them now either.
     */
    fun urlsWithoutHeadersOnImport(
        deviceUrls: List<String>,
        deviceUrlsWithoutHeaders: Set<String>,
        deviceHasHeaders: Boolean
    ): Set<String> {
        val keepsDeviceHeaders = headers.isEmpty() && deviceHasHeaders
        return webhookUrls.filter { url ->
            url in urlsWithoutHeaders ||
                (keepsDeviceHeaders && (url !in deviceUrls || url in deviceUrlsWithoutHeaders))
        }.toSet()
    }
}

/** One broker's connection details. */
@Serializable
data class BrokerConfig(
    val host: String = "",
    val port: Int = 1883,
    @SerialName("use_tls") val useTls: Boolean = false,
    val username: String? = null,
    val password: String? = null
) {
    fun containsSecrets(): Boolean = !username.isNullOrBlank() || !password.isNullOrBlank()

    fun withoutSecrets(): BrokerConfig = copy(username = null, password = null)

    fun toBroker(): MqttBroker = MqttBroker(
        host = host,
        port = port,
        useTls = useTls,
        username = username?.takeIf { it.isNotBlank() },
        password = password?.takeIf { it.isNotBlank() }
    )

    /**
     * This broker as it is written on import. From a backup exported without secrets, the
     * username and password already on the device for the same host are kept, the way webhook
     * secrets are: that export left them out, it did not say there are none (F9 of P2-4).
     * Only for the same broker, meaning host, port and TLS: credentials set for one broker
     * never go to another, and never go out in plain text where they had TLS.
     */
    fun toBroker(current: MqttBroker, backupHasSecrets: Boolean): MqttBroker {
        val sameBroker = current.host.isNotBlank() && current.host.equals(host.trim(), ignoreCase = true) &&
            current.port == port && current.useTls == useTls
        val keep = !backupHasSecrets && sameBroker
        return if (keep) toBroker().copy(username = current.username, password = current.password) else toBroker()
    }

    companion object {
        fun from(broker: MqttBroker) = BrokerConfig(
            host = broker.host,
            port = broker.port,
            useTls = broker.useTls,
            username = broker.username,
            password = broker.password
        )
    }
}

/** MQTT settings: the shared broker plus each section's switch, topic and optional own broker. */
@Serializable
data class MqttConfig(
    val shared: BrokerConfig = BrokerConfig(),
    @SerialName("health_enabled") val healthEnabled: Boolean = false,
    @SerialName("health_use_shared") val healthUseShared: Boolean = true,
    @SerialName("health_base_topic") val healthBaseTopic: String = MqttSupport.DEFAULT_BASE_TOPIC,
    @SerialName("health_own_broker") val healthOwnBroker: BrokerConfig = BrokerConfig(),
    @SerialName("screen_time_enabled") val screenTimeEnabled: Boolean = false,
    @SerialName("screen_time_use_shared") val screenTimeUseShared: Boolean = true,
    @SerialName("screen_time_base_topic") val screenTimeBaseTopic: String = MqttSupport.DEFAULT_BASE_TOPIC,
    @SerialName("screen_time_own_broker") val screenTimeOwnBroker: BrokerConfig = BrokerConfig()
) {
    fun containsSecrets(): Boolean =
        shared.containsSecrets() ||
            healthOwnBroker.containsSecrets() ||
            screenTimeOwnBroker.containsSecrets()

    fun withoutSecrets(): MqttConfig = copy(
        shared = shared.withoutSecrets(),
        healthOwnBroker = healthOwnBroker.withoutSecrets(),
        screenTimeOwnBroker = screenTimeOwnBroker.withoutSecrets()
    )
}

/**
 * Everything else the user can toggle. Data types are stored by enum name so an export from an
 * older build still imports cleanly when new types are added; unknown names are dropped.
 */
@Serializable
data class OptionsConfig(
    @SerialName("enabled_data_types") val enabledDataTypes: List<String> = emptyList(),
    @SerialName("include_daily_totals") val includeDailyTotals: Boolean = true,
    @SerialName("allow_http_webhooks") val allowHttpWebhooks: Boolean = false,
    @SerialName("keep_full_payloads") val keepFullPayloads: Boolean = false,
    @SerialName("screen_time_day_boundary_hour") val screenTimeDayBoundaryHour: Int = 4,
    @SerialName("screen_time_use_day_boundary") val screenTimeUseDayBoundary: Boolean = true,
    @SerialName("failure_notification_threshold") val failureNotificationThreshold: Int? = null,
    /** Type name to resolution name, only for types not at raw; absent in older backups. */
    @SerialName("series_resolutions") val seriesResolutions: Map<String, String>? = null,
    /**
     * The phone's name for MQTT (1.20.0). Absent in a backup from before names existed, and
     * then left alone on import; an export writes an empty string for a phone without one.
     */
    @SerialName("phone_name") val phoneName: String? = null,
    /**
     * Receive (1.20.0): the switches and the source URL. All absent in an older backup, and
     * then left alone on import, like the resolutions; the ledger stays behind like the
     * watermarks. Types are the protocol keys (weight, blood_pressure), not enum names.
     */
    @SerialName("receive_enabled") val receiveEnabled: Boolean? = null,
    @SerialName("receive_types") val receiveTypes: List<String>? = null,
    @SerialName("receive_older_measurements") val receiveOlderMeasurements: Boolean? = null,
    /** An export writes an empty string when no source is chosen; absent means an older backup. */
    @SerialName("receive_source_url") val receiveSourceUrl: String? = null
)
