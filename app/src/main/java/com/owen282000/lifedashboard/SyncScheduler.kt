package com.owen282000.lifedashboard

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Turns a [SyncSchedule] into WorkManager work, for both sources.
 *
 * A plain interval stays periodic work, which is what Android schedules most efficiently and
 * what every existing install already has. As soon as a schedule needs a decision per run
 * (fixed times, a weekday filter, a quiet window) it becomes one-time work: each run queues
 * the next one when it finishes, because periodic work cannot express "next Monday at 08:00".
 *
 * Two rules keep that chain honest:
 *
 * 1. A finishing run never enqueues under its own unique name. Replacing the name you are
 *    running under cancels the run doing the replacing, so the queued run alternates between
 *    two slots (A finishes and queues B; B finishes and queues A).
 * 2. A settings change never aborts a sync in flight. If a run is active, the change is left
 *    for that run to pick up when it finishes and re-reads the schedule.
 *
 * WorkManager's state is read on a background executor; nothing here blocks the caller.
 */
object SyncScheduler {

    /** Marks work this scheduler created, as opposed to a manual sync from the tile or a broadcast. */
    private const val TAG_SCHEDULED = "lifedashboard.scheduled"
    private const val TAG_SLOT_A = "lifedashboard.slot.a"
    private const val TAG_SLOT_B = "lifedashboard.slot.b"

    private val executor: Executor = Executors.newSingleThreadExecutor()

    /** Called after a settings change or at app start: apply the stored schedule of [source]. */
    fun reschedule(context: Context, source: LogType) {
        plan(context.applicationContext, source, finished = null)
    }

    /** Reschedules both sources; called at app start and after a settings import. */
    fun rescheduleAll(context: Context) {
        LogType.entries.forEach { reschedule(context, it) }
    }

    /**
     * Called by every worker as it finishes, scheduled or not. A scheduled run records itself
     * as the last run (interval mode counts from it) and queues the next one. A manual run
     * records nothing, but still re-plans: a settings change made while it was active was
     * deferred to this moment.
     */
    fun onSyncFinished(context: Context, source: LogType, workId: UUID, tags: Set<String>) {
        val appContext = context.applicationContext
        if (TAG_SCHEDULED in tags) {
            appContext.appPreferences().setScheduleLastRun(source, System.currentTimeMillis())
        }
        plan(appContext, source, finished = FinishedRun(workId, slotFromTags(tags)))
    }

    private class FinishedRun(val id: UUID, val slot: Slot?)

    private enum class Slot { A, B }

    private fun plan(context: Context, source: LogType, finished: FinishedRun?) {
        val workManager = WorkManager.getInstance(context)
        val names = listOf(periodicName(source)) + Slot.entries.map { slotName(source, it) }
        val futures = names.map { workManager.getWorkInfosForUniqueWork(it) }

        executor.execute {
            val infos = futures.flatMap { runCatching { it.get() }.getOrDefault(emptyList()) }
            val anotherRunIsActive = infos.any { it.state == WorkInfo.State.RUNNING && it.id != finished?.id }
            if (anotherRunIsActive) return@execute

            val prefs = context.appPreferences()
            val schedule = prefs.getSyncSchedule(source)
            val lastRun = prefs.getScheduleLastRun(source)?.let { toLocalDateTime(it) }
            apply(workManager, source, schedule, lastRun, finished?.slot)
        }
    }

    private fun apply(
        workManager: WorkManager,
        source: LogType,
        schedule: SyncSchedule,
        lastRun: LocalDateTime?,
        ownSlot: Slot?
    ) {
        // The finishing run's own slot completes by itself; cancelling it would only log noise.
        val otherSlots = Slot.entries.filter { it != ownSlot }

        if (schedule.isNeverRunning) {
            // Nothing can run: cancel everything rather than leave work that fires on old settings.
            workManager.cancelUniqueWork(periodicName(source))
            otherSlots.forEach { workManager.cancelUniqueWork(slotName(source, it)) }
            return
        }

        if (schedule.usesPlainInterval) {
            // A queued timed run left behind would fire once more after the switch.
            otherSlots.forEach { workManager.cancelUniqueWork(slotName(source, it)) }
            workManager.enqueueUniquePeriodicWork(
                periodicName(source),
                ExistingPeriodicWorkPolicy.UPDATE,
                periodicRequest(source, schedule)
            )
            return
        }

        // Likewise the other way around: the periodic chain has to go, or both would run.
        workManager.cancelUniqueWork(periodicName(source))

        val delay = schedule.delayFrom(LocalDateTime.now(), lastRun) ?: return
        val nextSlot = if (ownSlot == Slot.A) Slot.B else Slot.A
        otherSlots.filter { it != nextSlot }.forEach { workManager.cancelUniqueWork(slotName(source, it)) }

        val slotTag = if (nextSlot == Slot.A) TAG_SLOT_A else TAG_SLOT_B
        val request = when (source) {
            LogType.HEALTH_CONNECT -> OneTimeWorkRequestBuilder<HealthSyncWorker>().scheduled(delay.toMillis(), slotTag)
            LogType.SCREEN_TIME -> OneTimeWorkRequestBuilder<ScreenTimeSyncWorker>().scheduled(delay.toMillis(), slotTag)
        }
        // REPLACE: editing the schedule while a run is queued moves that run instead of adding one.
        workManager.enqueueUniqueWork(slotName(source, nextSlot), ExistingWorkPolicy.REPLACE, request)
    }

    private fun OneTimeWorkRequest.Builder.scheduled(delayMillis: Long, slotTag: String): OneTimeWorkRequest =
        setInitialDelay(delayMillis, TimeUnit.MILLISECONDS).addTag(TAG_SCHEDULED).addTag(slotTag).build()

    private fun periodicRequest(source: LogType, schedule: SyncSchedule) = when (source) {
        LogType.HEALTH_CONNECT -> PeriodicWorkRequestBuilder<HealthSyncWorker>(
            schedule.intervalMinutes.toLong(), TimeUnit.MINUTES
        )
        LogType.SCREEN_TIME -> PeriodicWorkRequestBuilder<ScreenTimeSyncWorker>(
            schedule.intervalMinutes.toLong(), TimeUnit.MINUTES
        )
    }.addTag(TAG_SCHEDULED).build()

    /** The periodic chain keeps the names the app has always used, so an update does not orphan them. */
    private fun periodicName(source: LogType) = when (source) {
        LogType.HEALTH_CONNECT -> HealthSyncWorker.WORK_NAME
        LogType.SCREEN_TIME -> ScreenTimeSyncWorker.WORK_NAME
    }

    private fun slotName(source: LogType, slot: Slot) = periodicName(source) + "_next_" + slot.name.lowercase()

    private fun slotFromTags(tags: Set<String>): Slot? = when {
        TAG_SLOT_A in tags -> Slot.A
        TAG_SLOT_B in tags -> Slot.B
        else -> null
    }

    private fun toLocalDateTime(epochMillis: Long): LocalDateTime =
        LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMillis), ZoneId.systemDefault())
}

/**
 * True when the schedule is a bare interval with no filters, which periodic work can express
 * on its own. Anything else needs a decision per run.
 */
val SyncSchedule.usesPlainInterval: Boolean
    get() = mode == SyncMode.INTERVAL && quietWindow == null && days.size == DayOfWeek.entries.size
