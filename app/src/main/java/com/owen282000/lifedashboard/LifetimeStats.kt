package com.owen282000.lifedashboard

import android.content.Context

/** Lifetime sync counters shown in the hidden Nerd Stats card on the About screen. */
object LifetimeStats {

    private const val PREFS_NAME = "life_dashboard_prefs"
    private const val KEY_DELIVERIES = "stats_total_deliveries"
    private const val KEY_RECORDS = "stats_lifetime_records"
    private const val KEY_LARGEST_PAYLOAD = "stats_largest_payload"
    private const val KEY_FIRST_SYNC = "stats_first_sync"

    data class Stats(
        val deliveries: Int,
        val records: Long,
        val largestPayloadBytes: Int,
        val firstSyncMillis: Long?
    )

    /** Counted per successful delivery (one webhook log entry per URL, or one MQTT publish). */
    fun recordDelivery(context: Context, records: Int, payloadBytes: Int, source: LogType) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val sourceKey = KEY_RECORDS + "_" + source.name
        val editor = prefs.edit()
            .putInt(KEY_DELIVERIES, prefs.getInt(KEY_DELIVERIES, 0) + 1)
            .putLong(KEY_RECORDS, prefs.getLong(KEY_RECORDS, 0) + records)
            .putLong(sourceKey, prefs.getLong(sourceKey, 0) + records)
        if (payloadBytes > prefs.getInt(KEY_LARGEST_PAYLOAD, 0)) {
            editor.putInt(KEY_LARGEST_PAYLOAD, payloadBytes)
        }
        if (!prefs.contains(KEY_FIRST_SYNC)) {
            editor.putLong(KEY_FIRST_SYNC, System.currentTimeMillis())
        }
        editor.apply()
    }

    /** App-wide stats, or with [source] the record total of that source alone. */
    fun read(context: Context, source: LogType? = null): Stats {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return Stats(
            deliveries = prefs.getInt(KEY_DELIVERIES, 0),
            records = prefs.getLong(source?.let { KEY_RECORDS + "_" + it.name } ?: KEY_RECORDS, 0),
            largestPayloadBytes = prefs.getInt(KEY_LARGEST_PAYLOAD, 0),
            firstSyncMillis = prefs.getLong(KEY_FIRST_SYNC, -1).takeIf { it > 0 }
        )
    }
}
