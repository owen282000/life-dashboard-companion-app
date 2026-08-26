package com.owen282000.lifedashboard

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/**
 * Posts a local notification when syncs keep failing, so silent background problems
 * surface without the user having to open the app. Mirrors the iOS companion app.
 */
object SyncFailureNotifier {

    private const val CHANNEL_ID = "sync_failures"
    private const val NOTIFICATION_ID = 4001
    private const val KEY_STREAK_PREFIX = "sync_failure_streak_"
    private const val KEY_NOTIFICATIONS_ENABLED = "failure_notifications_enabled"
    private const val KEY_NOTIFICATION_THRESHOLD = "failure_notification_threshold"
    private const val PREFS_NAME = "life_dashboard_prefs"
    const val DEFAULT_THRESHOLD = 3

    fun isEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_NOTIFICATIONS_ENABLED, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_NOTIFICATIONS_ENABLED, enabled).apply()
    }

    fun getThreshold(context: Context): Int =
        prefs(context).getInt(KEY_NOTIFICATION_THRESHOLD, DEFAULT_THRESHOLD)

    fun setThreshold(context: Context, threshold: Int) {
        prefs(context).edit().putInt(KEY_NOTIFICATION_THRESHOLD, threshold.coerceIn(1, 100)).apply()
    }

    /**
     * Tracks the failure streak per sync category and notifies once the configured
     * threshold (and every multiple of it) is reached. A success clears the streak
     * and any delivered notification for that category.
     */
    fun recordResult(context: Context, logType: LogType, success: Boolean) {
        val prefs = prefs(context)
        val key = KEY_STREAK_PREFIX + logType.name

        if (success) {
            if (prefs.getInt(key, 0) > 0) {
                prefs.edit().putInt(key, 0).apply()
                NotificationManagerCompat.from(context).cancel(notificationId(logType))
            }
            return
        }

        val streak = prefs.getInt(key, 0) + 1
        prefs.edit().putInt(key, streak).apply()

        if (!isEnabled(context)) return
        val threshold = getThreshold(context).coerceAtLeast(1)
        if (streak % threshold != 0) return
        // Inline permission check in the form lint recognizes (granted implicitly below API 33)
        if (androidx.core.content.ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        ensureChannel(context)

        val categoryName = when (logType) {
            LogType.HEALTH_CONNECT -> "Health Connect"
            LogType.SCREEN_TIME -> "Screen Time"
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("$categoryName sync is failing")
            .setContentText("$streak syncs in a row failed. Check the webhook logs for details.")
            .setAutoCancel(true)
            .build()

        NotificationManagerCompat.from(context).notify(notificationId(logType), notification)
    }

    private fun notificationId(logType: LogType) = NOTIFICATION_ID + logType.ordinal

    private fun ensureChannel(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Sync failures",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Alerts when webhook syncs keep failing"
        }
        manager.createNotificationChannel(channel)
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
