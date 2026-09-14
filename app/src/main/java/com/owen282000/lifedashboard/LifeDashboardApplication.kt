package com.owen282000.lifedashboard

import android.app.Application
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

class LifeDashboardApplication : Application() {

    /** The one PreferencesManager for the process; screens and view models share it. */
    val preferencesManager: PreferencesManager by lazy { PreferencesManager(this) }

    override fun onCreate() {
        super.onCreate()

        // Schedule periodic sync work for both Health Connect and Screen Time
        scheduleHealthSyncWork()
        scheduleScreenTimeSyncWork()
    }

    fun scheduleHealthSyncWork() {
        val syncIntervalMinutes = preferencesManager.getHealthSyncIntervalMinutes()

        val syncWorkRequest = PeriodicWorkRequestBuilder<HealthSyncWorker>(
            repeatInterval = syncIntervalMinutes.toLong(),
            repeatIntervalTimeUnit = TimeUnit.MINUTES
        ).build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            HealthSyncWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            syncWorkRequest
        )
    }

    fun scheduleScreenTimeSyncWork() {
        val syncIntervalMinutes = preferencesManager.getScreenTimeSyncIntervalMinutes()

        val syncWorkRequest = PeriodicWorkRequestBuilder<ScreenTimeSyncWorker>(
            repeatInterval = syncIntervalMinutes.toLong(),
            repeatIntervalTimeUnit = TimeUnit.MINUTES
        ).build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            ScreenTimeSyncWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            syncWorkRequest
        )
    }
}

/** The process-wide [PreferencesManager], or a fresh one when not running inside the app (tests, previews). */
fun android.content.Context.appPreferences(): PreferencesManager =
    (applicationContext as? LifeDashboardApplication)?.preferencesManager ?: PreferencesManager(this)
