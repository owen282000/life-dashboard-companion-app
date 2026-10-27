package com.owen282000.lifedashboard

/**
 * When to ask for a rating on Google Play (P1-28), the same rule as the iPhone app. Only after
 * something went right, never next to an error: the last sync, within a day, reached every
 * address and nothing is failing or queued, or a backfill just finished. Not in the first three
 * days of use, and once per version at most. Play keeps a quota of its own on top of that and
 * decides itself whether the dialog appears.
 *
 * Pure, so it is tested on the JVM; [ReviewMoment] reads the state from the stores. Only the
 * Google Play build asks (see [StoreReview]).
 */
object ReviewPrompt {

    /** Three days, so the first question comes after the app has proved itself for a while. */
    const val MINIMUM_USE_MILLIS = 3L * 24 * 60 * 60 * 1000

    /** A delivery this recent counts as the moment when the app comes to the foreground. */
    const val RECENT_DELIVERY_MILLIS = 24L * 60 * 60 * 1000

    data class State(
        /** When the last sync delivered or tried to, of either source; null before the first. */
        val lastSyncMillis: Long?,
        val lastSyncSucceeded: Boolean,
        /** Failed syncs in a row, Health Connect, Screen Time and Receive together. */
        val failureStreak: Int,
        /** Syncs in a row that some webhook missed while another took them. */
        val partialStreak: Int,
        /** Payloads waiting in the outbox. */
        val pending: Int
    )

    /** Whether the sync side is in a good moment: delivered lately, to every address, and nothing failing or queued. */
    fun isGoodMoment(state: State, now: Long): Boolean {
        val last = state.lastSyncMillis ?: return false
        if (now - last > RECENT_DELIVERY_MILLIS) return false
        return state.lastSyncSucceeded && state.failureStreak == 0 && state.partialStreak == 0 && state.pending == 0
    }

    fun shouldAsk(goodMoment: Boolean, firstUse: Long?, askedVersion: String?, version: String, now: Long): Boolean {
        if (!goodMoment || firstUse == null || now - firstUse < MINIMUM_USE_MILLIS) return false
        return askedVersion != version
    }
}
