package com.owen282000.lifedashboard

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class PreferencesManager(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Keystore-backed storage for secrets (webhook headers with auth tokens, HMAC secrets). */
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
        // Keystore can be briefly unavailable right after boot; fall back to plain
        // prefs rather than crash so background syncs keep working.
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    init {
        migrateSecretsToEncryptedStorage()
    }

    /** One-time migration of secrets that older versions kept in plain SharedPreferences. */
    private fun migrateSecretsToEncryptedStorage() {
        if (securePrefs === prefs) return  // Keystore unavailable, nothing to migrate into
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

    fun getMqttSettings(): MqttSettings = MqttSettings(
        enabled = prefs.getBoolean(KEY_MQTT_ENABLED, false),
        host = prefs.getString(KEY_MQTT_HOST, "") ?: "",
        port = prefs.getInt(KEY_MQTT_PORT, 1883),
        useTls = prefs.getBoolean(KEY_MQTT_TLS, false),
        username = securePrefs.getString(KEY_MQTT_USERNAME, null)?.takeIf { it.isNotBlank() },
        password = securePrefs.getString(KEY_MQTT_PASSWORD, null)?.takeIf { it.isNotBlank() },
        baseTopic = prefs.getString(KEY_MQTT_BASE_TOPIC, MqttSupport.DEFAULT_BASE_TOPIC)
            ?.takeIf { it.isNotBlank() } ?: MqttSupport.DEFAULT_BASE_TOPIC
    )

    fun setMqttSettings(settings: MqttSettings) {
        prefs.edit()
            .putBoolean(KEY_MQTT_ENABLED, settings.enabled)
            .putString(KEY_MQTT_HOST, settings.host.trim())
            .putInt(KEY_MQTT_PORT, settings.port)
            .putBoolean(KEY_MQTT_TLS, settings.useTls)
            .putString(KEY_MQTT_BASE_TOPIC, settings.baseTopic.trim())
            .apply()
        securePrefs.edit()
            .putString(KEY_MQTT_USERNAME, settings.username ?: "")
            .putString(KEY_MQTT_PASSWORD, settings.password ?: "")
            .apply()
    }

    fun getLastMqttStatus(): String? = prefs.getString(KEY_MQTT_LAST_STATUS, null)

    fun setLastMqttStatus(status: String) {
        prefs.edit().putString(KEY_MQTT_LAST_STATUS, status).apply()
    }

    companion object {
        private const val PREFS_NAME = "life_dashboard_prefs"
        private const val SECURE_PREFS_NAME = "life_dashboard_secure_prefs"

        // MQTT keys (username/password live in securePrefs)
        private const val KEY_INCLUDE_DAILY_TOTALS = "include_daily_totals"
        private const val KEY_MQTT_ENABLED = "mqtt_enabled"
        private const val KEY_MQTT_HOST = "mqtt_host"
        private const val KEY_MQTT_PORT = "mqtt_port"
        private const val KEY_MQTT_TLS = "mqtt_tls"
        private const val KEY_MQTT_USERNAME = "mqtt_username"
        private const val KEY_MQTT_PASSWORD = "mqtt_password"
        private const val KEY_MQTT_BASE_TOPIC = "mqtt_base_topic"
        private const val KEY_MQTT_LAST_STATUS = "mqtt_last_status"

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
        private const val KEY_WEBHOOK_LOGS = "webhook_logs"

        // Defaults
        private const val DEFAULT_SYNC_INTERVAL_MINUTES = 60
        private const val DEFAULT_DAY_BOUNDARY_HOUR = 4
        private const val MAX_LOGS = 100
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

    fun getWebhookLogs(filterType: LogType? = null): List<WebhookLog> {
        val logsJson = prefs.getString(KEY_WEBHOOK_LOGS, null) ?: return emptyList()
        return try {
            val allLogs = Json.decodeFromString<List<WebhookLog>>(logsJson)
            if (filterType != null) {
                allLogs.filter { it.logType == filterType.name }
            } else {
                allLogs
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun addWebhookLog(log: WebhookLog) {
        val currentLogs = getWebhookLogs().toMutableList()
        currentLogs.add(0, log) // Add to beginning

        // Keep only the most recent MAX_LOGS entries
        val trimmedLogs = currentLogs.take(MAX_LOGS)

        val logsJson = Json.encodeToString(trimmedLogs)
        prefs.edit().putString(KEY_WEBHOOK_LOGS, logsJson).apply()
    }

    fun clearWebhookLogs(filterType: LogType? = null) {
        if (filterType == null) {
            prefs.edit().remove(KEY_WEBHOOK_LOGS).apply()
        } else {
            val currentLogs = getWebhookLogs().toMutableList()
            val filteredLogs = currentLogs.filter { it.logType != filterType.name }
            val logsJson = Json.encodeToString(filteredLogs)
            prefs.edit().putString(KEY_WEBHOOK_LOGS, logsJson).apply()
        }
    }
}
