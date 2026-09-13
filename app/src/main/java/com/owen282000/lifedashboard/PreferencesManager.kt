package com.owen282000.lifedashboard

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class PreferencesManager(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Keystore-backed storage for secrets (webhook headers with auth tokens, HMAC secrets,
     * MQTT credentials).
     *
     * The keystore can be briefly unavailable right after boot, before the user has unlocked
     * the device for the first time. Falling back to plain SharedPreferences there would keep
     * background syncs working, but it would silently write secrets in cleartext into a file
     * that is eligible for cloud backup. Instead the fallback is [InMemoryPrefs]: reads return
     * nothing and writes are dropped, so a sync during the outage fails loudly (missing auth)
     * rather than quietly downgrading the user's security.
     *
     * [secretsUnavailable] reports this state so the UI can explain it.
     */
    private val securePrefs: SharedPreferences = try {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            SECURE_PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (e: Exception) {
        InMemoryPrefs()
    }

    /**
     * True when encrypted storage could not be opened, so secrets cannot be read or saved in
     * this process. Transient: it normally resolves once the device has been unlocked.
     */
    val secretsUnavailable: Boolean get() = securePrefs is InMemoryPrefs

    private val logStore = WebhookLogStore(context)

    init {
        migrateSecretsToEncryptedStorage()
        logStore.migrateFromLegacyPrefs(prefs)
    }

    /** One-time migration of secrets that older versions kept in plain SharedPreferences. */
    private fun migrateSecretsToEncryptedStorage() {
        if (secretsUnavailable) return  // Keystore unavailable, nothing to migrate into
        val secretKeys = listOf(
            KEY_HEALTH_WEBHOOK_HEADERS, KEY_SCREENTIME_WEBHOOK_HEADERS,
            KEY_HEALTH_WEBHOOK_SECRET, KEY_SCREENTIME_WEBHOOK_SECRET
        )
        for (key in secretKeys) {
            val plainValue = prefs.getString(key, null) ?: continue
            if (securePrefs.getString(key, null) == null) {
                securePrefs.edit().putString(key, plainValue).apply()
            }
            prefs.edit().remove(key).apply()
        }
    }

    // ==================== MQTT ====================
    // One shared broker connection (the original mqtt_* keys, so existing setups keep working)
    // that both sections use by default; each section has its own switch, base topic and, when
    // it opts out of the shared connection, its own broker (issue #52).

    fun getSharedMqttBroker(): MqttBroker = readBroker(SHARED_MQTT_PREFIX)

    fun setSharedMqttBroker(broker: MqttBroker) = writeBroker(SHARED_MQTT_PREFIX, broker)

    fun getMqttSection(section: MqttSection): MqttSectionSettings = MqttSectionSettings(
        enabled = prefs.getBoolean(section.enabledKey, false),
        useSharedBroker = prefs.getBoolean(section.prefix + "use_shared", true),
        ownBroker = readBroker(section.prefix),
        baseTopic = prefs.getString(section.baseTopicKey, MqttSupport.DEFAULT_BASE_TOPIC)
            ?.takeIf { it.isNotBlank() } ?: MqttSupport.DEFAULT_BASE_TOPIC
    )

    fun setMqttSection(section: MqttSection, settings: MqttSectionSettings) {
        prefs.edit()
            .putBoolean(section.enabledKey, settings.enabled)
            .putBoolean(section.prefix + "use_shared", settings.useSharedBroker)
            .putString(section.baseTopicKey, settings.baseTopic.trim())
            .apply()
        writeBroker(section.prefix, settings.ownBroker)
    }

    /** What the publisher of [section] connects to: its own switch and topic plus the broker it uses. */
    fun resolvedMqttSettings(section: MqttSection): MqttSettings {
        val settings = getMqttSection(section)
        val broker = if (settings.useSharedBroker) getSharedMqttBroker() else settings.ownBroker
        return MqttSettings(
            enabled = settings.enabled,
            host = broker.host,
            port = broker.port,
            useTls = broker.useTls,
            username = broker.username,
            password = broker.password,
            baseTopic = settings.baseTopic
        )
    }

    fun getLastMqttStatus(section: MqttSection): String? = prefs.getString(section.statusKey, null)

    fun setLastMqttStatus(section: MqttSection, status: String) {
        prefs.edit().putString(section.statusKey, status).apply()
    }

    private fun readBroker(prefix: String): MqttBroker = MqttBroker(
        host = prefs.getString(prefix + "host", "") ?: "",
        port = prefs.getInt(prefix + "port", 1883),
        useTls = prefs.getBoolean(prefix + "tls", false),
        username = securePrefs.getString(prefix + "username", null)?.takeIf { it.isNotBlank() },
        password = securePrefs.getString(prefix + "password", null)?.takeIf { it.isNotBlank() }
    )

    private fun writeBroker(prefix: String, broker: MqttBroker) {
        prefs.edit()
            .putString(prefix + "host", broker.host.trim())
            .putInt(prefix + "port", broker.port)
            .putBoolean(prefix + "tls", broker.useTls)
            .apply()
        securePrefs.edit()
            .putString(prefix + "username", broker.username ?: "")
            .putString(prefix + "password", broker.password ?: "")
            .apply()
    }

    companion object {
        private const val PREFS_NAME = "life_dashboard_prefs"
        private const val SECURE_PREFS_NAME = "life_dashboard_secure_prefs"

        private const val KEY_INCLUDE_DAILY_TOTALS = "include_daily_totals"
        private const val KEY_ALLOW_HTTP_WEBHOOKS = "allow_http_webhooks"
        /** Shared MQTT broker keys: mqtt_host, mqtt_port, mqtt_tls, mqtt_username, mqtt_password (securePrefs). */
        private const val SHARED_MQTT_PREFIX = "mqtt_"

        // Health Connect keys
        private const val KEY_HEALTH_LAST_SYNC_TS_PREFIX = "health_last_sync_ts_"
        private const val KEY_HEALTH_SYNC_INTERVAL_MINUTES = "health_sync_interval_minutes"
        private const val KEY_HEALTH_WEBHOOK_URLS = "health_webhook_urls"
        private const val KEY_HEALTH_ENABLED_DATA_TYPES = "health_enabled_data_types"

        // Screen Time keys
        private const val KEY_SCREENTIME_LAST_SYNC_TS = "screentime_last_sync_ts"
        private const val KEY_SCREENTIME_SYNC_INTERVAL_MINUTES = "screentime_sync_interval_minutes"
        private const val KEY_SCREENTIME_WEBHOOK_URLS = "screentime_webhook_urls"
        private const val KEY_SCREENTIME_DAY_BOUNDARY_HOUR = "screentime_day_boundary_hour"
        private const val KEY_SCREENTIME_USE_DAY_BOUNDARY = "screentime_use_day_boundary"

        // Webhook header keys
        private const val KEY_HEALTH_WEBHOOK_HEADERS = "health_webhook_headers"
        private const val KEY_SCREENTIME_WEBHOOK_HEADERS = "screentime_webhook_headers"
        private const val KEY_HEALTH_WEBHOOK_SECRET = "health_webhook_secret"
        private const val KEY_SCREENTIME_WEBHOOK_SECRET = "screentime_webhook_secret"

        // Shared keys
        private const val KEY_KEEP_FULL_PAYLOADS = "keep_full_payloads"

        // Defaults
        private const val DEFAULT_SYNC_INTERVAL_MINUTES = 60
        private const val DEFAULT_DAY_BOUNDARY_HOUR = 4
    }

    // ==================== Health Connect Settings ====================

    fun getHealthSyncIntervalMinutes(): Int {
        return prefs.getInt(KEY_HEALTH_SYNC_INTERVAL_MINUTES, DEFAULT_SYNC_INTERVAL_MINUTES)
    }

    fun setHealthSyncIntervalMinutes(minutes: Int) {
        prefs.edit().putInt(KEY_HEALTH_SYNC_INTERVAL_MINUTES, minutes).apply()
    }

    fun getHealthWebhookUrls(): List<String> {
        val urlsString = prefs.getString(KEY_HEALTH_WEBHOOK_URLS, "") ?: ""
        return if (urlsString.isEmpty()) emptyList() else urlsString.split(",")
    }

    fun setHealthWebhookUrls(urls: List<String>) {
        val urlsString = urls.joinToString(",")
        prefs.edit().putString(KEY_HEALTH_WEBHOOK_URLS, urlsString).apply()
    }

    fun getHealthEnabledDataTypes(): Set<HealthDataType> {
        val typesString = prefs.getString(KEY_HEALTH_ENABLED_DATA_TYPES, "") ?: ""
        return if (typesString.isEmpty()) {
            emptySet()
        } else {
            typesString.split(",").mapNotNull {
                try { HealthDataType.valueOf(it) } catch (e: Exception) { null }
            }.toSet()
        }
    }

    fun setHealthEnabledDataTypes(types: Set<HealthDataType>) {
        val typesString = types.joinToString(",") { it.name }
        prefs.edit().putString(KEY_HEALTH_ENABLED_DATA_TYPES, typesString).apply()
    }

    fun getHealthLastSyncTimestamp(type: HealthDataType): Long? {
        val timestamp = prefs.getLong(KEY_HEALTH_LAST_SYNC_TS_PREFIX + type.name, -1)
        return if (timestamp == -1L) null else timestamp
    }

    fun setHealthLastSyncTimestamp(type: HealthDataType, timestamp: Long) {
        prefs.edit().putLong(KEY_HEALTH_LAST_SYNC_TS_PREFIX + type.name, timestamp).apply()
    }

    fun getHealthWebhookHeaders(): Map<String, String> {
        val headersJson = securePrefs.getString(KEY_HEALTH_WEBHOOK_HEADERS, null) ?: return emptyMap()
        return try {
            Json.decodeFromString<Map<String, String>>(headersJson)
        } catch (e: Exception) {
            emptyMap()
        }
    }

    fun setHealthWebhookHeaders(headers: Map<String, String>) {
        val headersJson = Json.encodeToString(headers)
        securePrefs.edit().putString(KEY_HEALTH_WEBHOOK_HEADERS, headersJson).apply()
    }

    /** Daily deduplicated totals in the payload (aggregate API merges phone + watch). */
    fun includeDailyTotals(): Boolean = prefs.getBoolean(KEY_INCLUDE_DAILY_TOTALS, true)

    /**
     * Plain http:// webhook URLs are refused unless the user opts in, for endpoints only
     * reachable over a private LAN or VPN (issue #51). Applies to both webhook sections.
     */
    fun allowHttpWebhooks(): Boolean = prefs.getBoolean(KEY_ALLOW_HTTP_WEBHOOKS, false)

    fun setAllowHttpWebhooks(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ALLOW_HTTP_WEBHOOKS, enabled).apply()
    }

    fun setIncludeDailyTotals(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_INCLUDE_DAILY_TOTALS, enabled).apply()
    }

    fun getHealthWebhookSecret(): String? {
        return securePrefs.getString(KEY_HEALTH_WEBHOOK_SECRET, null)?.takeIf { it.isNotBlank() }
    }

    fun setHealthWebhookSecret(secret: String?) {
        if (secret.isNullOrBlank()) {
            securePrefs.edit().remove(KEY_HEALTH_WEBHOOK_SECRET).apply()
        } else {
            securePrefs.edit().putString(KEY_HEALTH_WEBHOOK_SECRET, secret).apply()
        }
    }

    // ==================== Screen Time Settings ====================

    fun getScreenTimeSyncIntervalMinutes(): Int {
        return prefs.getInt(KEY_SCREENTIME_SYNC_INTERVAL_MINUTES, DEFAULT_SYNC_INTERVAL_MINUTES)
    }

    fun setScreenTimeSyncIntervalMinutes(minutes: Int) {
        prefs.edit().putInt(KEY_SCREENTIME_SYNC_INTERVAL_MINUTES, minutes).apply()
    }

    fun getScreenTimeWebhookUrls(): List<String> {
        val urlsString = prefs.getString(KEY_SCREENTIME_WEBHOOK_URLS, "") ?: ""
        return if (urlsString.isEmpty()) emptyList() else urlsString.split(",")
    }

    fun setScreenTimeWebhookUrls(urls: List<String>) {
        val urlsString = urls.joinToString(",")
        prefs.edit().putString(KEY_SCREENTIME_WEBHOOK_URLS, urlsString).apply()
    }

    fun getScreenTimeWebhookHeaders(): Map<String, String> {
        val headersJson = securePrefs.getString(KEY_SCREENTIME_WEBHOOK_HEADERS, null) ?: return emptyMap()
        return try {
            Json.decodeFromString<Map<String, String>>(headersJson)
        } catch (e: Exception) {
            emptyMap()
        }
    }

    fun setScreenTimeWebhookHeaders(headers: Map<String, String>) {
        val headersJson = Json.encodeToString(headers)
        securePrefs.edit().putString(KEY_SCREENTIME_WEBHOOK_HEADERS, headersJson).apply()
    }

    fun getScreenTimeWebhookSecret(): String? {
        return securePrefs.getString(KEY_SCREENTIME_WEBHOOK_SECRET, null)?.takeIf { it.isNotBlank() }
    }

    fun setScreenTimeWebhookSecret(secret: String?) {
        if (secret.isNullOrBlank()) {
            securePrefs.edit().remove(KEY_SCREENTIME_WEBHOOK_SECRET).apply()
        } else {
            securePrefs.edit().putString(KEY_SCREENTIME_WEBHOOK_SECRET, secret).apply()
        }
    }

    fun getScreenTimeLastSyncTimestamp(): Long? {
        val timestamp = prefs.getLong(KEY_SCREENTIME_LAST_SYNC_TS, -1)
        return if (timestamp == -1L) null else timestamp
    }

    fun setScreenTimeLastSyncTimestamp(timestamp: Long) {
        prefs.edit().putLong(KEY_SCREENTIME_LAST_SYNC_TS, timestamp).apply()
    }

    fun getScreenTimeDayBoundaryHour(): Int {
        return prefs.getInt(KEY_SCREENTIME_DAY_BOUNDARY_HOUR, DEFAULT_DAY_BOUNDARY_HOUR)
    }

    fun setScreenTimeDayBoundaryHour(hour: Int) {
        prefs.edit().putInt(KEY_SCREENTIME_DAY_BOUNDARY_HOUR, hour.coerceIn(0, 23)).apply()
    }

    fun useScreenTimeDayBoundary(): Boolean {
        return prefs.getBoolean(KEY_SCREENTIME_USE_DAY_BOUNDARY, true)
    }

    fun setUseScreenTimeDayBoundary(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_SCREENTIME_USE_DAY_BOUNDARY, enabled).apply()
    }

    // ==================== Webhook Logs (Shared) ====================
    // Backed by WebhookLogStore: metadata in its own prefs file, payloads as separate files
    // capped by total bytes. Both are excluded from backup (res/xml/backup_rules.xml).

    fun getWebhookLogs(filterType: LogType? = null): List<WebhookLog> =
        logStore.getAll(filterType)

    fun addWebhookLog(log: WebhookLog) {
        logStore.add(log, keepFullPayloads = keepFullPayloads())
    }

    fun clearWebhookLogs(filterType: LogType? = null) {
        logStore.clear(filterType)
    }

    /** Whether raw payloads are kept in full; off by default, they are raw health data. */
    fun keepFullPayloads(): Boolean = prefs.getBoolean(KEY_KEEP_FULL_PAYLOADS, false)

    fun setKeepFullPayloads(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_KEEP_FULL_PAYLOADS, enabled).apply()
    }
}
