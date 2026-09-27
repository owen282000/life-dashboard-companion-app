package com.owen282000.lifedashboard

import kotlinx.serialization.Serializable
import java.time.Duration
import java.time.Instant

/**
 * How far back a sync reads each type. The watermark filters on modification time, but Health
 * Connect can only be asked for a range of the records' own timestamps, so a record outside the
 * read range is never seen, while the watermark moves past it on newer records. With a range
 * that trailed "now" by a fixed week, a phone that did not sync for more than a week (deep
 * sleep, switched off, force-stopped) lost what a watch wrote for the days before the pause.
 *
 * The range therefore reaches [LOOKBACK] back from the last read that took everything of the
 * type, not from now. When syncs run normally that read was one interval ago, so the range is
 * the same week plus that interval, and the watermark keeps out everything already sent.
 */
object LookbackWindow {

    /** How long after the fact a source may write a record and still have it picked up. */
    val LOOKBACK: Duration = Duration.ofDays(7)

    /**
     * The furthest a sync reaches back, whatever the pause. Health Connect lets any app read
     * this far without the history permission, and the changes feed that carries deletions
     * forgets a phone after the same 30 days. A longer gap is the backfill's job: it reads in
     * bounded windows, where a sync reads its whole range again on every pass.
     */
    val MAX_REACH: Duration = Duration.ofDays(DeletionTracking.TOKEN_MAX_AGE_DAYS)

    /**
     * The range start for one type. [gapFrom] is set when the pause was too long for
     * [MAX_REACH]: records timestamped from [gapFrom] up to [start] that were written or edited
     * during the pause are not read, and `_diagnostics` names that range so a receiver can ask
     * for a backfill of it.
     */
    data class Window(val start: Instant, val gapFrom: Instant? = null)

    /**
     * The window for a read at [now] of a type last read completely at [coveredUntil] (null
     * before the first such read, which reads the plain [LOOKBACK] as before).
     */
    fun of(now: Instant, coveredUntil: Instant?): Window {
        // A clock set back since then must not push the range into the future.
        val anchor = if (coveredUntil == null || coveredUntil > now) now else coveredUntil
        val wanted = anchor.minus(LOOKBACK)
        val floor = now.minus(MAX_REACH)
        return if (wanted < floor) Window(floor, gapFrom = wanted) else Window(wanted)
    }

    /**
     * The anchors to store after a read at [readEnd] that used [anchors]. A type read
     * completely moves its [of] anchor to [readEnd]. A type cut by the per-sync cap keeps the
     * anchor this read used, since the rest of its backlog may be older than a later range:
     * the stored one, or [readEnd] when it had none, so its range does not slide forward with
     * "now" while the backlog drains. A type left unread stores nothing.
     */
    fun covered(
        enabled: Set<HealthDataType>,
        capped: Set<HealthDataType>,
        unread: Set<HealthDataType>,
        readEnd: Instant,
        anchors: Map<HealthDataType, Instant?> = emptyMap()
    ): Map<HealthDataType, Instant> = (enabled - unread).associateWith { type ->
        if (type in capped) anchors[type] ?: readEnd else readEnd
    }

    /**
     * [anchors] without the types whose read named a [Window.gapFrom], for a sync that sends no
     * payload: the gap is only named in a payload's `_diagnostics`, so moving the anchor
     * without one would drop it unsaid. The next sync names the same gap again.
     */
    fun keepingGapsOpen(
        anchors: Map<HealthDataType, Instant>,
        diagnostics: Map<HealthDataType, TypeDiagnostics>
    ): Map<HealthDataType, Instant> = anchors.filterKeys { diagnostics[it]?.lookbackGapFrom == null }

    /**
     * What of one type's changes the read over a range from [readFrom] cannot see: the
     * [recordTimes] (time, or start time) of records a source wrote or edited since the last
     * sync, before that range. Null when all of them are inside it.
     */
    fun outside(recordTimes: List<Instant>, readFrom: Instant): OutsideWindow? {
        val before = recordTimes.filter { it < readFrom }
        if (before.isEmpty()) return null
        return OutsideWindow(before.size, before.min().toEpochMilli(), readFrom.toEpochMilli())
    }
}

/**
 * Changes of one type that the sync's read did not see: [count] records that a source wrote or
 * edited long after their own time, timestamped from [fromMs] up to [untilMs], where the range
 * the sync read started. A watch that was away from the phone for more than a week uploads
 * such records while syncs run normally; the watermark moves past them on newer records, so
 * only a backfill of that range sends them. Carried with the deletions, which come from the
 * same changes feed and cannot be read twice either.
 */
@Serializable
data class OutsideWindow(val count: Int, val fromMs: Long, val untilMs: Long) {
    operator fun plus(other: OutsideWindow) =
        OutsideWindow(count + other.count, minOf(fromMs, other.fromMs), maxOf(untilMs, other.untilMs))
}
