package com.owen282000.lifedashboard

import android.content.Context
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.time.Instant

/** Serialises [ScreenTimeSyncManager.performSync] across every caller in the process. */
private val SCREEN_TIME_SYNC_LOCK = Mutex()

class ScreenTimeSyncManager(private val context: Context) {

    private val preferencesManager = PreferencesManager(context)
    private val screenTimeManager = ScreenTimeManager(context, preferencesManager)

    suspend fun previewData(): Result<String> = withContext(Dispatchers.IO) {
        try {
            if (!screenTimeManager.hasPermission()) {
                return@withContext Result.failure(Exception("Usage stats permission not granted"))
            }

            val screenTimeResult = screenTimeManager.readScreenTimeData(lookbackDays = 7)
            if (screenTimeResult.isFailure) {
                return@withContext Result.failure(screenTimeResult.exceptionOrNull() ?: Exception("Failed to read screen time data"))
            }

            val screenTimeDataList = screenTimeResult.getOrThrow()
            if (screenTimeDataList.isEmpty()) {
                return@withContext Result.failure(Exception("No data to preview"))
            }

            val json = Json { prettyPrint = true }
            val payload = buildJsonPayload(screenTimeDataList, getAppVersion(), deviceName())
            val prettyPayload = json.encodeToString(
                kotlinx.serialization.json.JsonElement.serializer(),
                Json.parseToJsonElement(payload)
            )
            Result.success(prettyPayload)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * One sync at a time per process. The tile, the worker and a manual sync can each start
     * one, and two running together would post the same week twice and take sequence numbers
     * in an order that no longer matches the order the payloads were read; the second simply
     * waits its turn.
     */
    suspend fun performSync(): Result<ScreenTimeSyncResult> = withContext(Dispatchers.IO) {
        SCREEN_TIME_SYNC_LOCK.withLock { performSyncLocked() }
    }

    private suspend fun performSyncLocked(): Result<ScreenTimeSyncResult> {
        try {
            // Deliver any queued payloads from earlier failed syncs first, preserving order.
            PendingDrainer.drain(context)

            val webhookUrls = preferencesManager.getScreenTimeWebhookUrls()
            val mqttSettings = preferencesManager.resolvedMqttSettings(MqttSection.SCREEN_TIME)
            val publishToMqtt = mqttSettings.enabled && mqttSettings.host.isNotBlank()

            if (webhookUrls.isEmpty() && !publishToMqtt) {
                return Result.failure(Exception("No webhook URLs configured"))
            }

            if (!screenTimeManager.hasPermission()) {
                return Result.failure(Exception("Usage stats permission not granted"))
            }

            // Read screen time data for the past 7 days
            val screenTimeResult = screenTimeManager.readScreenTimeData(lookbackDays = 7)
            if (screenTimeResult.isFailure) {
                return Result.failure(
                    screenTimeResult.exceptionOrNull() ?: Exception("Failed to read screen time data")
                )
            }

            val screenTimeDataList = screenTimeResult.getOrThrow()

            // Always sync all 7 days - the backend does upsert so duplicates are fine
            // This ensures we always have complete data even if the app wasn't synced for a while
            if (screenTimeDataList.isEmpty()) {
                return Result.success(ScreenTimeSyncResult.NoData)
            }

            // Calculate total apps synced
            val totalApps = screenTimeDataList.sumOf { it.apps.size }

            // Same broker and Home Assistant device as Health Connect (issue #52). Failures
            // never block the webhook delivery; the outcome shows in the MQTT settings section.
            if (publishToMqtt) {
                MqttPublisher(context).publishScreenTime(screenTimeDataList)
            }

            // MQTT-only setup: nothing to post, nothing to queue.
            if (webhookUrls.isEmpty()) {
                SyncFailureNotifier.recordResult(context, LogType.SCREEN_TIME, true)
                SyncStatusStore.record(context, true, totalApps, LogType.SCREEN_TIME)
                LifetimeStats.recordDelivery(context, totalApps, 0, LogType.SCREEN_TIME)
                preferencesManager.setScreenTimeLastSyncTimestamp(System.currentTimeMillis())
                return Result.success(ScreenTimeSyncResult.Success(totalApps, screenTimeDataList.size))
            }

            val webhookManager = WebhookManager(
                webhookUrls = webhookUrls,
                context = context,
                dataType = "screen_time",
                recordCount = totalApps,
                logType = LogType.SCREEN_TIME,
                customHeaders = preferencesManager.getScreenTimeWebhookHeaders(),
                urlsWithoutHeaders = preferencesManager.getScreenTimeUrlsWithoutHeaders(),
                signingSecret = preferencesManager.getScreenTimeWebhookSecret()
            )

            // Taken only when there is a payload to post, from the counter Health Connect
            // payloads use, so the number goes up across everything this install sends.
            val jsonPayload = buildJsonPayload(
                screenTimeDataList,
                getAppVersion(),
                deviceName(),
                sequence = preferencesManager.nextHealthSyncSequence()
            )

            // Post to webhook
            val postResult = webhookManager.postData(jsonPayload)
            SyncFailureNotifier.recordResult(context, LogType.SCREEN_TIME, postResult.isSuccess)
            SyncStatusStore.record(context, postResult.isSuccess, if (postResult.isSuccess) totalApps else 0, LogType.SCREEN_TIME)
            // Watermark advances regardless of delivery outcome: a failed payload goes to the
            // outbox and is guaranteed to be delivered by a later drain.
            preferencesManager.setScreenTimeLastSyncTimestamp(System.currentTimeMillis())

            if (postResult.isFailure) {
                PendingSyncStore.forContext(context).enqueue(
                    payload = jsonPayload,
                    dataType = "screen_time",
                    logType = LogType.SCREEN_TIME.name,
                    recordCount = totalApps,
                    nowMillis = System.currentTimeMillis()
                )
                return Result.success(ScreenTimeSyncResult.Queued(totalApps))
            }

            return Result.success(ScreenTimeSyncResult.Success(totalApps, screenTimeDataList.size))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            return Result.failure(e)
        }
    }

    private fun deviceName() = "${Build.MANUFACTURER} ${Build.MODEL}"

    private fun getAppVersion(): String {
        return try {
            val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(
                    context.packageName,
                    android.content.pm.PackageManager.PackageInfoFlags.of(0)
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0)
            }
            packageInfo.versionName ?: "1.0"
        } catch (e: Exception) {
            "1.0"
        }
    }

    companion object {
        /**
         * The Screen Time payload. [sequence] is taken from the counter by the sync that sends;
         * the preview passes null, because taking a number there would leave a gap in the
         * sequence a receiver sees, and looking is not sending.
         */
        internal fun buildJsonPayload(
            screenTimeDataList: List<ScreenTimeData>,
            appVersion: String,
            device: String,
            sequence: Long? = null
        ): String {
            val json = buildJsonObject {
                put("timestamp", Instant.now().toString())
                put("app_version", appVersion)
                put("device", device)
                put("source", "screen_time")
                // Strictly increasing per payload, so an older week that arrives after a newer
                // one, from the outbox or a retry, is recognisable as stale instead of
                // overwriting it.
                sequence?.let { put("sequence", it) }

                putJsonArray("screen_time") {
                    screenTimeDataList.forEach { dayData ->
                        add(buildJsonObject {
                            put("date", dayData.date.toString())
                            put("total_screen_time_minutes", dayData.totalScreenTimeMs / 60000)

                            putJsonArray("apps") {
                                dayData.apps.forEach { app ->
                                    add(buildJsonObject {
                                        put("package", app.packageName)
                                        put("name", app.appName)
                                        put("minutes", app.totalTimeMs / 60000)
                                        put("last_used", app.lastUsed.toString())
                                    })
                                }
                            }
                        })
                    }
                }
            }

            return json.toString()
        }
    }
}
