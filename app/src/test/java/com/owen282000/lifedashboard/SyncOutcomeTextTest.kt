package com.owen282000.lifedashboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** What TalkBack hears for the green or red last-sync dot on the widget and the dashboard. */
class SyncOutcomeTextTest {

    private fun status(lastSync: Long?, success: Boolean) = SyncStatusStore.Status(lastSync, success, recordsToday = 0)

    @Test
    fun aFailedSyncIsSaidToHaveFailed() {
        val outcome = status(lastSync = 1_000L, success = false).outcome
        assertEquals(SyncStatusStore.Outcome.FAILED, outcome)
        assertEquals(R.string.widget_last_sync_failed, SyncOutcomeText.widgetRes(outcome))
        assertEquals(R.string.dashboard_last_sync_failed, SyncOutcomeText.dashboardRes(outcome))
    }

    @Test
    fun aGoodSyncIsSaidToHaveSucceeded() {
        val outcome = status(lastSync = 1_000L, success = true).outcome
        assertEquals(SyncStatusStore.Outcome.SUCCEEDED, outcome)
        assertEquals(R.string.widget_last_sync_succeeded, SyncOutcomeText.widgetRes(outcome))
        assertEquals(R.string.dashboard_last_sync_succeeded, SyncOutcomeText.dashboardRes(outcome))
    }

    @Test
    fun beforeTheFirstSyncTheDotSaysNothing() {
        // The store reports success until a sync says otherwise, which is no outcome to read out:
        // the text next to the dot already says there has been no sync.
        val outcome = status(lastSync = null, success = true).outcome
        assertEquals(SyncStatusStore.Outcome.NEVER, outcome)
        assertNull(SyncOutcomeText.widgetRes(outcome))
        assertNull(SyncOutcomeText.dashboardRes(outcome))
    }
}
