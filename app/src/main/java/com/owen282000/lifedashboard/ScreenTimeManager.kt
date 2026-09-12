package com.owen282000.lifedashboard

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.concurrent.TimeUnit

data class ScreenTimeData(
    val date: LocalDate,
    val totalScreenTimeMs: Long,
    val apps: List<AppUsageData>
)

data class AppUsageData(
    val packageName: String,
    val appName: String,
    val totalTimeMs: Long,
    val lastUsed: Instant
)

class ScreenTimeManager(
    private val context: Context,
    private val preferencesManager: PreferencesManager
) {

    private val usageStatsManager: UsageStatsManager by lazy {
        context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
    }

    private val packageManager: PackageManager by lazy {
        context.packageManager
    }

    /** System UI and the launcher are hidden, as Digital Wellbeing does. */
    private val excludedPackages: Set<String> by lazy {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val launchers = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.queryIntentActivities(
                    home,
                    PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong())
                )
            } else {
                @Suppress("DEPRECATION")
                packageManager.queryIntentActivities(home, PackageManager.MATCH_DEFAULT_ONLY)
            }.map { it.activityInfo.packageName }
        } catch (e: Exception) {
            emptyList()
        }
        (launchers + ScreenTimeSessions.SYSTEM_UI_PACKAGE).toSet()
    }

    /**
     * Get the logical date for a given timestamp, respecting day boundary.
     * If time is before day boundary hour (e.g., 4 AM), it counts as previous day.
     */
    private fun getLogicalDate(timestampMs: Long, zone: ZoneId): LocalDate {
        val instant = Instant.ofEpochMilli(timestampMs)
        val localDateTime = instant.atZone(zone).toLocalDateTime()

        return if (preferencesManager.useScreenTimeDayBoundary() &&
                   localDateTime.hour < preferencesManager.getScreenTimeDayBoundaryHour()) {
            localDateTime.toLocalDate().minusDays(1)
        } else {
            localDateTime.toLocalDate()
        }
    }

    /**
     * Get the start timestamp for a logical day (at day boundary hour).
     */
    private fun getDayStartMs(date: LocalDate, zone: ZoneId): Long {
        val boundaryHour = if (preferencesManager.useScreenTimeDayBoundary()) {
            preferencesManager.getScreenTimeDayBoundaryHour()
        } else {
            0
        }
        return date.atTime(LocalTime.of(boundaryHour, 0))
            .atZone(zone)
            .toInstant()
            .toEpochMilli()
    }

    /**
     * Get the end timestamp for a logical day (at day boundary hour of next day).
     */
    private fun getDayEndMs(date: LocalDate, zone: ZoneId): Long {
        val boundaryHour = if (preferencesManager.useScreenTimeDayBoundary()) {
            preferencesManager.getScreenTimeDayBoundaryHour()
        } else {
            0
        }
        return date.plusDays(1).atTime(LocalTime.of(boundaryHour, 0))
            .atZone(zone)
            .toInstant()
            .toEpochMilli()
    }

    fun hasPermission(): Boolean {
        val appOpsManager = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOpsManager.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName
            )
        } else {
            @Suppress("DEPRECATION")
            appOpsManager.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName
            )
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun readScreenTimeData(lookbackDays: Int = 7): Result<List<ScreenTimeData>> {
        return try {
            if (!hasPermission()) {
                return Result.failure(Exception("Usage stats permission not granted"))
            }

            val result = mutableListOf<ScreenTimeData>()
            val zone = ZoneId.systemDefault()

            // Get logical "today" based on day boundary
            val now = System.currentTimeMillis()
            val today = getLogicalDate(now, zone)

            // Query each day separately using UsageEvents for accurate timing
            for (dayOffset in 0 until lookbackDays) {
                val targetDate = today.minusDays(dayOffset.toLong())

                // Day boundaries in milliseconds (respecting day boundary hour)
                val dayStart = getDayStartMs(targetDate, zone)
                val dayEnd = getDayEndMs(targetDate, zone)

                // Pair resume and pause events per activity; see ScreenTimeSessions for the rules.
                val events = mutableListOf<UsageEventSnapshot>()
                val usageEvents = usageStatsManager.queryEvents(dayStart, dayEnd)
                val event = UsageEvents.Event()
                while (usageEvents.hasNextEvent()) {
                    usageEvents.getNextEvent(event)
                    val packageName = event.packageName ?: continue
                    events.add(UsageEventSnapshot(packageName, event.className, event.eventType, event.timeStamp))
                }
                val perPackage = ScreenTimeSessions.aggregate(events, dayStart, dayEnd, now, excludedPackages)

                val appUsageList = perPackage
                    .filter { it.value.foregroundMs > 60000 } // > 1 minute
                    .map { (packageName, usage) ->
                        AppUsageData(
                            packageName = packageName,
                            appName = getAppName(packageName),
                            totalTimeMs = usage.foregroundMs,
                            lastUsed = Instant.ofEpochMilli(usage.lastUsedMs)
                        )
                    }
                    .sortedByDescending { it.totalTimeMs }

                if (appUsageList.isNotEmpty()) {
                    val totalScreenTime = appUsageList.sumOf { it.totalTimeMs }
                    result.add(
                        ScreenTimeData(
                            date = targetDate,
                            totalScreenTimeMs = totalScreenTime,
                            apps = appUsageList
                        )
                    )
                }
            }

            Result.success(result.sortedByDescending { it.date })
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun getAppName(packageName: String): String {
        return try {
            val appInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getApplicationInfo(
                    packageName,
                    PackageManager.ApplicationInfoFlags.of(0)
                )
            } else {
                @Suppress("DEPRECATION")
                packageManager.getApplicationInfo(packageName, 0)
            }
            packageManager.getApplicationLabel(appInfo).toString()
        } catch (e: PackageManager.NameNotFoundException) {
            packageName.substringAfterLast('.')
        }
    }

    companion object {
        const val LOOKBACK_DAYS = 7
    }
}
