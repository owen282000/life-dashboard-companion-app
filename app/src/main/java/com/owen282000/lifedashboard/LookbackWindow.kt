package com.owen282000.lifedashboard

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
     * The types a read at [readEnd] took completely: every enabled type that was neither cut by
     * the per-sync cap (the rest of its backlog may be older than the next range) nor left
     * unread. Only these move their [of] anchor to [readEnd].
     */
    fun covered(
        enabled: Set<HealthDataType>,
        capped: Set<HealthDataType>,
        unread: Set<HealthDataType>,
        readEnd: Instant
    ): Map<HealthDataType, Instant> = (enabled - capped - unread).associateWith { readEnd }
}
