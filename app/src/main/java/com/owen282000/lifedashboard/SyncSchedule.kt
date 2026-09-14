package com.owen282000.lifedashboard

import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * When the next background sync should run. Two modes: a fixed interval (what the app has
 * always done) or a list of times of day. Both can be narrowed by a weekday filter and a
 * quiet window, so "every hour, on weekdays, but not at night" is expressible either way.
 *
 * Deliberately free of Android types: [SyncSchedule.nextRun] is the whole scheduling decision
 * and is unit tested on the JVM. [SyncScheduler] turns the result into WorkManager work.
 */
enum class SyncMode { INTERVAL, TIMES }

data class QuietWindow(val from: LocalTime, val to: LocalTime) {
    /**
     * True when [time] falls inside the window. A window that wraps midnight (23:00 to 07:00)
     * covers both sides; one that does not (13:00 to 14:00) covers the plain range. The end is
     * exclusive, so a sync scheduled exactly at the end of the window still runs. Equal ends
     * mean no window at all.
     */
    fun contains(time: LocalTime): Boolean =
        if (from == to) false
        else if (from < to) time >= from && time < to
        else time >= from || time < to
}

data class SyncSchedule(
    val mode: SyncMode = SyncMode.INTERVAL,
    val intervalMinutes: Int = DEFAULT_INTERVAL_MINUTES,
    /** Times of day for [SyncMode.TIMES], in any order; duplicates and order do not matter. */
    val times: List<LocalTime> = emptyList(),
    /** Days the sync may run. Empty means "no day", which the UI refuses to save. */
    val days: Set<DayOfWeek> = DayOfWeek.entries.toSet(),
    val quietWindow: QuietWindow? = null
) {

    /** True when nothing is configured to run at all, before the filters are even applied. */
    private val hasNothingToRun: Boolean
        get() = days.isEmpty() || (mode == SyncMode.TIMES && times.isEmpty())

    /**
     * True when this schedule can never produce a run, so the UI can warn instead of going
     * silent. Covers the empty cases and the subtler one where every configured time falls
     * inside the quiet window.
     */
    val isNeverRunning: Boolean
        get() = hasNothingToRun || nextRun(REFERENCE_MOMENT) == null

    /**
     * The first moment at or after [after] that satisfies mode, weekdays and quiet window, or
     * null when the schedule can never run.
     *
     * [lastRun] is the previous scheduled run. Interval mode counts from it, so restarting the
     * app does not restart the clock and a run that is already overdue comes back as [after]
     * itself. Times mode uses it as a floor of one minute past the last run, so a run that
     * fired a few seconds early can never be followed by a second one for the same slot.
     */
    fun nextRun(after: LocalDateTime, lastRun: LocalDateTime? = null): LocalDateTime? {
        if (hasNothingToRun) return null
        val candidate = when (mode) {
            SyncMode.INTERVAL -> intervalCandidate(after, lastRun)
            SyncMode.TIMES -> maxOf(after, lastRun?.plusMinutes(1) ?: after)
        }
        return firstAllowed(candidate)
    }

    /** Interval mode: [intervalMinutes] after the last run, or right now when there was none. */
    private fun intervalCandidate(after: LocalDateTime, lastRun: LocalDateTime?): LocalDateTime {
        val due = lastRun?.plusMinutes(intervalMinutes.toLong()) ?: after
        return maxOf(due, after)
    }

    /**
     * Walks forward from [candidate] until a moment passes both filters.
     *
     * The two modes treat a quiet hit differently on purpose. Interval mode has no preferred
     * moment, so it resumes at the end of the window. Times mode does: moving 03:00 to 07:00
     * would invent a run the user never asked for, so a scheduled time inside the window is
     * skipped and the next configured time is tried instead. A schedule whose every time is
     * quiet never runs, which [isNeverRunning] reports to the UI.
     */
    private fun firstAllowed(candidate: LocalDateTime): LocalDateTime? {
        var current = candidate
        repeat(MAX_HOPS) {
            if (mode == SyncMode.TIMES) {
                current = nextScheduledTime(current) ?: return null
            }
            if (current.dayOfWeek !in days) {
                current = current.toLocalDate().plusDays(1).atStartOfDay()
                return@repeat
            }
            val quiet = quietWindow
            if (quiet != null && quiet.contains(current.toLocalTime())) {
                current = when (mode) {
                    SyncMode.INTERVAL -> endOfQuietWindow(current, quiet)
                    SyncMode.TIMES -> current.plusMinutes(1)
                }
                return@repeat
            }
            return current
        }
        return null
    }

    /** The first configured time at or after [from], looking into the following days if needed. */
    private fun nextScheduledTime(from: LocalDateTime): LocalDateTime? {
        val sorted = times.distinct().sorted()
        if (sorted.isEmpty()) return null
        val sameDay = sorted.firstOrNull { it >= from.toLocalTime() }
        return if (sameDay != null) from.toLocalDate().atTime(sameDay)
        else from.toLocalDate().plusDays(1).atTime(sorted.first())
    }

    /** The moment the quiet window ends, on this day or the next when it wraps midnight. */
    private fun endOfQuietWindow(current: LocalDateTime, quiet: QuietWindow): LocalDateTime {
        val sameDay = current.toLocalDate().atTime(quiet.to)
        return if (sameDay > current) sameDay else current.toLocalDate().plusDays(1).atTime(quiet.to)
    }

    /** Delay until the next run, never negative, or null when the schedule never runs. */
    fun delayFrom(now: LocalDateTime, lastRun: LocalDateTime? = null): Duration? =
        nextRun(now, lastRun)?.let { maxOf(Duration.between(now, it), Duration.ZERO) }

    companion object {
        const val DEFAULT_INTERVAL_MINUTES = 60

        /** WorkManager refuses periodic work under 15 minutes; the same floor keeps both modes honest. */
        const val MIN_INTERVAL_MINUTES = 15

        /**
         * Upper bound on the search. Every hop moves to the next day, the end of the quiet
         * window or the next configured time, so a schedule that runs at all is found within
         * a week's worth of hops; the bound only exists to make "never" terminate. Generous
         * on purpose: a times schedule with many quiet slots needs one hop per slot per day.
         */
        private const val MAX_HOPS = 2000

        /**
         * A fixed Monday midnight for [isNeverRunning]. Asking "does a run exist" from a
         * constant keeps the property pure: the answer depends only on the schedule, never
         * on when it is asked, so a Sunday does not make a weekday schedule look broken.
         */
        private val REFERENCE_MOMENT: LocalDateTime = LocalDateTime.of(2024, 1, 1, 0, 0)

        /** "07:30,21:00" to a list of times; anything unparseable is dropped. */
        fun parseTimes(text: String): List<LocalTime> = text.split(',')
            .mapNotNull { part ->
                val trimmed = part.trim()
                if (trimmed.isEmpty()) null else runCatching { LocalTime.parse(trimmed) }.getOrNull()
            }
            .distinct()
            .sorted()

        fun formatTimes(times: List<LocalTime>): String =
            times.distinct().sorted().joinToString(",") { it.toString() }

        /**
         * "MONDAY,TUESDAY" to a set. Null (nothing stored yet) means every day; an empty
         * string is an empty set, so [formatDays] and this are exact inverses.
         */
        fun parseDays(text: String?): Set<DayOfWeek> {
            if (text == null) return DayOfWeek.entries.toSet()
            return text.split(',')
                .mapNotNull { part -> runCatching { DayOfWeek.valueOf(part.trim()) }.getOrNull() }
                .toSet()
        }

        fun formatDays(days: Set<DayOfWeek>): String =
            DayOfWeek.entries.filter { it in days }.joinToString(",") { it.name }
    }
}

/** Monday to Friday, the one preset the schedule UI and its tests refer to by name. */
val WEEKDAYS: Set<DayOfWeek> = setOf(
    DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY
)
