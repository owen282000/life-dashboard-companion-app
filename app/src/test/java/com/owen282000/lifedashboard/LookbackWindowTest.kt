package com.owen282000.lifedashboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

/**
 * The time range a sync reads per type: a week back from the last complete
 * read, so a pause does not push what a watch wrote before it out of reach, and never more
 * than 30 days, with the part that did not fit named instead of skipped in silence.
 */
class LookbackWindowTest {

    private val now = Instant.parse("2026-09-27T12:00:00Z")
    private val week = Duration.ofDays(7)

    @Test
    fun `without a complete read the range is the plain week, as before`() {
        // The first sync after the update, and a newly enabled type.
        val window = LookbackWindow.of(now, coveredUntil = null)
        assertEquals(now - week, window.start)
        assertNull(window.gapFrom)
    }

    @Test
    fun `a normal sync reads the week before the previous one, one interval wider`() {
        val previous = now - Duration.ofMinutes(15)
        val window = LookbackWindow.of(now, previous)
        assertEquals(previous - week, window.start)
        assertNull(window.gapFrom)
    }

    @Test
    fun `after a ten day pause the range reaches a week before the pause`() {
        // A record from the day before the pause that the watch uploaded during it is inside;
        // with the old range, which started three days after the pause began, it was lost.
        val previous = now - Duration.ofDays(10)
        val window = LookbackWindow.of(now, previous)
        assertEquals(previous - week, window.start)
        assertNull(window.gapFrom)
        val lateUpload = previous - Duration.ofDays(1)
        assertTrue(lateUpload >= window.start)
        assertTrue(lateUpload < now - week)
    }

    @Test
    fun `a pause that just fits reaches exactly thirty days back without a gap`() {
        val previous = now - LookbackWindow.MAX_REACH + week
        val window = LookbackWindow.of(now, previous)
        assertEquals(now - LookbackWindow.MAX_REACH, window.start)
        assertNull(window.gapFrom)
    }

    @Test
    fun `a longer pause stops at thirty days and names where the range should have started`() {
        val previous = now - Duration.ofDays(40)
        val window = LookbackWindow.of(now, previous)
        assertEquals(now - Duration.ofDays(30), window.start)
        assertEquals(previous - week, window.gapFrom)
    }

    @Test
    fun `a clock set back since the last read does not move the range forward`() {
        val window = LookbackWindow.of(now, coveredUntil = now + Duration.ofHours(2))
        assertEquals(now - week, window.start)
        assertNull(window.gapFrom)
    }

    @Test
    fun `only types read completely move their anchor`() {
        val enabled = setOf(HealthDataType.STEPS, HealthDataType.HEART_RATE, HealthDataType.WEIGHT, HealthDataType.SLEEP)
        val beforePause = now - Duration.ofDays(12)
        val covered = LookbackWindow.covered(
            enabled = enabled,
            capped = setOf(HealthDataType.HEART_RATE),
            unread = setOf(HealthDataType.WEIGHT),
            readEnd = now,
            anchors = enabled.associateWith { beforePause }
        )
        // A capped type still has a backlog, which may be older than the next range would be,
        // so it keeps its anchor; an unread type was not read at all and stores nothing.
        assertEquals(
            mapOf(HealthDataType.STEPS to now, HealthDataType.SLEEP to now, HealthDataType.HEART_RATE to beforePause),
            covered
        )
    }

    /** Stores what [covered] hands back, the way HealthSyncManager.updateSyncTimestamps does. */
    private fun store(stored: MutableMap<HealthDataType, Instant?>, covered: Map<HealthDataType, Instant>) {
        covered.forEach { (type, until) -> stored[type] = until }
    }

    @Test
    fun `a capped type keeps reading from before the pause until its backlog is drained`() {
        val hr = setOf(HealthDataType.HEART_RATE)
        val stored = mutableMapOf<HealthDataType, Instant?>(HealthDataType.HEART_RATE to now - Duration.ofDays(12))
        val first = LookbackWindow.of(now, stored[HealthDataType.HEART_RATE])
        // Pass one of the first sync after the pause is capped.
        store(stored, LookbackWindow.covered(hr, capped = hr, unread = emptySet(), readEnd = now, anchors = stored))
        // The next sync, an interval later, is capped too and starts where the first did.
        val later = now + Duration.ofMinutes(15)
        assertEquals(first.start, LookbackWindow.of(later, stored[HealthDataType.HEART_RATE]).start)
        store(stored, LookbackWindow.covered(hr, capped = hr, unread = emptySet(), readEnd = later, anchors = stored))
        // The sync that drains it moves the anchor on.
        val drained = later + Duration.ofMinutes(15)
        store(stored, LookbackWindow.covered(hr, capped = emptySet(), unread = emptySet(), readEnd = drained, anchors = stored))
        assertEquals(drained, stored[HealthDataType.HEART_RATE])
    }

    @Test
    fun `a capped type without an anchor stops its range from sliding while the backlog drains`() {
        // The first sync after the update, or a type never read completely: no stored anchor.
        // The range must not trail "now" from one capped sync to the next, or the oldest part
        // of the backlog falls out of it while the watermark moves past it.
        val hr = setOf(HealthDataType.HEART_RATE)
        val stored = mutableMapOf<HealthDataType, Instant?>()
        val first = LookbackWindow.of(now, stored[HealthDataType.HEART_RATE])
        store(stored, LookbackWindow.covered(hr, capped = hr, unread = emptySet(), readEnd = now, anchors = stored))

        val later = now + Duration.ofHours(6)
        assertEquals(first.start, LookbackWindow.of(later, stored[HealthDataType.HEART_RATE]).start)
    }

    @Test
    fun `an unread type without an anchor stays without one`() {
        // A type that cannot be read (no permission) would otherwise pin its range to its first
        // sync and name a lookback gap three weeks later for data it never had access to.
        val weight = setOf(HealthDataType.WEIGHT)
        assertEquals(emptyMap<HealthDataType, Instant>(), LookbackWindow.covered(weight, emptySet(), unread = weight, readEnd = now))
    }

    @Test
    fun `a sync without a payload keeps the anchor of a type whose gap it could not name`() {
        val anchors = mapOf(HealthDataType.STEPS to now, HealthDataType.WEIGHT to now)
        val diagnostics = mapOf(
            HealthDataType.STEPS to diagnostics(gapFrom = null),
            HealthDataType.WEIGHT to diagnostics(gapFrom = now - Duration.ofDays(47))
        )
        // Weight keeps its old anchor, so the next sync names the same gap in its payload.
        assertEquals(mapOf(HealthDataType.STEPS to now), LookbackWindow.keepingGapsOpen(anchors, diagnostics))
    }

    @Test
    fun `a gap stays named from one empty sync to the next until a payload carries it`() {
        val beforePause = now - Duration.ofDays(40)
        val stored = mutableMapOf<HealthDataType, Instant?>(HealthDataType.WEIGHT to beforePause)
        val weight = setOf(HealthDataType.WEIGHT)
        val window = LookbackWindow.of(now, stored[HealthDataType.WEIGHT])
        val covered = LookbackWindow.covered(weight, emptySet(), emptySet(), now, stored)
        store(stored, LookbackWindow.keepingGapsOpen(covered, mapOf(HealthDataType.WEIGHT to diagnostics(window.gapFrom))))

        val later = now + Duration.ofMinutes(15)
        assertEquals(window.gapFrom, LookbackWindow.of(later, stored[HealthDataType.WEIGHT]).gapFrom)
    }

    @Test
    fun `a change timestamped before the range is counted, with the range it needs`() {
        // A watch away from the phone for ten days uploads while syncs run normally: the range
        // starts a week back, so the three oldest days are never read.
        val readFrom = now - week
        val times = listOf(now - Duration.ofDays(10), now - Duration.ofDays(8), now - Duration.ofDays(2), now - Duration.ofHours(1))
        val outside = LookbackWindow.outside(times, readFrom)
        assertEquals(OutsideWindow(2, (now - Duration.ofDays(10)).toEpochMilli(), readFrom.toEpochMilli()), outside)
    }

    @Test
    fun `changes inside the range are not counted`() {
        val readFrom = now - week
        assertNull(LookbackWindow.outside(listOf(readFrom, now - Duration.ofDays(1)), readFrom))
        assertNull(LookbackWindow.outside(emptyList(), readFrom))
    }

    @Test
    fun `counts from two syncs join into one range`() {
        val a = OutsideWindow(2, 100, 1_000)
        val b = OutsideWindow(3, 50, 2_000)
        assertEquals(OutsideWindow(5, 50, 2_000), a + b)
    }

    private fun diagnostics(gapFrom: Instant?) = TypeDiagnostics(
        permissionGranted = true,
        pageCount = 0,
        rawRecordCount = 0,
        filteredRecordCount = 0,
        minTime = null,
        maxTime = null,
        lastSync = null,
        error = null,
        lookbackGapFrom = gapFrom
    )
}
