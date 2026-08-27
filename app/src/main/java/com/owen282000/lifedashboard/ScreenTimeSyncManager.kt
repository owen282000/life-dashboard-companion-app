package com.owen282000.lifedashboard

import android.content.Context
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.time.Instant

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
            val payload = buildJsonPayload(screenTimeDataList)
            val prettyPayload = json.encodeToString(
                kotlinx.serialization.json.JsonElement.serializer(),
                Json.parseToJsonElement(payload)
            )
            Result.success(prettyPayload)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun performSync(): Result<ScreenTimeSyncResult> = withContext(Dispatchers.IO) {
        try {
            // Deliver any queued payloads from earlier failed syncs first, preserving order.
            PendingDrainer.drain(context)

            val webhookUrls = preferencesManager.getScreenTimeWebhookUrls()

            if (webhookUrls.isEmpty()) {
                return@withContext Result.failure(Exception("No webhook URLs configured"))
            }

            if (!screenTimeManager.hasPermission()) {
                return@withContext Result.failure(Exception("Usage stats permission not granted"))
            }

            // Read screen time data for the past 7 days
            val screenTimeResult = screenTimeManager.readScreenTimeData(lookbackDays = 7)
            if (screenTimeResult.isFailure) {
                return@withContext Result.failure(
                    screenTimeResult.exceptionOrNull() ?: Exception("Failed to read screen time data")
                )
            }

            val screenTimeDataList = screenTimeResult.getOrThrow()

            // Always sync all 7 days - the backend does upsert so duplicates are fine
            // This ensures we always have complete data even if the app wasn't synced for a while
            if (screenTimeDataList.isEmpty()) {
                return@withContext Result.success(ScreenTimeSyncResult.NoData)
            }

            // Calculate total apps synced
            val totalApps = screenTimeDataList.sumOf { it.apps.size }

            val webhookManager = WebhookManager(
                webhookUrls = webhookUrls,
                context = context,
                dataType = "screen_time",
                recordCount = totalApps,
                logType = LogType.SCREEN_TIME,
                customHeaders = preferencesManager.getScreenTimeWebhookHeaders(),
                signingSecret = preferencesManager.getScreenTimeWebhookSecret()
            )

            // Build JSON payload
            val jsonPayload = buildJsonPayload(screenTimeDataList)

            // Post to webhook
            val postResult = webhookManager.postData(jsonPayload)
            SyncFailureNotifier.recordResult(context, LogType.SCREEN_TIME, postResult.isSuccess)
            SyncStatusStore.record(context, postResult.isSuccess, if (postResult.isSuccess) totalApps else 0)
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
                return@withContext Result.success(ScreenTimeSyncResult.Queued(totalApps))
            }

            Result.success(ScreenTimeSyncResult.Success(totalApps, screenTimeDataList.size))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun buildJsonPayload(screenTimeDataList: List<ScreenTimeData>): String {
        val json = buildJsonObject {
            put("timestamp", Instant.now().toString())
            put("app_version", getAppVersion())
            put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
            put("source", "screen_time")

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
}
