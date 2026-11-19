package com.owen282000.lifedashboard

import android.os.SystemClock
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.play.core.ktx.launchReview
import com.google.android.play.core.ktx.requestReview
import com.google.android.play.core.review.ReviewManagerFactory
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

/**
 * Play's In-App Review (P1-28): Google Play shows its own rating dialog over the app, or nothing
 * when its quota says no, and the app never learns which or what was rated. [ReviewMoment]
 * decides when to ask; nothing here asks a question first or offers anything in return.
 */
object PlayStoreReview : StoreReview {

    private const val TAG = "PlayStoreReview"

    override fun request(activity: ComponentActivity, onDone: (elapsedMillis: Long) -> Unit) {
        activity.lifecycleScope.launch {
            val manager = ReviewManagerFactory.create(activity)
            try {
                val info = manager.requestReview()
                val started = SystemClock.elapsedRealtime()
                manager.launchReview(activity, info)
                // The flow returns when the dialog closes, or at once when Play showed none.
                val elapsed = SystemClock.elapsedRealtime() - started
                Log.i(TAG, "Review flow took $elapsed ms")
                onDone(elapsed)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Play is missing, out of date or not signed in: no dialog, and nothing to tell.
                Log.i(TAG, "No review dialog: ${e.message}")
            }
        }
    }
}
