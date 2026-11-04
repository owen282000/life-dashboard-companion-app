package com.owen282000.lifedashboard

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * The figures the Today panel may show. Nothing sensitive is in this list, so nothing sensitive
 * can reach the panel.
 */
enum class TodayKind(val dataType: HealthDataType) {
    STEPS(HealthDataType.STEPS),
    SLEEP(HealthDataType.SLEEP),
    RESTING_HEART_RATE(HealthDataType.RESTING_HEART_RATE),
    WEIGHT(HealthDataType.WEIGHT)
}

/** One day's value: steps in steps, sleep in minutes, resting heart rate in bpm, weight in kilograms. */
data class TodayDay(val date: LocalDate, val value: Double)

/** One figure of the panel. [series] is oldest first, with only the days that have a value, and is never empty. */
data class TodayStat(val kind: TodayKind, val series: List<TodayDay>) {
    val latest: TodayDay get() = series.last()
    val values: List<Double> get() = series.map { it.value }

    /** The value of [day] itself, null when it has none yet; sleep counts the night that ended that day. */
    fun valueOn(day: LocalDate = LocalDate.now()): Double? = series.lastOrNull { it.date == day }?.value
}

/** What the panel draws from: the figures of the enabled types that have data, and what to fall back on. */
data class TodayState(
    val stats: List<TodayStat> = emptyList(),
    /** Records the Health section delivered today, for when no figure fits. */
    val recordsToday: Int = 0,
    /** False until the first read is back, so the panel does not flash its fallback first. */
    val hasLoaded: Boolean = false
)

/** Turns what Health Connect answers into the panel's series, free of Android so it is tested on the JVM. */
object TodaySeries {
    const val DAYS = 7L
    const val WEIGHT_PAGE = 100

    /** A night runs from 18:00 the evening before to 18:00 on its day, so it counts as one night past midnight. */
    val NIGHT_STARTS_AT: LocalTime = LocalTime.of(18, 0)

    fun firstDay(today: LocalDate): LocalDate = today.minusDays(DAYS - 1)

    fun firstNightStart(today: LocalDate): LocalDateTime = firstDay(today).minusDays(1).atTime(NIGHT_STARTS_AT)

    /** The day a sleep bucket starting at [bucketStart] belongs to. */
    fun nightOf(bucketStart: LocalDateTime): LocalDate = bucketStart.toLocalDate().plusDays(1)

    /** A day without a record is a day without steps, unless the whole week is empty. */
    fun steps(days: List<Pair<LocalDate, Double?>>): List<TodayDay> =
        if (days.all { it.second == null }) emptyList() else days.map { (date, value) -> TodayDay(date, value ?: 0.0) }

    /** The days that have a value. */
    fun present(days: List<Pair<LocalDate, Double?>>): List<TodayDay> =
        days.mapNotNull { (date, value) -> value?.let { TodayDay(date, it) } }

    /** The nights with sleep in them. */
    fun sleep(nights: List<Pair<LocalDate, Double?>>): List<TodayDay> = present(nights).filter { it.value > 0 }

    /** Of several measurements on one day, the last one counts, oldest day first. */
    fun latestPerDay(measurements: List<Pair<LocalDateTime, Double>>): List<TodayDay> =
        measurements.groupBy { it.first.toLocalDate() }
            .map { (date, sameDay) -> TodayDay(date, sameDay.maxBy { it.first }.second) }
            .sortedBy { it.date }
}
