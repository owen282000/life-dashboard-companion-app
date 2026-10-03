package com.owen282000.lifedashboard

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * A backfill as a job that outlives the screen and the process (P2-14): the range it covers,
 * fixed when it starts, and the first window it has not delivered in full. It is saved after
 * every window that went through, so a run that Android stops, that the quota stops or that a
 * failed delivery stops picks up at the next window instead of at the first one; at most one
 * window goes out twice, and a receiver deduplicates its records on their uuid. A job that
 * finished is cleared. The iOS app keeps its BackfillJob the same way.
 *
 * Free of Android types, so the windows and the resume rules are tested on the JVM.
 */
@Serializable
data class BackfillJob(
    val id: String,
    val days: Int,
    /** The range in epoch milliseconds, whole seconds, so a resumed job walks the same windows. */
    val rangeStart: Long,
    val rangeEnd: Long,
    /** The first window not delivered in full; equal to [windowCount] once the job is done. */
    val nextWindow: Int = 0,
    /** Records delivered by every run of this job together. */
    val recordsSent: Int = 0,
    /** Runs the read quota stopped since the last window that went through; see [MAX_QUOTA_PAUSES]. */
    val quotaPauses: Int = 0,
    val updatedAt: Long
) {
    val windowCount: Int get() = windowCount(rangeStart, rangeEnd)

    val isFinished: Boolean get() = nextWindow >= windowCount

    /** Window [index], oldest first: three days of 86,400 seconds, the last one ending at the range end. */
    fun window(index: Int = nextWindow): Pair<Instant, Instant> {
        val start = rangeStart + index * WINDOW_MILLIS
        return Instant.ofEpochMilli(start) to Instant.ofEpochMilli(minOf(start + WINDOW_MILLIS, rangeEnd))
    }

    /** The job once the window it stood at was delivered in full, [records] of them. */
    fun afterWindow(records: Int, now: Long): BackfillJob =
        copy(nextWindow = nextWindow + 1, recordsSent = recordsSent + records, quotaPauses = 0, updatedAt = now)

    /** The job once Health Connect's read quota stopped a run at its window. */
    fun afterQuotaPause(now: Long): BackfillJob = copy(quotaPauses = quotaPauses + 1, updatedAt = now)

    /**
     * True when the quota stopped the job [MAX_QUOTA_PAUSES] times in a row without a window
     * going through. WorkManager would retry it for as long as the work exists, hours apart in
     * the end; this ends it, and starting it again continues where it stopped.
     */
    val quotaGivesUp: Boolean get() = quotaPauses >= MAX_QUOTA_PAUSES

    companion object {
        const val WINDOW_DAYS = 3L
        private val WINDOW_MILLIS = Duration.ofDays(WINDOW_DAYS).toMillis()

        /**
         * Quota stops in a row before a job gives up. With the backoff of [BackfillWork] (one
         * minute, doubling) the sixth comes half an hour or more after the first.
         */
        const val MAX_QUOTA_PAUSES = 6

        /**
         * How long a stopped job is continued by starting a backfill of the same length. Past
         * that its range ends too long ago: what came in since would fall between its end and
         * the start of the regular sync's lookback, so the backfill starts over instead.
         */
        val RESUME_WINDOW: Duration = Duration.ofHours(24)

        private val json = Json { ignoreUnknownKeys = true }

        fun windowCount(rangeStart: Long, rangeEnd: Long): Int {
            if (rangeEnd <= rangeStart) return 0
            return ((rangeEnd - rangeStart + WINDOW_MILLIS - 1) / WINDOW_MILLIS).toInt()
        }

        /** A new job over the [days] up to [now], which is floored to a whole second like the payload's window_end. */
        fun start(days: Int, now: Long, id: String = UUID.randomUUID().toString()): BackfillJob {
            val end = now - Math.floorMod(now, 1000L)
            return BackfillJob(
                id = id,
                days = days,
                rangeStart = end - Duration.ofDays(days.toLong()).toMillis(),
                rangeEnd = end,
                updatedAt = now
            )
        }

        /**
         * What a start of a [days] backfill at [now] runs: [stored] when it is a stopped job of
         * the same length from the last [RESUME_WINDOW], with its quota count reset because
         * someone asked again; otherwise a new job. A different length is a new request.
         */
        fun startOrContinue(stored: BackfillJob?, days: Int, now: Long): BackfillJob =
            if (continues(stored, days, now)) stored!!.copy(quotaPauses = 0, updatedAt = now) else start(days, now)

        fun continues(stored: BackfillJob?, days: Int, now: Long): Boolean =
            stored != null && !stored.isFinished && stored.days == days &&
                now - stored.updatedAt in 0 until RESUME_WINDOW.toMillis()

        fun encode(job: BackfillJob): String = json.encodeToString(serializer(), job)

        /** The stored job, or null for none and for one that cannot be read: start over rather than guess. */
        fun decode(text: String?): BackfillJob? {
            if (text.isNullOrBlank()) return null
            return try {
                json.decodeFromString(serializer(), text)
            } catch (e: SerializationException) {
                null
            } catch (e: IllegalArgumentException) {
                null
            }
        }
    }
}

/**
 * How a backfill run ended. [job] is where it stopped, as it was saved; [sent] is what this run
 * delivered, which is what its row in the Logs tab counts.
 */
sealed interface BackfillRun {
    val job: BackfillJob
    val sent: Int

    /** Every window went through; the job is cleared. */
    data class Done(override val job: BackfillJob, override val sent: Int) : BackfillRun

    /** Health Connect's read quota is used up; the job stays and continues after a backoff. */
    data class QuotaUsedUp(override val job: BackfillJob, override val sent: Int) : BackfillRun

    /** A delivery, a read or the setup failed; the job stays, and starting it again continues it. */
    data class Failed(override val job: BackfillJob, override val sent: Int, val message: String) : BackfillRun
}

/** The backfill as the screen sees it, from the WorkManager job. */
sealed interface BackfillStatus {
    data object Idle : BackfillStatus

    /**
     * Queued or running at window [done] of [total]. [waiting]: it waits for a sync that holds
     * the lock. [paused]: Android or the quota stopped it, and WorkManager runs it again later.
     */
    data class Running(val done: Int, val total: Int, val waiting: Boolean = false, val paused: Boolean = false) : BackfillStatus

    /** Finished: [records] when every window went through, [error] when it stopped on a failure. */
    data class Finished(val records: Int?, val error: String?) : BackfillStatus
}
