package com.owen282000.lifedashboard

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log

/**
 * Lets automation apps (Tasker, MacroDroid, ...) trigger a sync with an explicit intent:
 *
 *   adb shell am broadcast -n com.owen282000.lifedashboard/.SyncBroadcastReceiver \
 *     -a com.owen282000.lifedashboard.ACTION_SYNC
 *
 * The receiver only enqueues the app's own WorkManager jobs and reads no extras, so external
 * triggers cannot exfiltrate anything beyond what the user already configured.
 *
 * It stays exported without a permission on purpose: Tasker and MacroDroid cannot declare a
 * permission of ours, so requiring one would break the documented trigger. What any installed
 * app can still do is spam it, so a broadcast within [BroadcastRateLimit.MIN_GAP_MILLIS] of the
 * last accepted one is ignored: a flood becomes at most one sync a minute instead of a queue of
 * syncs that drains the battery and hammers the webhook.
 */
class SyncBroadcastReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_SYNC) return
        val now = SystemClock.elapsedRealtime()
        if (!BroadcastRateLimit.allows(now, lastAcceptedAt)) {
            Log.i(TAG, "Sync broadcast ignored: another one was accepted less than a minute ago")
            return
        }
        lastAcceptedAt = now
        SyncTrigger.enqueueImmediateSync(context)
    }

    companion object {
        const val ACTION_SYNC = "com.owen282000.lifedashboard.ACTION_SYNC"
        private const val TAG = "SyncBroadcastReceiver"

        /**
         * Elapsed realtime of the last accepted broadcast. In memory only: a flood keeps the
         * process alive, and a process that was gone has seen no flood to hold back.
         */
        @Volatile
        private var lastAcceptedAt: Long? = null
    }
}

/** The rate limit of [SyncBroadcastReceiver], apart so the JVM tests can reach it. */
object BroadcastRateLimit {
    const val MIN_GAP_MILLIS = 60_000L

    /** True when a broadcast at [now] may trigger a sync, given the last accepted one. */
    fun allows(now: Long, lastAcceptedAt: Long?): Boolean =
        lastAcceptedAt == null || now - lastAcceptedAt >= MIN_GAP_MILLIS
}
