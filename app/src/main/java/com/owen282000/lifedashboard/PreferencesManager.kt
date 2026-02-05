package com.owen282000.lifedashboard

import android.content.Context
import android.content.SharedPreferences
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class PreferencesManager(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val PREFS_NAME = "life_dashboard_prefs"

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
