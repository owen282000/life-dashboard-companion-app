package com.owen282000.lifedashboard

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.edit

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

    private const val KEY_PARTIAL_PREFIX = "sync_partial_streak_"

    private fun partialNotificationId(logType: LogType) = NOTIFICATION_ID + 30 + logType.ordinal

    /**
     * Both streaks for one delivery: the sync's own through [recordResult], and the partial one
     * for webhooks that missed what another webhook took. Such a delivery counts as done, so
     * nothing is queued for the webhook that missed it; after the same number of those in a row
     * as the failure threshold, a notification names its host. A delivery that reaches every
     * webhook clears it.
     */
    fun recordDelivery(context: Context, logType: LogType, result: Result<WebhookOutcome>) {
        recordResult(context, logType, result.isSuccess)
        val prefs = prefs(context)
        val key = KEY_PARTIAL_PREFIX + logType.name
        val missed = result.getOrNull()?.missedUrls.orEmpty()
        val before = prefs.getInt(key, 0)
        val streak = PartialDelivery.nextStreak(before, result.isSuccess, missed)
        if (streak == before) return
        prefs.edit { putInt(key, streak) }
        if (streak == 0) {
            NotificationManagerCompat.from(context).cancel(partialNotificationId(logType))
            return
        }

        if (!isEnabled(context)) return
        val threshold = getThreshold(context).coerceAtLeast(1)
        if (streak % threshold != 0) return
        if (androidx.core.content.ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        ensureChannel(context)
        val hosts = PartialDelivery.hosts(missed)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle(context.getString(R.string.partial_delivery_title, categoryName(logType)))
            .setContentText(context.resources.getQuantityString(R.plurals.partial_delivery_text, streak, streak, hosts))
            .setStyle(NotificationCompat.BigTextStyle().bigText(context.resources.getQuantityString(R.plurals.partial_delivery_text, streak, streak, hosts)))
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(partialNotificationId(logType), notification)
    }

    private fun categoryName(logType: LogType) = when (logType) {
        LogType.HEALTH_CONNECT -> "Health Connect"
        LogType.SCREEN_TIME -> "Screen Time"
    }

    private const val KEY_DROPPED_PREFIX = "outbox_dropped_"

    private fun droppedNotificationId(logType: LogType) = NOTIFICATION_ID + 20 + logType.ordinal

    /**
     * The outbox dropped [count] undelivered payloads, and their records with them. Lost data is
     * worse than a failing sync, so this does not wait for the threshold or the "Notify after
     * failed syncs" switch: it notifies at once, updating one notification quietly. A delivery
     * does not clear it, since the records stay lost, and the Logs tab soon rotates the rows
     * that name them: it stays until the user dismisses it, and counts up while it shows.
     */
    fun notifyOutboxDropped(context: Context, logType: LogType, count: Int) {
        val prefs = prefs(context)
        val showing = context.getSystemService(NotificationManager::class.java)
            .activeNotifications.any { it.id == droppedNotificationId(logType) }
        val total = (if (showing) prefs.getInt(KEY_DROPPED_PREFIX + logType.name, 0) else 0) + count
        prefs.edit { putInt(KEY_DROPPED_PREFIX + logType.name, total) }

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
            .setContentTitle(context.getString(R.string.outbox_dropped_title, categoryName))
            .setContentText(context.resources.getQuantityString(R.plurals.outbox_dropped_text, total, total))
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(droppedNotificationId(logType), notification)
    }

    /** Its own streak next to the two sync categories: a failing Receive must not hide behind a healthy webhook. */
    private const val RECEIVE_STREAK_KEY = KEY_STREAK_PREFIX + "RECEIVE"
    private const val RECEIVE_NOTIFICATION_ID = NOTIFICATION_ID + 10

    /**
     * The same streak rule for Receive (issue #62): a round counts as failed when the
     * response was rejected, the source URL did not answer, or a reading could not be
     * written for a reason the phone can fix. Nothing to receive is not a failure.
     */
    fun recordReceiveResult(context: Context, success: Boolean) {
        val prefs = prefs(context)
        if (success) {
            if (prefs.getInt(RECEIVE_STREAK_KEY, 0) > 0) {
                prefs.edit { putInt(RECEIVE_STREAK_KEY, 0) }
                NotificationManagerCompat.from(context).cancel(RECEIVE_NOTIFICATION_ID)
            }
            return
        }
        val streak = prefs.getInt(RECEIVE_STREAK_KEY, 0) + 1
        prefs.edit { putInt(RECEIVE_STREAK_KEY, streak) }

        if (!isEnabled(context)) return
        val threshold = getThreshold(context).coerceAtLeast(1)
        if (streak % threshold != 0) return
        if (androidx.core.content.ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        ensureChannel(context)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle(context.getString(R.string.receive_failing_title))
            .setContentText(context.resources.getQuantityString(R.plurals.receive_failing_text, streak, streak))
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(RECEIVE_NOTIFICATION_ID, notification)
    }

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
