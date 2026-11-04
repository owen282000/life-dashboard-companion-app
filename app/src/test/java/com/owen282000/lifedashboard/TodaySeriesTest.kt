package com.owen282000.lifedashboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class TodaySeriesTest {

    private val monday = LocalDate.of(2026, 11, 2)

    @Test
    fun `the panel only knows four kinds, none of them sensitive`() {
        assertEquals(
            setOf(HealthDataType.STEPS, HealthDataType.SLEEP, HealthDataType.RESTING_HEART_RATE, HealthDataType.WEIGHT),
            TodayKind.entries.map { it.dataType }.toSet()
        )
    }

    @Test
    fun `a day without steps counts as none, unless the whole week is empty`() {
        val series = TodaySeries.steps(listOf(monday to 100.0, monday.plusDays(1) to null, monday.plusDays(2) to 300.0))
        assertEquals(listOf(100.0, 0.0, 300.0), series.map { it.value })
        assertEquals(monday.plusDays(1), series[1].date)
        assertTrue(TodaySeries.steps(listOf(monday to null, monday.plusDays(1) to null)).isEmpty())
    }

    @Test
    fun `heart rate leaves out the days without a value`() {
        val series = TodaySeries.present(listOf(monday to 55.0, monday.plusDays(1) to null, monday.plusDays(2) to 57.0))
        assertEquals(listOf(monday, monday.plusDays(2)), series.map { it.date })
    }

    @Test
    fun `a night counts for the day it ends on, and an empty one is left out`() {
        assertEquals(monday, TodaySeries.nightOf(monday.minusDays(1).atTime(18, 0)))
        val series = TodaySeries.sleep(listOf(monday to 420.0, monday.plusDays(1) to 0.0, monday.plusDays(2) to null))
        assertEquals(listOf(TodayDay(monday, 420.0)), series)
    }

    @Test
    fun `the window is seven days, the first night starting the evening before`() {
        val today = LocalDate.of(2026, 11, 8)
        assertEquals(LocalDate.of(2026, 11, 2), TodaySeries.firstDay(today))
        assertEquals(LocalDateTime.of(2026, 11, 1, 18, 0), TodaySeries.firstNightStart(today))
    }

    @Test
    fun `of several weights on a day the last one counts, oldest day first`() {
        val series = TodaySeries.latestPerDay(
            listOf(
                monday.plusDays(1).atTime(8, 0) to 81.0,
                monday.atTime(20, 0) to 80.5,
                monday.atTime(7, 0) to 80.0
            )
        )
        assertEquals(listOf(TodayDay(monday, 80.5), TodayDay(monday.plusDays(1), 81.0)), series)
    }

    @Test
    fun `a stat says today's value only for today`() {
        val stat = TodayStat(TodayKind.STEPS, listOf(TodayDay(monday, 10.0), TodayDay(monday.plusDays(1), 20.0)))
        assertEquals(20.0, stat.valueOn(monday.plusDays(1))!!, 0.0)
        assertEquals(null, stat.valueOn(monday.plusDays(2)))
        assertEquals(listOf(10.0, 20.0), stat.values)
    }
}
