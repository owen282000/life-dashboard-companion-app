package com.owen282000.lifedashboard

/**
 * When to ask for a rating on Google Play (P1-28, P1-43), the same rule as the iPhone app.
 *
 * The app is set up once and then syncs in the background, so the few times it is on screen
 * have to count. A moment is good only after something went right, never next to an error:
 *
 * - [Moment.BACKFILL_DONE]: a backfill the user started has just delivered their history, with
 *   nothing failing or queued. It counts from the first day, because for many people the setup
 *   is the last time they look at the app.
 * - [Moment.OPENED] and [Moment.SYNC_DELIVERED]: the app is in front, or a sync from the screen
 *   delivered, after at least a day of use, with a delivery to every address within the last
 *   day and no failed delivery in that day. Someone who came in to fix a problem is asked on a
 *   later visit, not in the same one.
 *
 * Play shows its dialog or not and never says which (see [wasShown]), so a request that
 * probably went unseen is tried again after two weeks, one that was seen only after four
 * months, and never more than three times in all.
 *
 * Pure, so it is tested on the JVM; [ReviewMoment] reads the state from the stores. Only the
 * Google Play build asks Play (see [StoreReview]); the GitHub and F-Droid builds have a card
 * that asks for a star on GitHub, by the same good moment (see [showsStarCard]).
 */
object ReviewPrompt {

    private const val DAY_MILLIS = 24L * 60 * 60 * 1000

    /** A day, so the app has synced on its own at least once before the first question. */
    const val MINIMUM_USE_MILLIS = DAY_MILLIS

    /** A delivery this recent counts as the moment when the app comes to the foreground. */
    const val RECENT_DELIVERY_MILLIS = DAY_MILLIS

    /** A failed delivery this recent means the visit may be about that failure. */
    const val QUIET_MILLIS = DAY_MILLIS

    /** How long the screen stays as it is before the question comes, so it never meets someone at the door. */
    const val REST_MILLIS = 2_500L

    /** After a dialog that was seen, about the three times a year the App Store allows. */
    const val PAUSE_AFTER_SHOWN_MILLIS = 120 * DAY_MILLIS

    /** After a request that Play probably kept to itself. */
    const val RETRY_AFTER_UNSEEN_MILLIS = 14 * DAY_MILLIS

    /** Whoever closed the dialog three times has answered. */
    const val MAXIMUM_SHOWN = 3

    /** A review flow that kept the app waiting this long had a dialog in it. */
    const val SHOWN_AFTER_MILLIS = 1_500L

    /** The card comes after two weeks of use at the earliest, */
    const val CARD_MINIMUM_USE_MILLIS = 14 * DAY_MILLIS

    /** and never in the week after the dialog was asked for. */
    const val CARD_AFTER_REQUEST_MILLIS = 7 * DAY_MILLIS

    enum class Moment { OPENED, SYNC_DELIVERED, BACKFILL_DONE }

    data class State(
        /** When the tabs first showed after the setup; null before that. */
        val firstUseMillis: Long?,
        /** When the last sync delivered or tried to, of either source; null before the first. */
        val lastSyncMillis: Long?,
        val lastSyncSucceeded: Boolean,
        /** Failed syncs in a row, Health Connect, Screen Time and Receive together. */
        val failureStreak: Int,
        /** Syncs in a row that some webhook missed while another took them. */
        val partialStreak: Int,
        /** Payloads waiting in the outbox. */
        val pending: Int,
        /** The newest failed delivery still in the log; null without one. */
        val lastFailureMillis: Long?
    )

    /** What was asked before. */
    data class History(
        /** When Play was last asked for its dialog; null before the first time. */
        val lastRequestMillis: Long? = null,
        /** Whether that request probably showed the dialog. */
        val lastRequestShown: Boolean = false,
        /** How many requests probably showed it. */
        val shownCount: Int = 0
    )

    /** Nothing is failing, queued or missing an address. */
    private fun isHealthy(state: State): Boolean =
        state.lastSyncSucceeded && state.failureStreak == 0 && state.partialStreak == 0 && state.pending == 0

    /** Whether [moment] is one to ask at, going by the sync side alone. */
    fun isGoodMoment(moment: Moment, state: State, now: Long): Boolean {
        if (!isHealthy(state)) return false
        if (moment == Moment.BACKFILL_DONE) return true
        val firstUse = state.firstUseMillis ?: return false
        if (now - firstUse < MINIMUM_USE_MILLIS) return false
        val last = state.lastSyncMillis ?: return false
        if (now - last > RECENT_DELIVERY_MILLIS) return false
        return state.lastFailureMillis == null || now - state.lastFailureMillis >= QUIET_MILLIS
    }

    /** Whether enough time has passed since the last request, and the dialog was not seen too often. */
    fun mayRequest(history: History, now: Long): Boolean {
        if (history.shownCount >= MAXIMUM_SHOWN) return false
        val last = history.lastRequestMillis ?: return true
        val wait = if (history.lastRequestShown) PAUSE_AFTER_SHOWN_MILLIS else RETRY_AFTER_UNSEEN_MILLIS
        return now - last >= wait
    }

    fun shouldAsk(moment: Moment, state: State, history: History, now: Long): Boolean =
        isGoodMoment(moment, state, now) && mayRequest(history, now)

    /**
     * Whether the dialog was probably on screen. Play does not say; a flow that returns at once
     * showed nothing, and one that took [elapsedMillis] of a second and a half or more waited for
     * someone. Wrong either way costs little: a seen dialog taken for unseen is asked for again
     * after two weeks, and then Play's own quota decides.
     */
    fun wasShown(elapsedMillis: Long): Boolean = elapsedMillis >= SHOWN_AFTER_MILLIS

    /**
     * Whether the quiet card on the Health tab shows: a link to the listing for whoever the
     * dialog did not reach. After two weeks of use, at a good moment, at least a week after the
     * dialog was asked for, and until it is used or closed.
     */
    fun showsCard(state: State, history: History, cardClosed: Boolean, now: Long): Boolean {
        if (cardClosed) return false
        val firstUse = state.firstUseMillis ?: return false
        if (now - firstUse < CARD_MINIMUM_USE_MILLIS) return false
        val asked = history.lastRequestMillis ?: return false
        if (now - asked < CARD_AFTER_REQUEST_MILLIS) return false
        return isGoodMoment(Moment.OPENED, state, now)
    }

    /**
     * The same card in the GitHub and F-Droid builds, which have no store to rate in: it asks for
     * a star on GitHub instead. There is no dialog to wait for, so it shows at the first good
     * moment with the app in front, and likewise until it is used or closed.
     */
    fun showsStarCard(state: State, cardClosed: Boolean, now: Long): Boolean =
        !cardClosed && isGoodMoment(Moment.OPENED, state, now)
}
