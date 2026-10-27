package com.owen282000.lifedashboard

/**
 * The application of the Google Play build (src/play/AndroidManifest.xml): the same app, which
 * asks for a rating through Play's In-App Review (P1-28). The GitHub and F-Droid builds run
 * [LifeDashboardApplication] itself and never ask.
 */
class PlayApplication : LifeDashboardApplication() {
    override val storeReview: StoreReview get() = PlayStoreReview
}
