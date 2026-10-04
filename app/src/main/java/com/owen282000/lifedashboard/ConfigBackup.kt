package com.owen282000.lifedashboard

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
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
 *
 * On import a key the file does not have leaves its setting on the device as it is, so an
 * absent option, broker or switch reads as null rather than as a default. A file from the
 * iPhone app has no Screen Time section and none of the Android-only options, and must not
 * reset them.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class ConfigBackup(
    /** Bumped only when the shape changes incompatibly; [CURRENT_VERSION] is what we write. */
    val version: Int = CURRENT_VERSION,
    /** "ios" in a file from the iPhone app. This app writes none, so absent means Android. */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val platform: String? = null,
    @SerialName("exported_at") val exportedAt: String? = null,
    @SerialName("app_version") val appVersion: String? = null,
    val health: SectionConfig = SectionConfig(),
    @SerialName("screen_time") val screenTime: SectionConfig = SectionConfig(),
    val mqtt: MqttConfig = MqttConfig(),
    val options: OptionsConfig = OptionsConfig()
) {
    companion object {
        const val CURRENT_VERSION = 1

        /** What the iPhone app writes in [platform]. */
        const val PLATFORM_IOS = "ios"

        /** The iPhone app's default MQTT base topic, the iPhone's sensors in Home Assistant. */
        const val IOS_DEFAULT_BASE_TOPIC = "lifedashboard-ios"

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

    /** True for a file written by the iPhone app. */
    val isFromIPhone: Boolean get() = platform == PLATFORM_IOS

    /**
     * What an import would replace, for the preview. The data type count is what the phone
     * ends up with, given the types enabled on it now: from an iPhone file that is not the
     * file's own count (see [ConfigBackupManager.dataTypesOnImport]).
     */
    fun summarise(currentDataTypes: Set<HealthDataType>): BackupSummary = BackupSummary(
        healthWebhooks = health.webhookUrls?.size,
        screenTimeWebhooks = screenTime.webhookUrls?.size,
        enabledDataTypes = options.enabledDataTypes?.let {
            ConfigBackupManager.dataTypesOnImport(it, currentDataTypes, isFromIPhone).size
        },
        brokers = listOfNotNull(mqtt.shared, mqtt.healthOwnBroker, mqtt.screenTimeOwnBroker).count { it.host.isNotBlank() },
        includesSecrets = containsSecrets()
    )

    /**
     * This file as it applies to this phone. A file from the iPhone app names the iPhone: its
     * phone name, and its default base topic, would put this phone's sensors on the iPhone's
     * in Home Assistant, so both are dropped and this phone keeps its own. The iPhone app does
     * the same with a file from here; a topic the user chose is taken as it is, as there.
     */
    fun forThisPhone(): ConfigBackup {
        if (!isFromIPhone) return this
        return copy(
            mqtt = if (hasIPhoneBaseTopic()) mqtt.copy(healthBaseTopic = null) else mqtt,
            options = options.copy(phoneName = null)
        )
    }

    /** What the import preview says beyond the counts: what this file leaves as it is. */
    fun importNotes(): List<ImportNote> = buildList {
        if (screenTime.webhookUrls == null) add(ImportNote.SCREEN_TIME_KEPT)
        if (isFromIPhone && options.enabledDataTypes != null) add(ImportNote.IPHONE_ANDROID_TYPES_KEPT)
        if (isFromIPhone && hasIPhoneBaseTopic()) add(ImportNote.IPHONE_BASE_TOPIC_KEPT)
        if (isFromIPhone && !options.phoneName.isNullOrBlank()) add(ImportNote.IPHONE_PHONE_NAME_KEPT)
    }

    private fun hasIPhoneBaseTopic() = mqtt.healthBaseTopic?.trim() == IOS_DEFAULT_BASE_TOPIC
}

/** The counts the import preview lists. Null where the file has none and the device keeps its own. */
data class BackupSummary(
    val healthWebhooks: Int?,
    val screenTimeWebhooks: Int?,
    val enabledDataTypes: Int?,
    val brokers: Int,
    val includesSecrets: Boolean
)

/** Something the import preview tells the user beyond a plain copy, as on iOS. */
enum class ImportNote {
    /** The file has no Screen Time section, which a file from the iPhone app never has. */
    SCREEN_TIME_KEPT,

    /** The data types the iPhone app does not have keep their state on this phone. */
    IPHONE_ANDROID_TYPES_KEPT,

    /** The iPhone's default topic is left out, so the two phones do not share sensors. */
    IPHONE_BASE_TOPIC_KEPT,

    /** The iPhone's name names the iPhone, so this phone keeps its own. */
    IPHONE_PHONE_NAME_KEPT
}

/** Webhook configuration of one section (Health Connect or Screen Time). */
@Serializable
data class SectionConfig(
    /** Null when the file has no such section, as an iPhone file has no Screen Time: the URLs stay. */
    @SerialName("webhook_urls") val webhookUrls: List<String>? = null,
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
        return webhookUrls.orEmpty().filter { url ->
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

/**
 * MQTT settings: the shared broker plus each section's switch, topic and optional own broker.
 * A key the file does not have leaves its setting alone: a file from the iPhone app has the
 * shared broker and the health keys only.
 */
@Serializable
data class MqttConfig(
    val shared: BrokerConfig? = null,
    @SerialName("health_enabled") val healthEnabled: Boolean? = null,
    @SerialName("health_use_shared") val healthUseShared: Boolean? = null,
    @SerialName("health_base_topic") val healthBaseTopic: String? = null,
    @SerialName("health_own_broker") val healthOwnBroker: BrokerConfig? = null,
    @SerialName("screen_time_enabled") val screenTimeEnabled: Boolean? = null,
    @SerialName("screen_time_use_shared") val screenTimeUseShared: Boolean? = null,
    @SerialName("screen_time_base_topic") val screenTimeBaseTopic: String? = null,
    @SerialName("screen_time_own_broker") val screenTimeOwnBroker: BrokerConfig? = null
) {
    fun containsSecrets(): Boolean =
        shared?.containsSecrets() == true ||
            healthOwnBroker?.containsSecrets() == true ||
            screenTimeOwnBroker?.containsSecrets() == true

    fun withoutSecrets(): MqttConfig = copy(
        shared = shared?.withoutSecrets(),
        healthOwnBroker = healthOwnBroker?.withoutSecrets(),
        screenTimeOwnBroker = screenTimeOwnBroker?.withoutSecrets()
    )

    /**
     * One section's settings as an import writes them: what the file has replaces the device's,
     * what it lacks stays as it is. Its own broker keeps credentials as [BrokerConfig.toBroker] says.
     */
    fun sectionOnImport(section: MqttSection, current: MqttSectionSettings, backupHasSecrets: Boolean): MqttSectionSettings {
        val health = section == MqttSection.HEALTH
        val ownBroker = if (health) healthOwnBroker else screenTimeOwnBroker
        val baseTopic = if (health) healthBaseTopic else screenTimeBaseTopic
        return MqttSectionSettings(
            enabled = (if (health) healthEnabled else screenTimeEnabled) ?: current.enabled,
            useSharedBroker = (if (health) healthUseShared else screenTimeUseShared) ?: current.useSharedBroker,
            ownBroker = ownBroker?.toBroker(current.ownBroker, backupHasSecrets) ?: current.ownBroker,
            baseTopic = baseTopic ?: current.baseTopic
        )
    }
}

/**
 * Everything else the user can toggle. Data types are stored by enum name so an export from an
 * older build still imports cleanly when new types are added; unknown names are dropped. A key
 * the file does not have leaves its setting alone: a file from the iPhone app has no full
 * payloads switch and no day boundary.
 */
/** The Screen Time app filter: a mode name (ALL, BLOCKLIST, ALLOWLIST) and package names. */
@Serializable
data class AppFilterConfig(
    @SerialName("mode") val mode: String,
    @SerialName("packages") val packages: List<String> = emptyList()
) {
    fun toFilter() = ScreenTimeAppFilter(AppFilterMode.from(mode), packages.toSet())

    companion object {
        fun from(filter: ScreenTimeAppFilter) = AppFilterConfig(filter.mode.name, filter.packages.sorted())
    }
}

@Serializable
data class OptionsConfig(
    @SerialName("enabled_data_types") val enabledDataTypes: List<String>? = null,
    @SerialName("include_daily_totals") val includeDailyTotals: Boolean? = null,
    /** Record metadata on every record (P2-7); absent in older backups and in the iPhone's. */
    @SerialName("include_record_metadata") val includeRecordMetadata: Boolean? = null,
    @SerialName("allow_http_webhooks") val allowHttpWebhooks: Boolean? = null,
    @SerialName("keep_full_payloads") val keepFullPayloads: Boolean? = null,
    @SerialName("screen_time_day_boundary_hour") val screenTimeDayBoundaryHour: Int? = null,
    @SerialName("screen_time_use_day_boundary") val screenTimeUseDayBoundary: Boolean? = null,
    /** Which apps Screen Time sends (issue #63); absent in older backups and in the iPhone's. */
    @SerialName("screen_time_app_filter") val screenTimeAppFilter: AppFilterConfig? = null,
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
