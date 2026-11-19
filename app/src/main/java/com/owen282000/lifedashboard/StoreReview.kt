package com.owen282000.lifedashboard

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.core.content.edit

/**
 * Asks the store to show its rating dialog (P1-28). Here it does nothing: the GitHub and F-Droid
 * builds carry no Google library and never ask. The Google Play build replaces it with Play's
 * In-App Review, in src/play (PlayApplication, PlayStoreReview).
 */
fun interface StoreReview {
    /**
     * Asks for the dialog. [onDone] gets how long the store kept the app waiting, which is all
     * there is to tell a dialog from none (see [ReviewPrompt.wasShown]); it is not called when
     * the store could not be asked at all.
     */
    fun request(activity: ComponentActivity, onDone: (elapsedMillis: Long) -> Unit)

    companion object {
        val NONE = StoreReview { _, _ -> }
    }
}

/** The store's rating dialog for this build: [StoreReview.NONE] unless the app is the Google Play build. */
fun Context.storeReview(): StoreReview =
    (applicationContext as? LifeDashboardApplication)?.storeReview ?: StoreReview.NONE

/**
 * Asks for a rating when [ReviewPrompt] says the moment is good: when a tab has been in front
 * for a moment, after a sync from the screen delivered, and when a backfill finished. The first
 * use is noted here too, the first time the tabs show after the setup, and [card] says which
 * card the Health tab shows. Everything it keeps stays on the phone.
 */
object ReviewMoment {

    private const val PREFS_NAME = "life_dashboard_prefs"
    private const val KEY_FIRST_USE = "review_first_use"
    private const val KEY_LAST_REQUEST = "review_last_request"
    private const val KEY_LAST_SHOWN = "review_last_shown"
    private const val KEY_SHOWN_COUNT = "review_shown_count"
    private const val KEY_CARD_CLOSED = "review_card_closed"

    /** Until 1.30.1 the question came once per version; the pauses in [ReviewPrompt] took its place. */
    private const val KEY_ASKED_VERSION = "review_asked_version"

    /** Which card the Health tab shows, if any. */
    enum class Card { NONE, RATE, STAR }

    fun consider(activity: ComponentActivity, moment: ReviewPrompt.Moment) {
        val prefs = prefs(activity)
        val now = System.currentTimeMillis()
        // In every build: the star card of the GitHub and F-Droid builds counts from here too.
        if (!prefs.contains(KEY_FIRST_USE)) prefs.edit { putLong(KEY_FIRST_USE, now) }
        val review = activity.storeReview()
        if (review === StoreReview.NONE) return
        if (prefs.contains(KEY_ASKED_VERSION)) prefs.edit { remove(KEY_ASKED_VERSION) }

        if (!ReviewPrompt.shouldAsk(moment, state(activity), history(activity), now)) return

        // Noted before Play answers, so a second moment in the meantime does not ask again.
        prefs.edit {
            putLong(KEY_LAST_REQUEST, now)
            putBoolean(KEY_LAST_SHOWN, false)
        }
        val app = activity.applicationContext
        review.request(activity) { elapsed ->
            if (ReviewPrompt.wasShown(elapsed)) {
                val shown = prefs(app)
                shown.edit {
                    putBoolean(KEY_LAST_SHOWN, true)
                    putInt(KEY_SHOWN_COUNT, shown.getInt(KEY_SHOWN_COUNT, 0) + 1)
                }
            }
        }
    }

    /**
     * The card on the Health tab now: the Play listing in the Google Play build, a star on GitHub
     * in the GitHub and F-Droid builds, never both in one build. Nothing here looks up whether
     * someone rated or starred already; the app asks no server anything.
     */
    fun card(context: Context): Card {
        val closed = prefs(context).getBoolean(KEY_CARD_CLOSED, false)
        if (closed) return Card.NONE
        val now = System.currentTimeMillis()
        return when {
            context.storeReview() !== StoreReview.NONE ->
                if (ReviewPrompt.showsCard(state(context), history(context), closed, now)) Card.RATE else Card.NONE
            context.resources.getBoolean(R.bool.show_star_card) ->
                if (ReviewPrompt.showsStarCard(state(context), closed, now)) Card.STAR else Card.NONE
            else -> Card.NONE
        }
    }

    /** The card was used or closed, and stays away. */
    fun closeCard(context: Context) {
        prefs(context).edit { putBoolean(KEY_CARD_CLOSED, true) }
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun history(context: Context): ReviewPrompt.History {
        val prefs = prefs(context)
        return ReviewPrompt.History(
            lastRequestMillis = prefs.getLong(KEY_LAST_REQUEST, -1).takeIf { it > 0 },
            lastRequestShown = prefs.getBoolean(KEY_LAST_SHOWN, false),
            shownCount = prefs.getInt(KEY_SHOWN_COUNT, 0)
        )
    }

    private fun state(context: Context): ReviewPrompt.State {
        val status = SyncStatusStore.read(context)
        return ReviewPrompt.State(
            firstUseMillis = prefs(context).getLong(KEY_FIRST_USE, -1).takeIf { it > 0 },
            lastSyncMillis = status.lastSyncMillis,
            lastSyncSucceeded = status.lastSuccess,
            failureStreak = SyncFailureNotifier.failureStreak(context),
            partialStreak = SyncFailureNotifier.partialStreak(context),
            pending = PendingSyncStore.forContext(context).size(),
            lastFailureMillis = context.appPreferences().getWebhookLogsWithoutPayloads()
                .filter { !it.success }
                .maxOfOrNull { it.timestamp }
        )
    }
}
