package com.owen282000.lifedashboard

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ScreenTimeSyncWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    private val syncManager = ScreenTimeSyncManager(appContext)

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val syncResult = syncManager.performSync()
            when {
                syncResult.isSuccess -> Result.success()
                syncResult.isFailure -> Result.failure()
                else -> Result.success() // No data case
            }
        } catch (e: Exception) {
            Result.failure()
        }
    }

    companion object {
        const val WORK_NAME = "screentime_sync_work"
    }
}
