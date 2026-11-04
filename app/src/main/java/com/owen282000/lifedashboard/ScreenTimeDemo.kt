package com.owen282000.lifedashboard

import android.content.Context
import android.content.pm.ApplicationInfo
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Instant
import java.time.LocalDate

/**
 * Example usage for the store screenshots, as `-ld.demoSteps` on iOS. The usage statistics cannot
 * be seeded from outside: only the system writes them, and it moves what it stored along with the
 * clock, so a phone clock set back or forward gives no history. scripts/store-screenshots.sh puts
 * this file in the app's own files, which only a debuggable build lets it do, and the check here
 * makes a release build ignore the file all the same.
 */
internal object ScreenTimeDemo {
    const val FILE = "ld_demo_usage.json"

    @Serializable
    data class App(val packageName: String, val name: String, val minutes: Long)

    /** [daysAgo] counts back from the logical today, [apps] are that day's. */
    @Serializable
    data class Day(val daysAgo: Long, val apps: List<App>)

    fun read(context: Context): List<Day>? {
        if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return null
        val file = File(context.filesDir, FILE)
        if (!file.exists()) return null
        return runCatching { Json.decodeFromString<List<Day>>(file.readText()) }.getOrNull()
    }

    fun data(days: List<Day>, today: LocalDate, now: Long): List<ScreenTimeData> = days.map { day ->
        val apps = day.apps.sortedByDescending { it.minutes }.map {
            AppUsageData(it.packageName, it.name, it.minutes * 60_000, Instant.ofEpochMilli(now))
        }
        ScreenTimeData(today.minusDays(day.daysAgo), apps.sumOf { it.totalTimeMs }, apps)
    }.sortedByDescending { it.date }
}
