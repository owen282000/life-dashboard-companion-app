package com.owen282000.lifedashboard.smoke

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.owen282000.lifedashboard.HealthDataType
import com.owen282000.lifedashboard.LogType
import com.owen282000.lifedashboard.SyncMode
import com.owen282000.lifedashboard.SyncSchedule
import com.owen282000.lifedashboard.appPreferences
import com.owen282000.lifedashboard.harness.AppStateRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalTime

/**
 * Not a test: the preparation of the background smoke run in scripts/instrumented.sh, the only
 * check of the real background route (a JobScheduler job in a process that is not in the
 * foreground, so Health Connect applies READ_HEALTH_DATA_IN_BACKGROUND). It leaves the app
 * configured for a receiver on the host and a timed schedule two hours out; the script then
 * starts the app's process without an activity, so the real WorkManager plans that run, and
 * forces the job with `cmd jobscheduler run -f`. Excluded from the suite itself.
 *
 *   -e smokeUrl http://127.0.0.1:18765/health -e smokeSecret smoke-secret
 */
@RunWith(AndroidJUnit4::class)
class BackgroundSmokeSetup {

    @get:Rule
    val appState = AppStateRule()

    @Test
    fun prepareForTheBackgroundRun() {
        val args = InstrumentationRegistry.getArguments()
        val prefs = InstrumentationRegistry.getInstrumentation().targetContext.appPreferences()
        prefs.setHealthWebhookUrls(listOf(checkNotNull(args.getString("smokeUrl")) { "-e smokeUrl is required" }))
        prefs.setHealthWebhookSecret(checkNotNull(args.getString("smokeSecret")) { "-e smokeSecret is required" })
        prefs.setAllowHttpWebhooks(true)
        prefs.setHealthEnabledDataTypes(setOf(HealthDataType.STEPS, HealthDataType.WEIGHT))
        prefs.setSyncSchedule(LogType.HEALTH_CONNECT, SyncSchedule(mode = SyncMode.TIMES, times = listOf(LocalTime.now().plusHours(2).withSecond(0).withNano(0))))
        // No Screen Time job next to it, so the one job of the app is the health run.
        prefs.setSyncSchedule(LogType.SCREEN_TIME, SyncSchedule(days = emptySet()))
        // The setters apply() in the background and the process ends with the instrumentation;
        // an empty commit() writes each file's whole state to disk before that.
        listOf("life_dashboard_prefs", "life_dashboard_secrets").forEach {
            check(InstrumentationRegistry.getInstrumentation().targetContext.getSharedPreferences(it, Context.MODE_PRIVATE).edit().commit())
        }
    }
}
