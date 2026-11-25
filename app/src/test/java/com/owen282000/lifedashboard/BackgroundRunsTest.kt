package com.owen282000.lifedashboard

import androidx.work.WorkInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BackgroundRunsTest {

    private val start = 1_789_380_000_000L

    private fun run(source: LogType, outcome: BackgroundRun.Outcome, minute: Int, reason: BackgroundRun.StopReason? = null): BackgroundRun {
        val moment = start + minute * 60_000L
        return BackgroundRun(source.name, outcome.name, moment, moment, durationSeconds = minute.toDouble(), stopReason = reason?.name)
    }

    private fun adding(vararg runs: BackgroundRun): List<BackgroundRun> =
        runs.fold(emptyList()) { entries, run -> BackgroundRunLog.adding(run, entries) }

    @Test
    fun runsWithNothingNewBecomeOneEntryWithACount() {
        val entries = adding(*Array(12) { run(LogType.HEALTH_CONNECT, BackgroundRun.Outcome.NOTHING_NEW, it * 15) })

        assertEquals(1, entries.size)
        assertEquals(12, entries[0].count)
        assertEquals(start, entries[0].first)
        assertEquals(start + 165 * 60_000L, entries[0].last)
        assertEquals("the duration is the last run's", 165.0, entries[0].durationSeconds)
    }

    @Test
    fun bothSourcesMergeEachOnTheirOwnWhileTheyAlternate() {
        val entries = adding(
            run(LogType.HEALTH_CONNECT, BackgroundRun.Outcome.NOTHING_NEW, 0),
            run(LogType.SCREEN_TIME, BackgroundRun.Outcome.NOTHING_NEW, 1),
            run(LogType.HEALTH_CONNECT, BackgroundRun.Outcome.NOTHING_NEW, 15),
            run(LogType.SCREEN_TIME, BackgroundRun.Outcome.NOTHING_NEW, 16),
            run(LogType.HEALTH_CONNECT, BackgroundRun.Outcome.NOTHING_NEW, 30)
        )

        assertEquals(listOf(LogType.HEALTH_CONNECT.name, LogType.SCREEN_TIME.name), entries.map { it.source })
        assertEquals(listOf(3, 2), entries.map { it.count })
        assertEquals("the newest stays on top", start + 30 * 60_000L, entries[0].last)
    }

    @Test
    fun anotherOutcomeInBetweenStartsANewEntry() {
        val entries = adding(
            run(LogType.HEALTH_CONNECT, BackgroundRun.Outcome.NOTHING_NEW, 0),
            run(LogType.HEALTH_CONNECT, BackgroundRun.Outcome.SYNCED, 15),
            run(LogType.HEALTH_CONNECT, BackgroundRun.Outcome.NOTHING_NEW, 30)
        )

        assertEquals(
            listOf(BackgroundRun.Outcome.NOTHING_NEW, BackgroundRun.Outcome.SYNCED, BackgroundRun.Outcome.NOTHING_NEW),
            entries.map { it.outcomeType }
        )
        assertTrue(entries.all { it.count == 1 })
    }

    @Test
    fun syncedFailedAndStoppedEachKeepAnEntryOfTheirOwn() {
        val outcomes = listOf(BackgroundRun.Outcome.SYNCED, BackgroundRun.Outcome.FAILED, BackgroundRun.Outcome.STOPPED)
        val entries = adding(*outcomes.flatMap { listOf(it, it) }.mapIndexed { i, outcome -> run(LogType.SCREEN_TIME, outcome, i) }.toTypedArray())

        assertEquals(6, entries.size)
        assertTrue(entries.all { it.count == 1 })
    }

    @Test
    fun theListKeepsTheNewestFifty() {
        val entries = adding(*Array(70) { run(LogType.HEALTH_CONNECT, BackgroundRun.Outcome.SYNCED, it) })

        assertEquals(BackgroundRunLog.MAX_ENTRIES, entries.size)
        assertEquals(start + 69 * 60_000L, entries.first().last)
        assertEquals(start + 20 * 60_000L, entries.last().last)
    }

    @Test
    fun theStoreKeepsWhatItRecordsAndClearsIt() {
        val prefs = InMemoryPrefs()
        val store = BackgroundRunStore(prefs)
        store.record(run(LogType.HEALTH_CONNECT, BackgroundRun.Outcome.STOPPED, 0, BackgroundRun.StopReason.QUOTA))
        store.record(run(LogType.HEALTH_CONNECT, BackgroundRun.Outcome.NOTHING_NEW, 15))
        store.record(run(LogType.HEALTH_CONNECT, BackgroundRun.Outcome.NOTHING_NEW, 30))

        val entries = BackgroundRunStore(prefs).load()
        assertEquals(2, entries.size)
        assertEquals(2, entries[0].count)
        assertEquals(BackgroundRun.StopReason.QUOTA, entries[1].stopReasonType)

        store.clear()
        assertTrue(store.load().isEmpty())
    }

    @Test
    fun anEntryFromANewerBuildStillDecodes() {
        val prefs = InMemoryPrefs()
        prefs.edit().putString(
            "background_runs",
            """[{"source":"STEPS_RING","outcome":"DEFERRED","first":1,"last":2,"stopReason":"SOMETHING_NEW","newField":true},""" +
                """{"source":"SCREEN_TIME","outcome":"SYNCED","first":3,"last":3}]"""
        ).apply()

        val entries = BackgroundRunStore(prefs).load()
        assertEquals(2, entries.size)
        assertNull(entries[0].sourceType)
        assertNull(entries[0].outcomeType)
        assertNull(entries[0].stopReasonType)
        assertEquals("STEPS_RING", entries[0].source)
        assertEquals(LogType.SCREEN_TIME, entries[1].sourceType)
        assertEquals(1, entries[1].count)
        assertNull(entries[1].durationSeconds)
    }

    @Test
    fun storedTextThatIsNoListReadsAsEmpty() {
        val prefs = InMemoryPrefs()
        prefs.edit().putString("background_runs", "{not json").apply()

        assertTrue(BackgroundRunStore(prefs).load().isEmpty())
    }

    @Test
    fun anUnknownOutcomeIsNeverMerged() {
        val unknown = BackgroundRun(LogType.HEALTH_CONNECT.name, "DEFERRED", start, start)
        val entries = adding(unknown, unknown)

        assertEquals(2, entries.size)
    }

    @Test
    fun everyStopReasonOfWorkManagerHasAGroup() {
        val expected = mapOf(
            WorkInfo.STOP_REASON_DEVICE_STATE to BackgroundRun.StopReason.BATTERY,
            WorkInfo.STOP_REASON_BACKGROUND_RESTRICTION to BackgroundRun.StopReason.RESTRICTED,
            WorkInfo.STOP_REASON_APP_STANDBY to BackgroundRun.StopReason.RESTRICTED,
            WorkInfo.STOP_REASON_QUOTA to BackgroundRun.StopReason.QUOTA,
            WorkInfo.STOP_REASON_CONSTRAINT_BATTERY_NOT_LOW to BackgroundRun.StopReason.CONSTRAINTS,
            WorkInfo.STOP_REASON_CONSTRAINT_CHARGING to BackgroundRun.StopReason.CONSTRAINTS,
            WorkInfo.STOP_REASON_CONSTRAINT_CONNECTIVITY to BackgroundRun.StopReason.CONSTRAINTS,
            WorkInfo.STOP_REASON_CONSTRAINT_DEVICE_IDLE to BackgroundRun.StopReason.CONSTRAINTS,
            WorkInfo.STOP_REASON_CONSTRAINT_STORAGE_NOT_LOW to BackgroundRun.StopReason.CONSTRAINTS,
            WorkInfo.STOP_REASON_TIMEOUT to BackgroundRun.StopReason.TIME_LIMIT,
            WorkInfo.STOP_REASON_FOREGROUND_SERVICE_TIMEOUT to BackgroundRun.StopReason.TIME_LIMIT,
            WorkInfo.STOP_REASON_PREEMPT to BackgroundRun.StopReason.OTHER,
            WorkInfo.STOP_REASON_CANCELLED_BY_APP to BackgroundRun.StopReason.OTHER,
            WorkInfo.STOP_REASON_USER to BackgroundRun.StopReason.OTHER,
            WorkInfo.STOP_REASON_SYSTEM_PROCESSING to BackgroundRun.StopReason.OTHER,
            WorkInfo.STOP_REASON_ESTIMATED_APP_LAUNCH_TIME_CHANGED to BackgroundRun.StopReason.OTHER,
            WorkInfo.STOP_REASON_UNKNOWN to BackgroundRun.StopReason.OTHER,
            WorkInfo.STOP_REASON_NOT_STOPPED to BackgroundRun.StopReason.OTHER,
            999 to BackgroundRun.StopReason.OTHER
        )
        expected.forEach { (reason, group) -> assertEquals("reason $reason", group, BackgroundRun.stopReasonOf(reason)) }
    }

    @Test
    fun aSyncResultNamesItsOutcome() {
        assertEquals(BackgroundRun.Outcome.SYNCED, BackgroundRun.outcomeOf(Result.success<HealthSyncResult>(HealthSyncResult.Success(emptyMap()))))
        assertEquals(BackgroundRun.Outcome.NOTHING_NEW, BackgroundRun.outcomeOf(Result.success<HealthSyncResult>(HealthSyncResult.NoData)))
        assertEquals(BackgroundRun.Outcome.FAILED, BackgroundRun.outcomeOf(Result.success<HealthSyncResult>(HealthSyncResult.Queued(3))))
        assertEquals(BackgroundRun.Outcome.FAILED, BackgroundRun.outcomeOf(Result.failure<HealthSyncResult>(Exception("No webhook URLs configured"))))
        assertEquals(BackgroundRun.Outcome.SYNCED, BackgroundRun.outcomeOf(Result.success<ScreenTimeSyncResult>(ScreenTimeSyncResult.Success(4, 7))))
        assertEquals(BackgroundRun.Outcome.NOTHING_NEW, BackgroundRun.outcomeOf(Result.success<ScreenTimeSyncResult>(ScreenTimeSyncResult.NoData)))
        assertEquals(BackgroundRun.Outcome.FAILED, BackgroundRun.outcomeOf(Result.success<ScreenTimeSyncResult>(ScreenTimeSyncResult.Queued(4))))
    }

    @Test
    fun aRunWithoutBackgroundAccessKeepsItsReasonAndDoesNotMergeWithAPlainOne() {
        val plain = run(LogType.HEALTH_CONNECT, BackgroundRun.Outcome.NOTHING_NEW, 0)
        val blocked = run(LogType.HEALTH_CONNECT, BackgroundRun.Outcome.NOTHING_NEW, 15)
            .copy(readProblem = BackgroundRun.ReadProblem.NO_BACKGROUND_ACCESS.name)
        val entries = adding(plain, blocked, blocked.copy(first = blocked.first + 900_000L, last = blocked.last + 900_000L))

        assertEquals(2, entries.size)
        assertEquals(BackgroundRun.ReadProblem.NO_BACKGROUND_ACCESS, entries[0].readProblemType)
        assertEquals(2, entries[0].count)
        assertNull(entries[1].readProblemType)
    }

    @Test
    fun backgroundAccessIsMissingOnlyWhenItCouldBeGrantedAndWouldMatter() {
        val steps = "android.permission.health.READ_STEPS"
        assertTrue(BackgroundAccess.missing(setOf(steps), readsTypes = true, featureAvailable = true))
        assertEquals(false, BackgroundAccess.missing(setOf(steps, HealthConnectManager.BACKGROUND_PERMISSION), readsTypes = true, featureAvailable = true))
        assertEquals("an older Health Connect has nothing to grant", false, BackgroundAccess.missing(setOf(steps), readsTypes = true, featureAvailable = false))
        assertEquals("a phone that only receives reads nothing", false, BackgroundAccess.missing(setOf(steps), readsTypes = false, featureAvailable = true))
        assertEquals("without any access the screen asks for that first", false, BackgroundAccess.missing(emptySet(), readsTypes = true, featureAvailable = true))
    }
}
