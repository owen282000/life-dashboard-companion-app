package com.owen282000.lifedashboard.sync

import androidx.health.connect.client.records.StepsRecord
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.owen282000.lifedashboard.HealthConnectManager
import com.owen282000.lifedashboard.HealthDataType.STEPS
import com.owen282000.lifedashboard.TodayKind
import com.owen282000.lifedashboard.harness.AppStateRule
import com.owen282000.lifedashboard.harness.HcFixture
import com.owen282000.lifedashboard.harness.TestSetup
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Today's totals with a record that runs to the end of the day, as Samsung Health writes a day's
 * steps: one record from 00:00 to 23:59 whose count it keeps updating (issue #246). Health
 * Connect's aggregate counts only the part of a record inside the asked range, so asking up to
 * now gave 16h40 of 24h of it at 16:40. Short records, as every other source writes them, keep
 * counting as before.
 */
@RunWith(AndroidJUnit4::class)
class DailyTotalsTodayTest {

    @get:Rule
    val state = AppStateRule()

    private val context = TestSetup.context
    private val fixture = HcFixture(context)
    private val zone = ZoneId.systemDefault()

    private fun at(time: LocalDateTime): Instant = time.atZone(zone).toInstant()

    /** The whole of [day] as one record, 00:00 to 23:59, the way Samsung Health writes it. */
    private fun wholeDay(count: Long, day: LocalDate) {
        fixture.insert(fixture.steps(count, at(day.atStartOfDay()), at(day.atTime(23, 59))))
    }

    /** Far enough from midnight that "today" is the same day for the whole test. */
    private fun notAroundMidnight() {
        val now = LocalDateTime.now()
        assumeTrue("too close to midnight", now.hour >= 1 && now.isBefore(now.toLocalDate().atTime(23, 50)))
    }

    private fun todaysSteps(): Long? = runBlocking {
        HealthConnectManager(context).readDailyTotals(days = 0, enabledTypes = setOf(STEPS))
            .singleOrNull { it.date == LocalDate.now().toString() }?.steps
    }

    @Test
    fun aRecordForTheWholeDayCountsInFullToday() {
        notAroundMidnight()
        fixture.assertNoForeignRecords(StepsRecord::class)
        wholeDay(21_482, LocalDate.now())
        assertEquals(21_482L, todaysSteps())
    }

    @Test
    fun shortRecordsTodayCountAsBefore() {
        notAroundMidnight()
        fixture.assertNoForeignRecords(StepsRecord::class)
        val start = LocalDate.now().atTime(0, 10)
        fixture.insert(fixture.steps(120, at(start), at(start.plusMinutes(1))))
        fixture.insert(fixture.steps(80, at(start.plusMinutes(5)), at(start.plusMinutes(6))))
        assertEquals(200L, todaysSteps())
    }

    @Test
    fun aRangeThatCutsADayGetsTheWholeDay() {
        fixture.assertNoForeignRecords(StepsRecord::class)
        val day = LocalDate.now().minusDays(3)
        wholeDay(9_000, day)
        val totals = runBlocking {
            HealthConnectManager(context).readDailyTotalsBetween(day.atTime(12, 0), day.atTime(13, 0), setOf(STEPS))
        }
        assertEquals(listOf(day.toString() to 9_000L), totals.map { it.date to it.steps })
    }

    @Test
    fun aBackfillWindowEndingTodayGetsTodayInFull() {
        notAroundMidnight()
        fixture.assertNoForeignRecords(StepsRecord::class)
        val today = LocalDate.now()
        wholeDay(7_000, today.minusDays(1))
        wholeDay(5_000, today)
        val totals = runBlocking {
            HealthConnectManager(context).readDailyTotalsBetween(today.minusDays(1).atStartOfDay(), LocalDateTime.now(), setOf(STEPS))
        }
        assertEquals(listOf(today.minusDays(1).toString() to 7_000L, today.toString() to 5_000L), totals.map { it.date to it.steps })
    }

    @Test
    fun theTodayPanelShowsTheWholeDay() {
        notAroundMidnight()
        fixture.assertNoForeignRecords(StepsRecord::class)
        wholeDay(21_482, LocalDate.now())
        val steps = runBlocking { HealthConnectManager(context).readTodaySeries(setOf(TodayKind.STEPS)) }[TodayKind.STEPS]
        assertEquals(21_482.0, steps?.last { it.date == LocalDate.now() }?.value)
    }
}
