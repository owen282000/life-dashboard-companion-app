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

    suspend fun record(context: Context, success: Boolean, records: Int) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val today = java.time.LocalDate.now().toString()

        var todayCount = prefs.getInt(KEY_RECORDS_TODAY, 0)
        if (prefs.getString(KEY_RECORDS_TODAY_DATE, null) != today) {
            todayCount = 0
        }
        if (success) {
            todayCount += records
        }

        prefs.edit()
            .putInt(KEY_RECORDS_TODAY, todayCount)
            .putString(KEY_RECORDS_TODAY_DATE, today)
            .putLong(KEY_LAST_SYNC, System.currentTimeMillis())
            .putBoolean(KEY_LAST_SUCCESS, success)
            .apply()

        SyncStatusWidget.updateAll(context)
    }

    fun read(context: Context): Status {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val lastSync = prefs.getLong(KEY_LAST_SYNC, -1).takeIf { it > 0 }
        var records = prefs.getInt(KEY_RECORDS_TODAY, 0)
        if (prefs.getString(KEY_RECORDS_TODAY_DATE, null) != java.time.LocalDate.now().toString()) {
            records = 0
        }
        return Status(
            lastSyncMillis = lastSync,
            lastSuccess = prefs.getBoolean(KEY_LAST_SUCCESS, true),
            recordsToday = records
        )
    }
}
