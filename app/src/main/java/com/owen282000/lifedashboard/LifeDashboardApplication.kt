package com.owen282000.lifedashboard

import android.app.Application

class LifeDashboardApplication : Application() {

    /** The one PreferencesManager for the process; screens and view models share it. */
    val preferencesManager: PreferencesManager by lazy { PreferencesManager(this) }

    override fun onCreate() {
        super.onCreate()

        // Both sources schedule themselves from their stored schedule: a plain interval stays
        // periodic work, a schedule with times, weekdays or quiet hours becomes self-repeating
        // one-time work. See SyncScheduler.
        SyncScheduler.rescheduleAll(this)
    }

    fun scheduleHealthSyncWork() = SyncScheduler.reschedule(this, LogType.HEALTH_CONNECT)

    fun scheduleScreenTimeSyncWork() = SyncScheduler.reschedule(this, LogType.SCREEN_TIME)
}

/** The process-wide [PreferencesManager], or a fresh one when not running inside the app (tests, previews). */
fun android.content.Context.appPreferences(): PreferencesManager =
    (applicationContext as? LifeDashboardApplication)?.preferencesManager ?: PreferencesManager(this)
