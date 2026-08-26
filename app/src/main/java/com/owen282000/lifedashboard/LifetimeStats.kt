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

    /** Counted per successful delivery (one webhook log entry per URL). */
    fun recordDelivery(context: Context, records: Int, payloadBytes: Int) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val editor = prefs.edit()
            .putInt(KEY_DELIVERIES, prefs.getInt(KEY_DELIVERIES, 0) + 1)
            .putLong(KEY_RECORDS, prefs.getLong(KEY_RECORDS, 0) + records)
        if (payloadBytes > prefs.getInt(KEY_LARGEST_PAYLOAD, 0)) {
            editor.putInt(KEY_LARGEST_PAYLOAD, payloadBytes)
        }
        if (!prefs.contains(KEY_FIRST_SYNC)) {
            editor.putLong(KEY_FIRST_SYNC, System.currentTimeMillis())
        }
        editor.apply()
    }

    fun read(context: Context): Stats {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return Stats(
            deliveries = prefs.getInt(KEY_DELIVERIES, 0),
            records = prefs.getLong(KEY_RECORDS, 0),
            largestPayloadBytes = prefs.getInt(KEY_LARGEST_PAYLOAD, 0),
            firstSyncMillis = prefs.getLong(KEY_FIRST_SYNC, -1).takeIf { it > 0 }
        )
    }
}
