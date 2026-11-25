package com.owen282000.lifedashboard

/**
 * Every sync but Sync Now runs in a worker, and Health Connect lets a worker read only with
 * READ_HEALTH_DATA_IN_BACKGROUND. Without it a scheduled sync reads nothing and sends only the
 * Receive heartbeat, while Sync Now works, which looks like a sync that runs but stays empty
 * (discussion #265). Android's own battery and background settings do not grant it.
 */
object BackgroundAccess {
    /**
     * True when the app reads types but may not read them in the background, on a Health Connect
     * that knows the permission. Without any read permission the screen already asks for access.
     */
    fun missing(granted: Set<String>, readsTypes: Boolean, featureAvailable: Boolean): Boolean =
        featureAvailable && readsTypes && granted.isNotEmpty() && HealthConnectManager.BACKGROUND_PERMISSION !in granted
}
