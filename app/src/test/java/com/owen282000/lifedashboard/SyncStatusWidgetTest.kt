package com.owen282000.lifedashboard

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class SyncStatusWidgetTest {
    private val zone = ZoneId.of("Europe/Amsterdam")
    private val today = LocalDate.of(2026, 11, 4)
    private fun millis(at: LocalDateTime) = at.atZone(zone).toInstant().toEpochMilli()

    @Test
    fun `a time alone says when only for a sync of today`() {
        assertTrue(SyncStatusWidget.syncedOnDay(millis(today.atTime(0, 1)), today, zone))
        assertTrue(SyncStatusWidget.syncedOnDay(millis(today.atTime(23, 59)), today, zone))
        assertFalse(SyncStatusWidget.syncedOnDay(millis(today.minusDays(1).atTime(23, 59)), today, zone))
    }
}
