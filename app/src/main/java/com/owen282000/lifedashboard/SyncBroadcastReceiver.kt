package com.owen282000.lifedashboard

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Lets automation apps (Tasker, MacroDroid, ...) trigger a sync with an explicit intent:
 *
 *   adb shell am broadcast -n com.owen282000.lifedashboard/.SyncBroadcastReceiver \
 *     -a com.owen282000.lifedashboard.ACTION_SYNC
 *
 * The receiver only enqueues the app's own WorkManager jobs, so external triggers cannot
 * exfiltrate anything beyond what the user already configured.
 */
class SyncBroadcastReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_SYNC) {
            SyncTrigger.enqueueImmediateSync(context)
        }
    }

    companion object {
        const val ACTION_SYNC = "com.owen282000.lifedashboard.ACTION_SYNC"
    }
}
