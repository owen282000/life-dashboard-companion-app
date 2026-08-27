package com.owen282000.lifedashboard

sealed class HealthSyncResult {
    object NoData : HealthSyncResult()
    data class Success(val syncCounts: Map<HealthDataType, Int>) : HealthSyncResult()

    /** Webhook delivery failed; the payload is queued in the outbox and watermarks advanced. */
    data class Queued(val recordCount: Int) : HealthSyncResult()
}

sealed class ScreenTimeSyncResult {
    object NoData : ScreenTimeSyncResult()
    data class Success(val appCount: Int, val dayCount: Int) : ScreenTimeSyncResult()

    /** Webhook delivery failed; the payload is queued in the outbox and watermarks advanced. */
    data class Queued(val appCount: Int) : ScreenTimeSyncResult()
}
