package com.owen282000.lifedashboard

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.res.Resources
import androidx.core.app.NotificationCompat
import androidx.core.content.edit
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * The stored [BackfillJob]: one in the app's preferences, a few numbers and no health data.
 * Written with commit(), so a payload that went through is on disk before the next one is read
 * and a process that dies right after it does not send it again.
 */
object BackfillJobStore {

    private const val PREFS_NAME = "life_dashboard_prefs"
    private const val KEY_JOB = "backfill_job"
    private val lock = Any()

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(context: Context): BackfillJob? = synchronized(lock) {
        BackfillJob.decode(prefs(context).getString(KEY_JOB, null))
    }

    /** The enabled types as a job keeps them. */
    fun enabledTypes(context: Context): List<String> =
        BackfillJob.typeNames(PreferencesManager(context).getHealthEnabledDataTypes())

    /** Stores the job a start of [days] runs, see [BackfillJob.startOrContinue], and returns it. */
    fun startOrContinue(context: Context, days: Int, now: Long = System.currentTimeMillis()): BackfillJob = synchronized(lock) {
        val job = BackfillJob.startOrContinue(load(context), days, enabledTypes(context), now)
        prefs(context).edit(commit = true) { putString(KEY_JOB, BackfillJob.encode(job)) }
        job
    }

    /**
     * Saves [job] when it is still the stored one. A run that was cancelled can finish its
     * payload after the cancel cleared the store; that must not bring the job back.
     */
    fun saveIfCurrent(context: Context, job: BackfillJob): Boolean = synchronized(lock) {
        if (load(context)?.id != job.id) return false
        prefs(context).edit(commit = true) { putString(KEY_JOB, BackfillJob.encode(job)) }
        true
    }

    /** Clears the job when [id] is still the stored one: a finished job, or a cancelled one with any id (null). */
    fun clear(context: Context, id: String? = null) = synchronized(lock) {
        if (id != null && load(context)?.id != id) return
        prefs(context).edit(commit = true) { remove(KEY_JOB) }
    }
}

/**
 * The backfill as unique WorkManager work (P2-14). It used to run in the screen's coroutine, so
 * it died with the screen or the process and a rerun started at the first window again. Now the
 * job is in [BackfillJobStore] and [BackfillWorker] runs it; when Android stops the worker,
 * WorkManager runs it again and it continues after the last payload that went through.
 *
 * Expedited, because the user just asked for it, and falling back to ordinary work when the
 * app's expedited quota is used up. Not a long-running foreground service: that needs a
 * foreground service type with its own permission and Play declaration, and a stop costs one
 * payload at most now. Reading Health Connect while the app is not in front needs the background
 * permission that Grant asks for, as every scheduled sync does; without it the read fails and
 * the job stops where it was, ready to be started again.
 */
object BackfillWork {

    const val WORK_NAME = "health_backfill_work"

    internal const val KEY_DONE = "done"
    internal const val KEY_TOTAL = "total"
    internal const val KEY_WAITING = "waiting"
    internal const val KEY_RESTARTED = "restarted"
    internal const val KEY_RECORDS = "records"
    internal const val KEY_FAILURE = "failure"
    internal const val KEY_DETAIL = "detail"

    /** The first retry after a quota stop; WorkManager doubles it each time. Health Connect refills within minutes. */
    private const val BACKOFF_MINUTES = 1L

    /**
     * Starts a backfill of [days], or continues the stopped one of that length and types.
     *
     * A backfill that is queued or running is kept, and the answer says so. The exception is
     * work that is still winding down after Stop: Stop cleared the stored job at once, while the
     * worker only notices its cancellation a moment later, and a start in that moment must not
     * be swallowed. That work is replaced; the old worker can no longer save anything, since the
     * stored job is not its own.
     */
    suspend fun start(context: Context, days: Int): BackfillStart = withContext(Dispatchers.IO) {
        val workManager = WorkManager.getInstance(context)
        val active = workManager.getWorkInfosForUniqueWork(WORK_NAME).get().any { !it.state.isFinished }
        if (active && BackfillJobStore.load(context) != null) return@withContext BackfillStart.ALREADY_RUNNING
        BackfillJobStore.startOrContinue(context, days)
        val request = OneTimeWorkRequestBuilder<BackfillWorker>()
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            // Without a network every delivery fails; waiting for one is what the user wants.
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_MINUTES, TimeUnit.MINUTES)
            .build()
        workManager.enqueueUniqueWork(WORK_NAME, if (active) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP, request)
        BackfillStart.STARTED
    }

    /** Stops the backfill and forgets the job. What was sent stays with the receiver. */
    fun cancel(context: Context) {
        BackfillJobStore.clear(context)
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }

    /** The job a start of its own length would continue with the types enabled now, for the dialog; null when there is none. */
    fun stopped(context: Context, now: Long = System.currentTimeMillis()): BackfillJob? =
        BackfillJobStore.load(context)?.takeIf { BackfillJob.continues(it, it.days, BackfillJobStore.enabledTypes(context), now) }

    /** The backfill's state, from its work; it keeps coming after the screen was left and opened again. */
    fun status(context: Context): Flow<BackfillStatus> =
        WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(WORK_NAME)
            // After a replace the cancelled work is listed next to the new one; the new one counts.
            .map { infos -> statusOf(infos.firstOrNull { !it.state.isFinished } ?: infos.lastOrNull(), context) }
            // Not made distinct: after Stop and a new start, the new work can report exactly what
            // the stopped one last did, and the screen, which ignored the stopped one, needs it.
            .flowOn(Dispatchers.IO)

    private fun statusOf(info: WorkInfo?, context: Context): BackfillStatus {
        info ?: return BackfillStatus.Idle
        return when (info.state) {
            WorkInfo.State.SUCCEEDED -> BackfillStatus.Finished(info.outputData.getInt(KEY_RECORDS, 0), null)
            WorkInfo.State.FAILED -> BackfillStatus.Finished(null, failureOf(info.outputData))
            WorkInfo.State.CANCELLED -> BackfillStatus.Idle
            WorkInfo.State.RUNNING -> {
                val progress = info.progress
                val job = BackfillJobStore.load(context)
                BackfillStatus.Running(
                    done = progress.getInt(KEY_DONE, job?.nextWindow ?: 0),
                    total = progress.getInt(KEY_TOTAL, job?.windowCount ?: 1),
                    waiting = progress.getBoolean(KEY_WAITING, false),
                    restarted = progress.getBoolean(KEY_RESTARTED, false)
                )
            }
            // Queued: not started yet, or run before and waiting for its retry.
            WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> {
                val job = BackfillJobStore.load(context)
                BackfillStatus.Running(
                    done = job?.nextWindow ?: 0,
                    total = job?.windowCount ?: 1,
                    paused = info.runAttemptCount > 0
                )
            }
        }
    }

    internal fun progressData(done: Int, total: Int, waiting: Boolean, restarted: Boolean): Data =
        workDataOf(KEY_DONE to done, KEY_TOTAL to total, KEY_WAITING to waiting, KEY_RESTARTED to restarted)

    internal fun failureData(failure: BackfillFailure, done: Int, total: Int): Data =
        workDataOf(KEY_FAILURE to failure.code, KEY_DONE to done, KEY_TOTAL to total, KEY_DETAIL to BackfillFailure.detailOf(failure))

    /** The failure a failed run's output carries; an output without one is an unexpected stop. */
    private fun failureOf(data: Data): BackfillFailure =
        BackfillFailure.of(data.getString(KEY_FAILURE), data.getInt(KEY_DONE, 0), data.getInt(KEY_TOTAL, 0), data.getString(KEY_DETAIL))
            ?: BackfillFailure.Unexpected("")
}

/** The backfill's words for the user: its failures, on screen and in the Logs tab, and its rows there. */
object BackfillTexts {

    fun failure(res: Resources, failure: BackfillFailure): String = when (failure) {
        BackfillFailure.NoWebhook -> res.getString(R.string.health_backfill_needs_webhook)
        BackfillFailure.NoTypes -> res.getString(R.string.health_backfill_failed_no_types)
        is BackfillFailure.Read -> res.getString(R.string.health_backfill_failed_read, failure.detail)
        is BackfillFailure.Delivery -> res.getString(R.string.health_backfill_failed_delivery, failure.done, failure.total)
        is BackfillFailure.NotReturned ->
            res.getString(R.string.health_backfill_failed_not_returned, failure.done, failure.total, failure.types.joinToString(", "))
        is BackfillFailure.TooManyChunks -> res.getString(
            R.string.health_backfill_failed_too_many_chunks,
            failure.done,
            failure.total,
            MAX_PASSES_PER_BACKFILL_WINDOW
        )
        is BackfillFailure.NoProgress -> res.getString(R.string.health_backfill_failed_no_progress, failure.done + 1, failure.total)
        is BackfillFailure.Unexpected -> res.getString(R.string.health_backfill_failed_unexpected, failure.detail)
    }
}

/**
 * Runs the stored [BackfillJob] from where it stands, under the sync lock like every sync. Each
 * payload that went through is saved before the next is read, so whatever stops this worker
 * (Android, the quota, a failed delivery, the process dying) costs at most the payload in
 * flight. A stop is a cancellation, rethrown, and WorkManager runs the work again by itself; the
 * quota asks for a retry with backoff; a failure ends the work and keeps the job. Runs that
 * deliver nothing are counted, and the job gives up after [BackfillJob.MAX_IDLE_RUNS].
 */
class BackfillWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        // Cancelled and cleared between being queued and running: nothing to do.
        val job = BackfillJobStore.load(applicationContext) ?: return@withContext Result.success()
        if (job.isFinished) {
            BackfillJobStore.clear(applicationContext, job.id)
            return@withContext Result.success(workDataOf(BackfillWork.KEY_RECORDS to job.recordsSent))
        }
        var at = job.nextWindow to job.windowCount
        var restarted = false
        setProgress(BackfillWork.progressData(at.first, at.second, waiting = false, restarted = false))
        val run = try {
            HealthSyncManager(applicationContext).runBackfill(
                job,
                onWaiting = { waiting -> setProgressAsync(BackfillWork.progressData(at.first, at.second, waiting, restarted)) },
                onProgress = { done, total ->
                    at = done to total
                    setProgressAsync(BackfillWork.progressData(done, total, waiting = false, restarted = restarted))
                },
                onRestarted = {
                    restarted = true
                    at = 0 to at.second
                    setProgressAsync(BackfillWork.progressData(0, at.second, waiting = false, restarted = true))
                }
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            val failure = BackfillFailure.Unexpected(e.message ?: e.javaClass.simpleName)
            return@withContext Result.failure(BackfillWork.failureData(failure, at.first, at.second))
        }
        when (run) {
            is BackfillRun.Done -> Result.success(workDataOf(BackfillWork.KEY_RECORDS to run.job.recordsSent))
            is BackfillRun.QuotaUsedUp -> Result.retry()
            is BackfillRun.Failed -> Result.failure(BackfillWork.failureData(run.failure, run.job.nextWindow, run.job.windowCount))
        }
    }

    /**
     * Only asked for below Android 12, where WorkManager runs expedited work as a foreground
     * service and has to show a notification for it. From Android 12 on expedited work is a
     * job of JobScheduler and this is not called.
     */
    override suspend fun getForegroundInfo(): ForegroundInfo {
        val context = applicationContext
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, context.getString(R.string.health_backfill_title), NotificationManager.IMPORTANCE_LOW)
            )
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_tile_sync)
            .setContentTitle(context.getString(R.string.health_backfill_title))
            .setOngoing(true)
            .setSilent(true)
            .build()
        return ForegroundInfo(NOTIFICATION_ID, notification)
    }

    private companion object {
        const val CHANNEL_ID = "backfill"
        const val NOTIFICATION_ID = 4101
    }
}
