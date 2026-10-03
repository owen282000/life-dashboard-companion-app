package com.owen282000.lifedashboard

import android.content.Context
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/**
 * Delivers queued outbox payloads using the CURRENT webhook configuration for each category,
 * so config changes made after a failure apply to the retried delivery too. Stops at the
 * first failure to preserve ordering; remaining items wait for the next drain (which runs at
 * the start of every sync).
 *
 * One exception to stopping (F6 of P2-4): a payload every webhook refused because of the
 * payload itself ([WebhookSupport.refusesPayload]) is skipped, so it cannot hold back what
 * was queued after it. It stays queued, because such a refusal can also come from a bug on
 * the receiving side that an update fixes (the Home Assistant integration answers 400 for
 * any error while reading a payload), and is dropped only after [REFUSED_MAX_AGE_MS] of
 * refusals. Payloads carry a `sequence`, so a receiver can order what then arrives late.
 *
 * A payload a sync is still posting is not in the queue (see [PendingSyncStore.writeAhead]),
 * so a drain beside that sync, the tile's other sync, never posts it a second time.
 */
object PendingDrainer {

    /** How long a refused payload is offered again before the outbox gives it up: a week. */
    const val REFUSED_MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000

    /**
     * How long one drain may post before it leaves the rest to the next sync. A full outbox
     * holds 700 payloads, which at a second a post to a remote Home Assistant would outlast
     * WorkManager's 10 minutes and hold the Screen Time sync on [lock] meanwhile. The same
     * 2 minutes as the read step's budget; a backlog of a week clears in a few syncs.
     */
    const val DRAIN_BUDGET_MS = 120_000L

    /**
     * One drain at a time. The health and Screen Time syncs both drain first and can run
     * together (the tile starts both), and two drains would post the same item twice and
     * count its records twice.
     */
    private val lock = Mutex()

    suspend fun drain(context: Context) = lock.withLock { drainLocked(context) }

    private suspend fun drainLocked(context: Context) {
        // A payload a sync wrote ahead of its post and never saw the end of, because its
        // process died, joins the queue here. One a running sync is posting stays out of it.
        // Nothing in there may stop the drain, nor the sync that runs it: a full disk would
        // otherwise fail every sync before it reads anything.
        try {
            PendingSyncStore.recoverInFlight(context)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w("PendingDrainer", "Could not recover in-flight payloads; the next drain tries again", e)
        }
        val store = PendingSyncStore.forContext(context)
        val items = store.peekAll()
        if (items.isEmpty()) return

        val preferencesManager = PreferencesManager(context)
        val startedAt = System.currentTimeMillis()
        for (item in items) {
            if (System.currentTimeMillis() - startedAt >= DRAIN_BUDGET_MS) break
            val isScreenTime = item.logType == LogType.SCREEN_TIME.name
            val logType = if (isScreenTime) LogType.SCREEN_TIME else LogType.HEALTH_CONNECT
            val urls = if (isScreenTime) preferencesManager.getScreenTimeWebhookUrls()
                       else preferencesManager.getHealthWebhookUrls()
            if (urls.isEmpty()) continue

            val webhookManager = WebhookManager(
                webhookUrls = urls,
                context = context,
                dataType = item.dataType,
                recordCount = item.recordCount,
                logType = logType,
                customHeaders = if (isScreenTime) preferencesManager.getScreenTimeWebhookHeaders()
                                else preferencesManager.getHealthWebhookHeaders(),
                urlsWithoutHeaders = if (isScreenTime) preferencesManager.getScreenTimeUrlsWithoutHeaders()
                                     else preferencesManager.getHealthUrlsWithoutHeaders(),
                signingSecret = if (isScreenTime) preferencesManager.getScreenTimeWebhookSecret()
                                else preferencesManager.getHealthWebhookSecret()
            )

            val result = webhookManager.postData(item.payload)
            val refusal = result.exceptionOrNull() as? PayloadRefusedException
            when {
                result.isSuccess -> {
                    store.remove(item.id)
                    // A drained payload is a delivery like any other: it ends the failure streak
                    // and moves "Last sync". Without this, an outage followed by a sync with no
                    // new data left the failure notification and a red status in place while the
                    // queued data had in fact arrived (F5 of P2-4). Its records count for today
                    // now, since the failed attempt that queued it counted none.
                    SyncFailureNotifier.recordDelivery(context, logType, result)
                    SyncStatusStore.record(context, true, item.recordCount, logType)
                }
                refusal != null && System.currentTimeMillis() - item.createdAt >= REFUSED_MAX_AGE_MS -> {
                    store.remove(item.id)
                    preferencesManager.addWebhookLog(
                        WebhookLog(
                            id = UUID.randomUUID().toString(),
                            timestamp = System.currentTimeMillis(),
                            url = urls.joinToString(", "),
                            statusCode = refusal.statusCode,
                            success = false,
                            errorMessage = "Refused for a week (HTTP ${refusal.statusCode}), dropped from the outbox",
                            dataType = item.dataType,
                            recordCount = item.recordCount,
                            rawPayload = item.payload,
                            logType = logType.name
                        )
                    )
                }
                // Skipped, not stopped at: see the class comment.
                refusal != null -> store.recordAttempt(item)
                else -> {
                    store.recordAttempt(item)
                    break
                }
            }
        }
    }
}
