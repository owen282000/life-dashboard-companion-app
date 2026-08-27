package com.owen282000.lifedashboard

import android.content.Context

/**
 * Delivers queued outbox payloads using the CURRENT webhook configuration for each category,
 * so config changes made after a failure apply to the retried delivery too. Stops at the
 * first failure to preserve ordering; remaining items wait for the next drain (which runs at
 * the start of every sync).
 */
object PendingDrainer {

    suspend fun drain(context: Context) {
        val store = PendingSyncStore.forContext(context)
        val items = store.peekAll()
        if (items.isEmpty()) return

        val preferencesManager = PreferencesManager(context)
        for (item in items) {
            val isScreenTime = item.logType == LogType.SCREEN_TIME.name
            val urls = if (isScreenTime) preferencesManager.getScreenTimeWebhookUrls()
                       else preferencesManager.getHealthWebhookUrls()
            if (urls.isEmpty()) continue

            val webhookManager = WebhookManager(
                webhookUrls = urls,
                context = context,
                dataType = item.dataType,
                recordCount = item.recordCount,
                logType = if (isScreenTime) LogType.SCREEN_TIME else LogType.HEALTH_CONNECT,
                customHeaders = if (isScreenTime) preferencesManager.getScreenTimeWebhookHeaders()
                                else preferencesManager.getHealthWebhookHeaders(),
                signingSecret = if (isScreenTime) preferencesManager.getScreenTimeWebhookSecret()
                                else preferencesManager.getHealthWebhookSecret()
            )

            if (webhookManager.postData(item.payload).isSuccess) {
                store.remove(item.id)
            } else {
                store.recordAttempt(item)
                break
            }
        }
    }
}
