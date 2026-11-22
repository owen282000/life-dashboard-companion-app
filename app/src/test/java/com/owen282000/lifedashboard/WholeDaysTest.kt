package com.owen282000.lifedashboard

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class WholeDaysTest {

    private val day = LocalDate.of(2026, 11, 22)

    @Test
    fun `a range up to now runs to the next midnight`() {
        assertEquals(
            day.minusDays(2).atStartOfDay() to day.plusDays(1).atStartOfDay(),
            WholeDays.of(day.minusDays(2).atStartOfDay(), day.atTime(16, 40))
        )
    }

    @Test
    fun `a range that already ends at midnight stays as it is`() {
        assertEquals(
            day.minusDays(1).atStartOfDay() to day.atStartOfDay(),
            WholeDays.of(day.minusDays(1).atStartOfDay(), day.atStartOfDay())
        )
    }

    @Test
    fun `a start in the middle of a day goes back to its midnight`() {
        assertEquals(day.atStartOfDay() to day.plusDays(1).atStartOfDay(), WholeDays.of(day.atTime(12, 0), day.atTime(13, 0)))
    }

    @Test
    fun `one second past midnight takes that day in full`() {
        assertEquals(
            day.atStartOfDay() to day.plusDays(1).atStartOfDay(),
            WholeDays.of(day.atStartOfDay(), LocalDateTime.of(day, java.time.LocalTime.of(0, 0, 1)))
        )
    }
}
