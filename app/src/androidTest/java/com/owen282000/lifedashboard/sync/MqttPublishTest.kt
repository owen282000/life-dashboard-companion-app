package com.owen282000.lifedashboard.sync

import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.owen282000.lifedashboard.BackfillFailure
import com.owen282000.lifedashboard.BackfillRun
import com.owen282000.lifedashboard.HealthDataType.HEART_RATE
import com.owen282000.lifedashboard.HealthDataType.STEPS
import com.owen282000.lifedashboard.HealthDataType.WEIGHT
import com.owen282000.lifedashboard.HealthSyncResult
import com.owen282000.lifedashboard.LogDestination
import com.owen282000.lifedashboard.LogType
import com.owen282000.lifedashboard.MqttSection
import com.owen282000.lifedashboard.MqttTimeouts
import com.owen282000.lifedashboard.R
import com.owen282000.lifedashboard.ScreenTimeSyncManager
import com.owen282000.lifedashboard.SyncStatusStore
import com.owen282000.lifedashboard.appPreferences
import com.owen282000.lifedashboard.harness.AppStateRule
import com.owen282000.lifedashboard.harness.Conservation
import com.owen282000.lifedashboard.harness.HcFixture
import com.owen282000.lifedashboard.harness.HcFixture.Companion.ago
import com.owen282000.lifedashboard.harness.MqttProbe
import com.owen282000.lifedashboard.harness.Receiver
import com.owen282000.lifedashboard.harness.ScreenTimeUse
import com.owen282000.lifedashboard.harness.TestSetup
import com.owen282000.lifedashboard.harness.Witness
import com.owen282000.lifedashboard.harness.arr
import com.owen282000.lifedashboard.harness.num
import com.owen282000.lifedashboard.harness.obj
import com.owen282000.lifedashboard.harness.str
import com.owen282000.lifedashboard.harness.strings
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import java.time.LocalDate

/**
 * MQTT with Home Assistant Discovery, against a real mosquitto. The fakes of the unit tests
 * never knew the combinations that broke here: MQTT only (1.13.1), totals and retired keys
 * (1.13.3), the rounding (1.13.4), the phone name (1.20.0).
 */
@RunWith(AndroidJUnit4::class)
class MqttPublishTest {

    private val receiver = Receiver()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(receiver).around(AppStateRule()).around(Witness.Rule(receiver))

    private val context = TestSetup.context
    private val fixture = HcFixture(context)
    private val prefs get() = context.appPreferences()

    private fun config(key: String, slug: String? = null) =
        "homeassistant/sensor/life_dashboard_companion_${slug?.let { "${it}_" }.orEmpty()}$key/config"

    /** Heart rate 72, a weight with many decimals, 1234 steps today, over webhook and MQTT. */
    private fun seed(): Pair<String, String> {
        HcFixture.awayFromMidnight()
        fixture.assertNoForeignRecords(StepsRecord::class, HeartRateRecord::class, WeightRecord::class)
        val (_, heartRate, weight) = fixture.insert(
            fixture.steps(1234, ago(2), ago(1)),
            fixture.heartRate(listOf(ago(3) to 70L, ago(2) to 72L)),
            fixture.weight(78.2006048685296, ago(2))
        )
        return heartRate to weight
    }

    /**
     * T16. Discovery config, state and attributes per sensor, live from this sync: the ids and
     * topics, the device, one decimal for the weight, the steps equal to the webhook's daily
     * total, attributes that name the same uuid as the webhook, the retired keys emptied, one
     * MQTT log row; and a fresh subscriber finds the same values retained.
     */
    @Test
    fun discoveryStateAndAttributes() = runBlocking {
        TestSetup.health(receiver, setOf(STEPS, HEART_RATE, WEIGHT))
        val base = TestSetup.mqtt(MqttSection.HEALTH)
        val (heartRateId, weightId) = seed()
        val probe = MqttProbe("homeassistant/sensor/#", "$base/#").drainRetained()

        TestSetup.syncManager().performSync().getOrThrow()

        val webhook = Conservation.parse(receiver.exchanges.single().text)
        val weightConfig = Conservation.parse(probe.awaitLive(config("weight")).payload)
        assertEquals("life_dashboard_companion_weight", weightConfig.str("unique_id"))
        assertEquals("$base/weight/state", weightConfig.str("state_topic"))
        assertEquals("$base/weight/attributes", weightConfig.str("json_attributes_topic"))
        assertEquals("1", weightConfig.num("suggested_display_precision"))
        val device = weightConfig.obj("device")!!
        assertEquals(listOf("life_dashboard_companion"), device["identifiers"].strings())
        assertEquals("Life Dashboard Companion", device.str("name"))
        assertEquals(TestSetup.versionName(), device.str("sw_version"))

        assertEquals("72", probe.awaitLive("$base/heart_rate/state").payload)
        assertEquals("78.2", probe.awaitLive("$base/weight/state").payload)
        val todaySteps = webhook.arr("daily_totals").orEmpty().map { it as JsonObject }.single { it.str("date") == LocalDate.now().toString() }.num("steps")
        assertEquals("1234", probe.awaitLive("$base/steps_today/state").payload)
        assertEquals(todaySteps, probe.awaitLive("$base/steps_today/state").payload)
        probe.awaitLive(config("steps_today"))
        probe.awaitLive(config("heart_rate"))

        val weightAttributes = Conservation.parse(probe.awaitLive("$base/weight/attributes").payload)
        assertEquals(weightId, weightAttributes.str("uuid"))
        assertEquals(context.packageName, weightAttributes.str("source"))
        assertNotNull(weightAttributes.str("measured_at"))
        val heartRateAttributes = Conservation.parse(probe.awaitLive("$base/heart_rate/attributes").payload)
        assertTrue("the uuid of the newest sample", heartRateAttributes.str("uuid").orEmpty().startsWith("$heartRateId#"))

        for (topic in listOf("$base/steps/state", "$base/steps/attributes", config("steps"))) {
            assertEquals("retired key emptied: $topic", "", probe.awaitLive(topic).payload)
        }
        val mqttRows = prefs.getWebhookLogs(LogType.HEALTH_CONNECT).filter { it.destination == LogDestination.MQTT.name }
        assertEquals(1, mqttRows.size)
        assertTrue(mqttRows.single().success)
        assertEquals("mqtt://${MqttProbe.HOST}:${MqttProbe.PORT}/$base", mqttRows.single().url)
        assertEquals(3, mqttRows.single().recordCount)
        probe.close()

        MqttProbe("$base/#").drainRetained().use { fresh ->
            assertEquals("72", fresh.retained("$base/heart_rate/state")?.payload)
            assertEquals("78.2", fresh.retained("$base/weight/state")?.payload)
            assertEquals("1234", fresh.retained("$base/steps_today/state")?.payload)
        }
    }

    /**
     * T17 (1.20.0). Naming the phone takes the nameless device off the broker (state,
     * attributes and config of every known sensor emptied, in that order) and publishes under
     * the slug; the slug is remembered. A later sync under the same name clears nothing more.
     */
    @Test
    fun phoneNameMovesDeviceAndClearsOldTopics() = runBlocking {
        TestSetup.health(receiver, setOf(STEPS, HEART_RATE, WEIGHT))
        val base = TestSetup.mqtt(MqttSection.HEALTH)
        seed()
        TestSetup.syncManager().performSync().getOrThrow()
        assertNull(prefs.getMqttPublishedSlug(MqttSection.HEALTH))

        prefs.setPhoneName("S21 Ultra")
        fixture.insert(fixture.heartRate(listOf(ago(1) to 75L, ago(0) to 76L)))
        val probe = MqttProbe("homeassistant/sensor/#", "$base/#").drainRetained()
        TestSetup.syncManager().performSync().getOrThrow()

        for (key in listOf("heart_rate", "weight", "steps_today")) {
            val cleared = listOf("$base/$key/state", "$base/$key/attributes", config(key))
            cleared.forEach { assertEquals("cleared: $it", "", probe.awaitLive(it).payload) }
            // The broker keeps the order within one subscription, not across the two this probe
            // has, so only state and attributes (both under the base topic) are compared here; it
            // failed twice on [state, attributes] arriving after the config. The order the app
            // sends all three in, config last, is MqttSupportTest's.
            val order = cleared.take(2).map { topic -> probe.live().indexOfFirst { it.topic == topic } }
            assertEquals("state before attributes", order.sorted(), order)
        }
        assertEquals("76", probe.awaitLive("$base/s21_ultra/heart_rate/state").payload)
        val named = Conservation.parse(probe.awaitLive(config("heart_rate", "s21_ultra")).payload)
        assertEquals("Life Dashboard Companion (S21 Ultra)", named.obj("device")?.str("name"))
        assertEquals("s21_ultra", prefs.getMqttPublishedSlug(MqttSection.HEALTH))
        probe.close()

        fixture.insert(fixture.heartRate(listOf(ago(0).minusSeconds(20) to 77L, ago(0).minusSeconds(10) to 78L)))
        MqttProbe("$base/#").drainRetained().use { third ->
            TestSetup.syncManager().performSync().getOrThrow()
            third.awaitLive("$base/s21_ultra/heart_rate/state")
            val emptied = third.live().filter { it.payload.isEmpty() }.map { it.topic }
            assertTrue("only retired keys are emptied now: $emptied", emptied.all { it.contains("/steps/") || it.contains("/distance/") || it.contains("/active_calories/") || it.contains("/total_calories/") })
        }
    }

    /** T18. A broker that is not there never blocks the webhook; the MQTT row and status say what went wrong. */
    @Test
    fun brokerDownDoesNotBlockWebhook() = runBlocking {
        TestSetup.health(receiver, setOf(STEPS))
        TestSetup.mqtt(MqttSection.HEALTH, port = 1)
        fixture.insert(fixture.steps(12, ago(30), ago(20)))

        val result = TestSetup.syncManager().performSync().getOrThrow()

        assertTrue(result is HealthSyncResult.Success)
        assertEquals(1, receiver.exchanges.size)
        val row = prefs.getWebhookLogs(LogType.HEALTH_CONNECT).single { it.destination == LogDestination.MQTT.name }
        assertTrue(!row.success && !row.errorMessage.isNullOrBlank())
        assertTrue(prefs.getLastMqttStatus(MqttSection.HEALTH).orEmpty().startsWith("Error:"))
    }

    /** T19 (1.13.1). MQTT without any webhook: the sync succeeds and publishes, no HTTP; a backfill says why it cannot run. */
    @Test
    fun mqttOnlyHealthSyncAndBackfillMessage() = runBlocking {
        prefs.setHealthEnabledDataTypes(setOf(HEART_RATE))
        val base = TestSetup.mqtt(MqttSection.HEALTH)
        fixture.insert(fixture.heartRate(listOf(ago(3) to 70L, ago(2) to 71L)))
        val probe = MqttProbe("$base/#").drainRetained()

        val result = TestSetup.syncManager().performSync().getOrThrow()

        assertEquals(HealthSyncResult.Success(mapOf(HEART_RATE to 2)), result)
        assertEquals("71", probe.awaitLive("$base/heart_rate/state").payload)
        assertEquals(0, receiver.exchanges.size)
        assertTrue(prefs.getLastMqttStatus(MqttSection.HEALTH).orEmpty().startsWith("OK:"))
        assertEquals(BackfillFailure.NoWebhook, (TestSetup.backfill(7) as BackfillRun.Failed).failure)
        probe.close()
    }

    /**
     * MQTT without any webhook and the broker down: the publish was the delivery, so the sync
     * fails, the dashboard shows it and the failure streak counts it, instead of a green sync
     * that went nowhere.
     */
    @Test
    fun mqttOnlyBrokerDownFailsTheSync() = runBlocking {
        prefs.setHealthEnabledDataTypes(setOf(HEART_RATE))
        TestSetup.mqtt(MqttSection.HEALTH, port = 1)
        fixture.insert(fixture.heartRate(listOf(ago(3) to 70L)))

        val failure = TestSetup.syncManager().performSync().exceptionOrNull()

        assertTrue("the sync fails: $failure", failure?.message.orEmpty().startsWith(context.getString(R.string.mqtt_sync_failed, "")))
        assertEquals(0, receiver.exchanges.size)
        assertTrue(prefs.getLastMqttStatus(MqttSection.HEALTH).orEmpty().startsWith("Error:"))
        assertFalse(SyncStatusStore.read(context, LogType.HEALTH_CONNECT).lastSuccess)
        assertEquals(1, TestSetup.streak("HEALTH_CONNECT"))
    }

    /**
     * A broker that takes the connection and never answers: the publish gives up within the
     * connect limits instead of holding the sync for good, and the webhook still decides.
     */
    @Test
    fun silentBrokerTimesOut() = runBlocking {
        java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1")).use { silent ->
            // Takes the connection and never says a word.
            val accepted = java.util.concurrent.CopyOnWriteArrayList<java.net.Socket>()
            val acceptor = Thread { runCatching { while (true) accepted += silent.accept() } }.apply { isDaemon = true; start() }
            TestSetup.health(receiver, setOf(STEPS))
            TestSetup.mqtt(MqttSection.HEALTH, port = silent.localPort)
            fixture.insert(fixture.steps(12, ago(30), ago(20)))

            val started = System.nanoTime()
            val result = TestSetup.syncManager().performSync().getOrThrow()
            val seconds = (System.nanoTime() - started) / 1_000_000_000

            assertTrue(result is HealthSyncResult.Success)
            assertTrue("gave up after ${seconds}s", seconds < MqttTimeouts.CONNECT_DEADLINE_MILLIS / 1000 + 10)
            assertTrue(prefs.getLastMqttStatus(MqttSection.HEALTH).orEmpty().startsWith("Error:"))
            assertTrue("the broker was reached", accepted.isNotEmpty())
            silent.close()
            acceptor.join(1000)
            accepted.forEach { it.close() }
        }
    }

    /** T20. Screen Time sensors: today, the top app without a state class, yesterday when there is one. */
    @Test
    fun screenTimeSensors() = runBlocking {
        ScreenTimeUse.ensureToday()
        val base = TestSetup.mqtt(MqttSection.SCREEN_TIME)
        val probe = MqttProbe("homeassistant/sensor/#", "$base/#").drainRetained()

        ScreenTimeSyncManager(context).performSync().getOrThrow()

        assertTrue(probe.awaitLive("$base/screen_time_today/state").payload.toInt() >= 1)
        val topApp = Conservation.parse(probe.awaitLive(config("screen_time_top_app")).payload)
        assertNull("a text sensor has no state class", topApp["state_class"])
        val today = Conservation.parse(probe.awaitLive(config("screen_time_today")).payload)
        assertEquals("measurement", today.str("state_class"))
        probe.close()
    }
}
