package com.owen282000.lifedashboard

import android.content.Context

/** Last-sync status shared with the home screen widget. */
object SyncStatusStore {

    private const val PREFS_NAME = "life_dashboard_prefs"
    private const val KEY_LAST_SYNC = "widget_last_sync"
    private const val KEY_LAST_SUCCESS = "widget_last_success"
    private const val KEY_RECORDS_TODAY = "widget_records_today"
    private const val KEY_RECORDS_TODAY_DATE = "widget_records_today_date"

    data class Status(
        val lastSyncMillis: Long?,
        val lastSuccess: Boolean,
        val recordsToday: Int
    )

    /**
     * Records one sync outcome, app-wide (what the widget shows) and per [source] (what each
     * tab's dashboard shows), so Health Connect's "today" never counts screen time apps.
     */
    suspend fun record(context: Context, success: Boolean, records: Int, source: LogType) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val today = java.time.LocalDate.now().toString()
        val editor = prefs.edit()
        for (suffix in listOf("", "_" + source.name)) {
            var todayCount = prefs.getInt(KEY_RECORDS_TODAY + suffix, 0)
            if (prefs.getString(KEY_RECORDS_TODAY_DATE + suffix, null) != today) {
                todayCount = 0
            }
            if (success) {
                todayCount += records
            }
            editor
                .putInt(KEY_RECORDS_TODAY + suffix, todayCount)
                .putString(KEY_RECORDS_TODAY_DATE + suffix, today)
                .putLong(KEY_LAST_SYNC + suffix, System.currentTimeMillis())
                .putBoolean(KEY_LAST_SUCCESS + suffix, success)
        }
        editor.apply()

        SyncStatusWidget.updateAll(context)
    }

    /** App-wide status, or the status of one [source] when given. */
    fun read(context: Context, source: LogType? = null): Status {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val suffix = source?.let { "_" + it.name } ?: ""
        val lastSync = prefs.getLong(KEY_LAST_SYNC + suffix, -1).takeIf { it > 0 }
        var records = prefs.getInt(KEY_RECORDS_TODAY + suffix, 0)
        if (prefs.getString(KEY_RECORDS_TODAY_DATE + suffix, null) != java.time.LocalDate.now().toString()) {
            records = 0
        }
        return Status(
            lastSyncMillis = lastSync,
            lastSuccess = prefs.getBoolean(KEY_LAST_SUCCESS + suffix, true),
            recordsToday = records
        )
    }
}
