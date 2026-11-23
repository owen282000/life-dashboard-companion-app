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
        val run = BackgroundRunRecorder(this@ScreenTimeSyncWorker, LogType.SCREEN_TIME)
        try {
            val syncResult = syncManager.performSync()
            run.finished(BackgroundRun.outcomeOf(syncResult))
            when {
                syncResult.isSuccess -> Result.success()
                syncResult.isFailure -> Result.failure()
                else -> Result.success() // No data case
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Android stopped this run: noted with its reason, and passed on so WorkManager retries it.
            run.stopped()
            throw e
        } catch (e: Exception) {
            run.finished(BackgroundRun.Outcome.FAILED)
            Result.failure()
        } finally {
            // Scheduled runs queue their successor here; see SyncScheduler for why every run,
            // manual ones included, reports that it finished.
            SyncScheduler.onSyncFinished(applicationContext, LogType.SCREEN_TIME, id, tags)
        }
    }

    companion object {
        const val WORK_NAME = "screentime_sync_work"
    }
}
