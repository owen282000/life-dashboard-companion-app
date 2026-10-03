package com.owen282000.lifedashboard.harness

import androidx.test.platform.app.InstrumentationRegistry
import com.owen282000.lifedashboard.BackfillJobStore
import com.owen282000.lifedashboard.BackfillRun
import com.owen282000.lifedashboard.HealthDataType
import com.owen282000.lifedashboard.HealthSyncManager
import com.owen282000.lifedashboard.MqttBroker
import com.owen282000.lifedashboard.MqttSection
import com.owen282000.lifedashboard.MqttSectionSettings
import com.owen282000.lifedashboard.WriteBackType
import com.owen282000.lifedashboard.appPreferences

/**
 * The app's settings for a test, written through the app's own PreferencesManager: one health
 * webhook on the [Receiver], a signing secret and headers of its own, plain HTTP allowed (the
 * receiver is http://127.0.0.1), MQTT off, Receive off unless asked for.
 */
object TestSetup {
    const val HEALTH_PATH = "/api/webhook/ci-health"
    const val HEALTH_SECRET = "ci-secret-health"
    val HEALTH_HEADERS = mapOf("X-Api-Key" to "ci-key-health", "Cookie" to "session=ci")

    val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    fun health(receiver: Receiver, types: Set<HealthDataType>, receive: Set<WriteBackType> = emptySet()) {
        val prefs = context.appPreferences()
        val url = receiver.url(HEALTH_PATH)
        prefs.setHealthWebhookUrls(listOf(url))
        prefs.setHealthWebhookSecret(HEALTH_SECRET)
        prefs.setHealthWebhookHeaders(HEALTH_HEADERS)
        prefs.setAllowHttpWebhooks(true)
        prefs.setHealthEnabledDataTypes(types)
        if (receive.isNotEmpty()) {
            prefs.setReceiveSourceUrl(url)
            prefs.setReceiveTypes(receive)
            prefs.setReceiveEnabled(true)
        }
    }

    const val SCREEN_PATH = "/api/webhook/ci-screen"
    const val SCREEN_SECRET = "ci-secret-screen"
    val SCREEN_HEADERS = mapOf("X-Api-Key" to "ci-key-screen", "Cookie" to "session=ci")

    /** One Screen Time webhook on the [Receiver], with a secret and headers of its own. */
    fun screenTime(receiver: Receiver) {
        val prefs = context.appPreferences()
        prefs.setScreenTimeWebhookUrls(listOf(receiver.url(SCREEN_PATH)))
        prefs.setScreenTimeWebhookSecret(SCREEN_SECRET)
        prefs.setScreenTimeWebhookHeaders(SCREEN_HEADERS)
        prefs.setAllowHttpWebhooks(true)
    }

    /**
     * MQTT on for [section] through the shared broker (the suite's own, see MqttProbe), under
     * a base topic of its own per test so retained values of earlier tests never mix in.
     */
    fun mqtt(section: MqttSection, baseTopic: String = "ldt_" + java.util.UUID.randomUUID().toString().take(6), port: Int = MqttProbe.PORT): String {
        val prefs = context.appPreferences()
        prefs.setSharedMqttBroker(MqttBroker(host = MqttProbe.HOST, port = port, useTls = false, username = null, password = null))
        prefs.setMqttSection(section, MqttSectionSettings(enabled = true, useSharedBroker = true, ownBroker = prefs.getMqttSection(section).ownBroker, baseTopic = baseTopic))
        return baseTopic
    }

    /** The sync as the app runs it, on a Health Connect client that counts its calls. */
    fun syncManager(): HealthSyncManager = HealthSyncManager(context, Managers.counting(context))

    /**
     * A backfill of [days] the way its worker runs one (P2-14): the stored job, started or
     * continued as the Backfill dialog does, run until it ends or stops.
     */
    suspend fun backfill(
        days: Int,
        manager: HealthSyncManager = syncManager(),
        onWaiting: (Boolean) -> Unit = {},
        onProgress: (Int, Int) -> Unit = { _, _ -> }
    ): BackfillRun = manager.runBackfill(BackfillJobStore.startOrContinue(context, days), onWaiting, onProgress)

    /** The failure streak SyncFailureNotifier keeps for HEALTH_CONNECT, SCREEN_TIME or RECEIVE. */
    fun streak(name: String): Int =
        context.getSharedPreferences("life_dashboard_prefs", android.content.Context.MODE_PRIVATE).getInt("sync_failure_streak_$name", 0)

    fun versionName(): String = context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
}
