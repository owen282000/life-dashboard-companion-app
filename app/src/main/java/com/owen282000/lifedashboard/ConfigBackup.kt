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
    @SerialName("sync_interval_minutes") val syncIntervalMinutes: Int? = null
) {
    fun containsSecrets(): Boolean = !signingSecret.isNullOrBlank() || headers.isNotEmpty()

    fun withoutSecrets(): SectionConfig = copy(headers = emptyMap(), signingSecret = null)
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
    @SerialName("failure_notification_threshold") val failureNotificationThreshold: Int? = null
)
