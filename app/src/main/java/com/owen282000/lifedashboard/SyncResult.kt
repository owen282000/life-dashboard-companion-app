package com.owen282000.lifedashboard

sealed class HealthSyncResult {
    object NoData : HealthSyncResult()

    /**
     * [written] is what Receive put into Health Connect during this sync; zero when Receive is off.
     * [missedUrls] are webhooks that did not take a payload another one took, out of [webhookCount].
     */
    data class Success(
        val syncCounts: Map<HealthDataType, Int>,
        val written: Int = 0,
        val missedUrls: Set<String> = emptySet(),
        val webhookCount: Int = 0
    ) : HealthSyncResult()

    /** Webhook delivery failed; the payload is queued in the outbox and watermarks advanced. */
    data class Queued(val recordCount: Int) : HealthSyncResult()
}

sealed class ScreenTimeSyncResult {
    object NoData : ScreenTimeSyncResult()
    data class Success(
        val appCount: Int,
        val dayCount: Int,
        val missedUrls: Set<String> = emptySet(),
        val webhookCount: Int = 0
    ) : ScreenTimeSyncResult()

    /** Webhook delivery failed; the payload is queued in the outbox and watermarks advanced. */
    data class Queued(val appCount: Int) : ScreenTimeSyncResult()
}
