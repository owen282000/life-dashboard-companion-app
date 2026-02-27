package com.owen282000.lifedashboard

import android.content.Context
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.time.Instant

class ScreenTimeSyncManager(private val context: Context) {

    private val preferencesManager = PreferencesManager(context)
    private val screenTimeManager = ScreenTimeManager(context, preferencesManager)

    suspend fun performSync(): Result<ScreenTimeSyncResult> = withContext(Dispatchers.IO) {
        try {
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
                customHeaders = preferencesManager.getScreenTimeWebhookHeaders()
            )

            // Build JSON payload
            val jsonPayload = buildJsonPayload(screenTimeDataList)

            // Post to webhook
            val postResult = webhookManager.postData(jsonPayload)
            if (postResult.isFailure) {
                return@withContext Result.failure(
                    postResult.exceptionOrNull() ?: Exception("Failed to post to webhooks")
                )
            }

            // Update last sync timestamp
            preferencesManager.setScreenTimeLastSyncTimestamp(System.currentTimeMillis())

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
