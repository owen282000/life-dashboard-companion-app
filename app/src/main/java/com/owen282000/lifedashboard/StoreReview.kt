package com.owen282000.lifedashboard

import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import androidx.core.content.edit

/**
 * Asks the store to show its rating dialog (P1-28). Here it does nothing: the GitHub and F-Droid
 * builds carry no Google library and never ask. The Google Play build replaces it with Play's
 * In-App Review, in src/play (PlayApplication, PlayStoreReview).
 */
fun interface StoreReview {
    fun request(activity: ComponentActivity)

    companion object {
        val NONE = StoreReview { }
    }
}

/** The store's rating dialog for this build: [StoreReview.NONE] unless the app is the Google Play build. */
fun Context.storeReview(): StoreReview =
    (applicationContext as? LifeDashboardApplication)?.storeReview ?: StoreReview.NONE

/**
 * Asks for a rating when [ReviewPrompt] says the moment is good: when the app comes to the
 * front, after a sync from the screen delivered, and when a backfill finished. The first use is
 * noted here too, the first time the tabs show after the setup.
 */
object ReviewMoment {

    private const val PREFS_NAME = "life_dashboard_prefs"
    private const val KEY_FIRST_USE = "review_first_use"
    private const val KEY_ASKED_VERSION = "review_asked_version"

    fun consider(activity: ComponentActivity, backfillDone: Boolean = false) {
        val review = activity.storeReview()
        if (review === StoreReview.NONE) return
        val prefs = activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (!prefs.contains(KEY_FIRST_USE)) prefs.edit { putLong(KEY_FIRST_USE, now) }

        val version = try {
            activity.packageManager.getPackageInfo(activity.packageName, 0).versionName
        } catch (_: PackageManager.NameNotFoundException) {
            null
        } ?: return
        val goodMoment = backfillDone || ReviewPrompt.isGoodMoment(state(activity), now)
        if (!ReviewPrompt.shouldAsk(goodMoment, prefs.getLong(KEY_FIRST_USE, now), prefs.getString(KEY_ASKED_VERSION, null), version, now)) return

        prefs.edit { putString(KEY_ASKED_VERSION, version) }
        review.request(activity)
    }

    private fun state(context: Context): ReviewPrompt.State {
        val status = SyncStatusStore.read(context)
        return ReviewPrompt.State(
            lastSyncMillis = status.lastSyncMillis,
            lastSyncSucceeded = status.lastSuccess,
            failureStreak = SyncFailureNotifier.failureStreak(context),
            partialStreak = SyncFailureNotifier.partialStreak(context),
            pending = PendingSyncStore.forContext(context).size()
        )
    }
}
