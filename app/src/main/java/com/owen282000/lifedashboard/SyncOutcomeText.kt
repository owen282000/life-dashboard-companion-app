package com.owen282000.lifedashboard

/**
 * The words behind the green or red last-sync dot, for TalkBack. The widget's dot stands
 * alone, so it says the whole thing; the dashboard's sits under "Last sync", so it says only
 * the outcome. Before the first sync there is no outcome, and the text already says so.
 */
object SyncOutcomeText {
    fun widgetRes(outcome: SyncStatusStore.Outcome): Int? = when (outcome) {
        SyncStatusStore.Outcome.NEVER -> null
        SyncStatusStore.Outcome.SUCCEEDED -> R.string.widget_last_sync_succeeded
        SyncStatusStore.Outcome.FAILED -> R.string.widget_last_sync_failed
    }

    fun dashboardRes(outcome: SyncStatusStore.Outcome): Int? = when (outcome) {
        SyncStatusStore.Outcome.NEVER -> null
        SyncStatusStore.Outcome.SUCCEEDED -> R.string.dashboard_last_sync_succeeded
        SyncStatusStore.Outcome.FAILED -> R.string.dashboard_last_sync_failed
    }
}
