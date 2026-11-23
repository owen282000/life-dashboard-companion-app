package com.owen282000.lifedashboard

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.SystemClock
import androidx.core.content.edit
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.math.roundToLong

/**
 * One scheduled sync that Android ran in the background, and what came of it.
 *
 * Logs only has deliveries, so a run that found nothing new or that Android stopped halfway left
 * no trace, and a day without syncs could not be told apart from a day Android never started one.
 * Only the moment, the source and the outcome are kept, never health data.
 *
 * [source], [outcome] and [stopReason] are kept as strings, so an entry from a newer build still
 * decodes; what this build does not know reads as null.
 */
@Serializable
data class BackgroundRun(
    val source: String,
    val outcome: String,
    /** When the first and the last run of a merged entry started; the same for a single one. */
    val first: Long,
    val last: Long,
    val count: Int = 1,
    /** How long the last run took. */
    val durationSeconds: Double? = null,
    val stopReason: String? = null
) {
    enum class Outcome {
        SYNCED,
        NOTHING_NEW,
        FAILED,
        STOPPED;

        /** A 15 minute interval finds nothing new most of the time, and would fill the list in hours. */
        val merges: Boolean get() = this == NOTHING_NEW
    }

    /** Why Android stopped a run, in a few groups that say what to change. */
    enum class StopReason {
        /** Doze or battery saver. */
        BATTERY,

        /** Restricted battery use, or the app on standby. */
        RESTRICTED,

        /** The share of background time Android gives the app was used up. */
        QUOTA,

        /** A condition of the work no longer held. */
        CONSTRAINTS,

        /** The run took longer than Android allows a background job. */
        TIME_LIMIT,

        /** The system for its own reasons, or one Android did not say. */
        OTHER
    }

    val sourceType: LogType? get() = LogType.entries.firstOrNull { it.name == source }
    val outcomeType: Outcome? get() = Outcome.entries.firstOrNull { it.name == outcome }
    val stopReasonType: StopReason? get() = StopReason.entries.firstOrNull { it.name == stopReason }

    companion object {
        /** A WorkInfo.STOP_REASON_* to the group the Logs tab names. */
        fun stopReasonOf(reason: Int): StopReason = when (reason) {
            WorkInfo.STOP_REASON_DEVICE_STATE -> StopReason.BATTERY
            WorkInfo.STOP_REASON_BACKGROUND_RESTRICTION,
            WorkInfo.STOP_REASON_APP_STANDBY -> StopReason.RESTRICTED
            WorkInfo.STOP_REASON_QUOTA -> StopReason.QUOTA
            WorkInfo.STOP_REASON_CONSTRAINT_BATTERY_NOT_LOW,
            WorkInfo.STOP_REASON_CONSTRAINT_CHARGING,
            WorkInfo.STOP_REASON_CONSTRAINT_CONNECTIVITY,
            WorkInfo.STOP_REASON_CONSTRAINT_DEVICE_IDLE,
            WorkInfo.STOP_REASON_CONSTRAINT_STORAGE_NOT_LOW -> StopReason.CONSTRAINTS
            WorkInfo.STOP_REASON_TIMEOUT,
            WorkInfo.STOP_REASON_FOREGROUND_SERVICE_TIMEOUT -> StopReason.TIME_LIMIT
            else -> StopReason.OTHER
        }

        fun outcomeOf(result: Result<HealthSyncResult>): Outcome = when (result.getOrNull()) {
            null, is HealthSyncResult.Queued -> Outcome.FAILED
            HealthSyncResult.NoData -> Outcome.NOTHING_NEW
            is HealthSyncResult.Success -> Outcome.SYNCED
        }

        @JvmName("screenTimeOutcomeOf")
        fun outcomeOf(result: Result<ScreenTimeSyncResult>): Outcome = when (result.getOrNull()) {
            null, is ScreenTimeSyncResult.Queued -> Outcome.FAILED
            ScreenTimeSyncResult.NoData -> Outcome.NOTHING_NEW
            is ScreenTimeSyncResult.Success -> Outcome.SYNCED
        }
    }
}

/** The last runs, newest first, merged and capped by pure rules the tests can check. */
object BackgroundRunLog {
    const val MAX_ENTRIES = 50

    /**
     * Adds [run] at the top. A "nothing new" merges into the newest entry of its own source when
     * that is a "nothing new" as well, and that entry moves to the top. Per source, because both
     * sources run on their own schedule, and their runs alternating would otherwise never merge.
     */
    fun adding(run: BackgroundRun, entries: List<BackgroundRun>): List<BackgroundRun> {
        if (run.outcomeType?.merges == true) {
            val index = entries.indexOfFirst { it.source == run.source }
            val newest = entries.getOrNull(index)
            if (newest != null && newest.outcome == run.outcome) {
                val merged = newest.copy(last = run.last, count = newest.count + run.count, durationSeconds = run.durationSeconds)
                return listOf(merged) + entries.filterIndexed { i, _ -> i != index }
            }
        }
        return (listOf(run) + entries).take(MAX_ENTRIES)
    }
}

/**
 * Keeps the runs as JSON in the logs' preferences file: a few kilobytes, no health data. That file
 * is left out of Android's backup (`res/xml/backup_rules.xml`), and the settings export lists its
 * fields one by one, so neither carries this install's runs to another phone.
 */
class BackgroundRunStore(private val prefs: SharedPreferences) {

    fun record(run: BackgroundRun) = synchronized(LOCK) {
        val entries = BackgroundRunLog.adding(run, read())
        // Committed rather than applied: Android may end the process right after it stopped a run.
        prefs.edit(commit = true) { putString(KEY, json.encodeToString(entries)) }
    }

    fun load(): List<BackgroundRun> = synchronized(LOCK) { read() }

    fun clear() = synchronized(LOCK) {
        prefs.edit { remove(KEY) }
    }

    private fun read(): List<BackgroundRun> {
        val stored = prefs.getString(KEY, null) ?: return emptyList()
        return runCatching { json.decodeFromString<List<BackgroundRun>>(stored) }.getOrDefault(emptyList())
    }

    companion object {
        private const val KEY = "background_runs"

        /** One lock for every store in the process: both workers can finish at the same moment. */
        private val LOCK = Any()

        private val json = Json { ignoreUnknownKeys = true }

        fun forContext(context: Context) = BackgroundRunStore(WebhookLogStore.prefs(context))
    }
}

/**
 * Notes one run of a worker in [BackgroundRunStore], when [SyncScheduler] scheduled it. A sync
 * from the screen, the tile or a broadcast is not a background run, and notes nothing.
 */
class BackgroundRunRecorder(private val worker: ListenableWorker, private val source: LogType) {
    private val scheduled = SyncScheduler.isScheduled(worker.tags)
    private val startedAt = System.currentTimeMillis()
    private val startedElapsed = SystemClock.elapsedRealtime()

    fun finished(outcome: BackgroundRun.Outcome) = note(outcome, stopReason = null)

    /**
     * For a worker Android stopped. WorkManager sets the reason before it cancels the coroutine,
     * so it is there by the time the CancellationException arrives. Android names it from 12 on.
     */
    fun stopped() {
        val reason = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) worker.stopReason else WorkInfo.STOP_REASON_UNKNOWN
        note(BackgroundRun.Outcome.STOPPED, BackgroundRun.stopReasonOf(reason))
    }

    private fun note(outcome: BackgroundRun.Outcome, stopReason: BackgroundRun.StopReason?) {
        if (!scheduled) return
        val tenths = (SystemClock.elapsedRealtime() - startedElapsed) / 100.0
        // Never in the way of the sync or of the cancellation being passed on.
        runCatching {
            BackgroundRunStore.forContext(worker.applicationContext).record(
                BackgroundRun(
                    source = source.name,
                    outcome = outcome.name,
                    first = startedAt,
                    last = startedAt,
                    durationSeconds = tenths.roundToLong() / 10.0,
                    stopReason = stopReason?.name
                )
            )
        }
    }
}
