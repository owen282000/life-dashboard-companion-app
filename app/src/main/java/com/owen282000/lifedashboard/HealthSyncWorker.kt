package com.owen282000.lifedashboard

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class HealthSyncWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    private val syncManager = HealthSyncManager(appContext)

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val run = BackgroundRunRecorder(this@HealthSyncWorker, LogType.HEALTH_CONNECT)
        try {
            val syncResult = syncManager.performSync()
            val outcome = BackgroundRun.outcomeOf(syncResult)
            run.finished(outcome, if (outcome == BackgroundRun.Outcome.SYNCED) null else readProblem())
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
            SyncScheduler.onSyncFinished(applicationContext, LogType.HEALTH_CONNECT, id, tags)
        }
    }

    /** Asked only after a run that sent nothing, so a run that works costs no extra call. */
    private suspend fun readProblem(): BackgroundRun.ReadProblem? = try {
        val manager = HealthConnectManager(applicationContext)
        val missing = BackgroundAccess.missing(
            granted = manager.getGrantedPermissions(),
            readsTypes = PreferencesManager(applicationContext).getHealthEnabledDataTypes().isNotEmpty(),
            featureAvailable = manager.backgroundReadAvailable()
        )
        if (missing) BackgroundRun.ReadProblem.NO_BACKGROUND_ACCESS else null
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    companion object {
        const val WORK_NAME = "health_sync_work"
    }
}
