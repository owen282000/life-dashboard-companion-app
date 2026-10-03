package com.owen282000.lifedashboard

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * A backfill as a job that outlives the screen and the process (P2-14): the range it covers and
 * the data types it sends, fixed when it starts, and how far it got. It is saved after every
 * payload that went through, with the chunk cursor inside the window it stands at, so a run that
 * Android stops, that the quota stops or that a failed delivery stops picks up at the payload
 * after the last one delivered, also halfway through a dense window; at most one payload goes
 * out twice, and a receiver deduplicates its records on their uuid. A job that finished is
 * cleared. The iOS app keeps its BackfillJob the same way, per window.
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
    /**
     * The enabled data types when the job started, by name and sorted. A run that finds other
     * types enabled starts the job over, so no window lacks a type the receiver now expects.
     */
    val types: List<String> = emptyList(),
    /** The first window not delivered in full; equal to [windowCount] once the job is done. */
    val nextWindow: Int = 0,
    /** Records delivered by every run of this job together. */
    val recordsSent: Int = 0,
    /** Payloads of window [nextWindow] that went through; the per-window cap counts across runs. */
    val chunksSent: Int = 0,
    /** Per type, where the next chunk of window [nextWindow] starts; empty before its first chunk. */
    val chunkCursor: Map<String, ChunkMark> = emptyMap(),
    /** The types still draining in window [nextWindow]; null before its first chunk, when every type is read. */
    val draining: List<String>? = null,
    /** Types that could not be read in an earlier chunk of window [nextWindow]: the window cannot be complete. */
    val windowUnread: List<String> = emptyList(),
    /**
     * Runs in a row that delivered nothing: the quota stopped them, or Android did before a
     * payload went through. See [MAX_IDLE_RUNS].
     */
    val idleRuns: Int = 0,
    val updatedAt: Long
) {
    val windowCount: Int get() = windowCount(rangeStart, rangeEnd)

    val isFinished: Boolean get() = nextWindow >= windowCount

    /** Window [index], oldest first: three days of 86,400 seconds, the last one ending at the range end. */
    fun window(index: Int = nextWindow): Pair<Instant, Instant> {
        val start = rangeStart + index * WINDOW_MILLIS
        return Instant.ofEpochMilli(start) to Instant.ofEpochMilli(minOf(start + WINDOW_MILLIS, rangeEnd))
    }

    /** The job once a payload of its window went through and the window goes on from [cursor]. */
    fun afterChunk(records: Int, cursor: Map<String, ChunkMark>, draining: Set<String>, unread: Set<String>, now: Long): BackfillJob =
        copy(
            recordsSent = recordsSent + records,
            chunksSent = chunksSent + 1,
            chunkCursor = cursor,
            draining = draining.sorted(),
            windowUnread = unread.sorted(),
            idleRuns = 0,
            updatedAt = now
        )

    /** The job once the window it stood at was delivered in full; [records] are those of its last payload. */
    fun afterWindow(records: Int, now: Long): BackfillJob =
        copy(
            nextWindow = nextWindow + 1,
            recordsSent = recordsSent + records,
            chunksSent = 0,
            chunkCursor = emptyMap(),
            draining = null,
            windowUnread = emptyList(),
            idleRuns = 0,
            updatedAt = now
        )

    /**
     * The job after a payload of its window went through that ends the window without
     * completing it: a type was not returned, or the window needs more payloads than it may
     * take. It stays at the window, which the next run sends again from its start.
     */
    fun windowFromStart(records: Int, now: Long): BackfillJob =
        copy(
            recordsSent = recordsSent + records,
            chunksSent = 0,
            chunkCursor = emptyMap(),
            draining = null,
            windowUnread = emptyList(),
            idleRuns = 0,
            updatedAt = now
        )

    /** The job once a run ended without delivering anything: the quota, or a stop before the first payload. */
    fun afterIdleRun(now: Long): BackfillJob = copy(idleRuns = idleRuns + 1, updatedAt = now)

    /**
     * True when [MAX_IDLE_RUNS] runs in a row delivered nothing. WorkManager would run it again
     * for as long as the work exists, hours apart in the end, sending nothing and holding the
     * sync lock each time; this ends it, and starting it again continues where it stopped.
     */
    val givesUp: Boolean get() = idleRuns >= MAX_IDLE_RUNS

    /** The same job from its first window, for [types]: what a run does when the enabled types changed. */
    fun restartedFor(types: List<String>, now: Long): BackfillJob =
        BackfillJob(id = id, days = days, rangeStart = rangeStart, rangeEnd = rangeEnd, types = types, updatedAt = now)

    companion object {
        const val WINDOW_DAYS = 3L
        private val WINDOW_MILLIS = Duration.ofDays(WINDOW_DAYS).toMillis()

        /**
         * Runs in a row without a delivery before a job gives up. With the backoff of
         * [BackfillWork] (one minute, doubling) the sixth comes half an hour or more after the
         * first.
         */
        const val MAX_IDLE_RUNS = 6

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

        /** The names a job keeps of [types], sorted, so two sets compare by value. */
        fun typeNames(types: Collection<HealthDataType>): List<String> = types.map { it.name }.sorted()

        /** A new job over the [days] up to [now], which is floored to a whole second like the payload's window_end. */
        fun start(days: Int, types: List<String>, now: Long, id: String = UUID.randomUUID().toString()): BackfillJob {
            val end = now - Math.floorMod(now, 1000L)
            return BackfillJob(
                id = id,
                days = days,
                rangeStart = end - Duration.ofDays(days.toLong()).toMillis(),
                rangeEnd = end,
                types = types,
                updatedAt = now
            )
        }

        /**
         * What a start of a [days] backfill of [types] at [now] runs: [stored] when it is a
         * stopped job of the same length and types from the last [RESUME_WINDOW], with its idle
         * count reset because someone asked again; otherwise a new job.
         */
        fun startOrContinue(stored: BackfillJob?, days: Int, types: List<String>, now: Long): BackfillJob =
            if (continues(stored, days, types, now)) stored!!.copy(idleRuns = 0, updatedAt = now) else start(days, types, now)

        fun continues(stored: BackfillJob?, days: Int, types: List<String>, now: Long): Boolean =
            stored != null && !stored.isFinished && stored.days == days && stored.types == types &&
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

/** A chunk cursor of one type: a [Watermark] that survives the store, to the nanosecond. */
@Serializable
data class ChunkMark(val seconds: Long, val nanos: Int, val tieId: String? = null) {
    fun toWatermark(): Watermark = Watermark(Instant.ofEpochSecond(seconds, nanos.toLong()), tieId)

    companion object {
        fun of(mark: Watermark): ChunkMark = ChunkMark(mark.time.epochSecond, mark.time.nano, mark.tieId)
    }
}

/**
 * Why a backfill stopped for good, as data: the screen and the Logs tab put it in the user's
 * language (see BackfillTexts), and the WorkManager output carries it by [code].
 */
sealed interface BackfillFailure {
    val code: String

    data object NoWebhook : BackfillFailure { override val code = "no_webhook" }
    data object NoTypes : BackfillFailure { override val code = "no_types" }

    /** Health Connect refused the read; [detail] is its own message. */
    data class Read(val detail: String) : BackfillFailure { override val code = "read" }

    /** A delivery failed after [done] of [total] windows. */
    data class Delivery(val done: Int, val total: Int) : BackfillFailure { override val code = "delivery" }

    /** Window [done] + 1 went out without these payload keys, which Health Connect did not return. */
    data class NotReturned(val done: Int, val total: Int, val types: List<String>) : BackfillFailure { override val code = "not_returned" }

    /** Window [done] + 1 needs more payloads than one window may take. */
    data class TooManyChunks(val done: Int, val total: Int) : BackfillFailure { override val code = "too_many_chunks" }

    /** [BackfillJob.MAX_IDLE_RUNS] runs in a row at window [done] + 1 delivered nothing. */
    data class NoProgress(val done: Int, val total: Int) : BackfillFailure { override val code = "no_progress" }

    /** Anything else the worker caught; [detail] is the exception's message. */
    data class Unexpected(val detail: String) : BackfillFailure { override val code = "unexpected" }

    companion object {
        /** The failure [code] names, rebuilt from the fields a WorkManager output carries; null for an unknown code. */
        fun of(code: String?, done: Int, total: Int, detail: String?): BackfillFailure? = when (code) {
            NoWebhook.code -> NoWebhook
            NoTypes.code -> NoTypes
            "read" -> Read(detail.orEmpty())
            "delivery" -> Delivery(done, total)
            "not_returned" -> NotReturned(done, total, detail.orEmpty().split(",").filter { it.isNotBlank() })
            "too_many_chunks" -> TooManyChunks(done, total)
            "no_progress" -> NoProgress(done, total)
            "unexpected" -> Unexpected(detail.orEmpty())
            else -> null
        }

        /** The text field of [failure] for a WorkManager output, the counterpart of [of]. */
        fun detailOf(failure: BackfillFailure): String? = when (failure) {
            is Read -> failure.detail
            is NotReturned -> failure.types.joinToString(",")
            is Unexpected -> failure.detail
            else -> null
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
    data class Failed(override val job: BackfillJob, override val sent: Int, val failure: BackfillFailure) : BackfillRun
}

/** What a start of a backfill did. */
enum class BackfillStart {
    STARTED,

    /** One is queued or running already; the start did nothing. */
    ALREADY_RUNNING
}

/** The backfill as the screen sees it, from the WorkManager job. */
sealed interface BackfillStatus {
    data object Idle : BackfillStatus

    /**
     * Queued or running at window [done] of [total]. [waiting]: it waits for a sync that holds
     * the lock. [paused]: Android or the quota stopped it, and WorkManager runs it again later.
     * [restarted]: the enabled data types changed since it started, so it began again at the
     * first window.
     */
    data class Running(
        val done: Int,
        val total: Int,
        val waiting: Boolean = false,
        val paused: Boolean = false,
        val restarted: Boolean = false
    ) : BackfillStatus

    /** Finished: [records] when every window went through, [failure] when it stopped on one. */
    data class Finished(val records: Int?, val failure: BackfillFailure?) : BackfillStatus
}
