package com.owen282000.lifedashboard

import android.app.Application
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

class LifeDashboardApplication : Application() {

    private lateinit var preferencesManager: PreferencesManager

    override fun onCreate() {
        super.onCreate()
        preferencesManager = PreferencesManager(this)

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
