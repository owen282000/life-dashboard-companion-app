package com.owen282000.lifedashboard.sync

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.owen282000.lifedashboard.ConfigBackup
import com.owen282000.lifedashboard.ConfigBackupManager
import com.owen282000.lifedashboard.ConfigCrypto
import com.owen282000.lifedashboard.LogType
import com.owen282000.lifedashboard.MqttSection
import com.owen282000.lifedashboard.SyncFailureNotifier
import com.owen282000.lifedashboard.SyncMode
import com.owen282000.lifedashboard.WriteBackLedger
import com.owen282000.lifedashboard.WriteBackReport
import com.owen282000.lifedashboard.appPreferences
import com.owen282000.lifedashboard.harness.AppStateRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The settings backup, both directions, against the real encrypted preferences. The fixture
 * sets every field to something other than its default; its encrypted twin is checked to hold
 * the same bytes, so the two files cannot drift apart.
 */
@RunWith(AndroidJUnit4::class)
class BackupImportTest {

    @get:Rule
    val appState = AppStateRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val prefs get() = context.appPreferences()

    private fun asset(name: String): String =
        InstrumentationRegistry.getInstrumentation().context.assets.open("fixtures/$name").bufferedReader().use { it.readText() }

    private val plain by lazy { asset("settings-ci.json") }
    private val fixture by lazy { ConfigBackup.decode(plain) }

    /** Everything but the two fields an export fills in itself. */
    private fun comparable(backup: ConfigBackup) = backup.copy(exportedAt = null, appVersion = null)

    /**
     * T01. The encrypted fixture decrypts to exactly the plain one, imports into every setting,
     * and exports back to the same backup: no field is lost in either direction.
     */
    @Test
    fun importsEncryptedFixture() {
        val decrypted = ConfigCrypto.decrypt(asset("settings-ci.enc"), "ci-fixture")
        assertEquals("the two fixture files hold the same backup", plain, decrypted)

        ConfigBackupManager(context).import(ConfigBackup.decode(decrypted))

        assertEquals(fixture.health.webhookUrls, prefs.getHealthWebhookUrls())
        assertEquals("ci-secret-health", prefs.getHealthWebhookSecret())
        assertEquals(mapOf("X-Api-Key" to "ci-key-screen"), prefs.getScreenTimeWebhookHeaders())
        assertEquals("ci-mqtt-password", prefs.getSharedMqttBroker().password)
        assertEquals("screentime-ci", prefs.getMqttSection(MqttSection.SCREEN_TIME).baseTopic)
        assertEquals(SyncMode.TIMES, prefs.getSyncSchedule(LogType.HEALTH_CONNECT).mode)
        assertEquals(4, SyncFailureNotifier.getThreshold(context))
        assertEquals("CI Phone", prefs.getPhoneName())
        assertEquals(fixture.health.webhookUrls.orEmpty().first(), prefs.getReceiveSettings().sourceUrl)
        assertEquals(comparable(fixture), comparable(ConfigBackupManager(context).export()))
    }

    /** T02. A wrong password is refused and changes nothing. */
    @Test
    fun wrongPasswordIsRejected() {
        val before = comparable(ConfigBackupManager(context).export())
        val refused = runCatching { ConfigCrypto.decrypt(asset("settings-ci.enc"), "not-the-password") }.exceptionOrNull()
        assertTrue(refused is ConfigCrypto.WrongPasswordException)
        assertEquals(before, comparable(ConfigBackupManager(context).export()))
    }

    /** T03. Importing a backup without secrets keeps the webhook secrets and headers on the device. */
    @Test
    fun secretFreeImportKeepsSecrets() {
        ConfigBackupManager(context).import(fixture)
        ConfigBackupManager(context).import(fixture.withoutSecrets())
        assertEquals("ci-secret-health", prefs.getHealthWebhookSecret())
        assertEquals("ci-secret-screen", prefs.getScreenTimeWebhookSecret())
        assertEquals(fixture.health.headers, prefs.getHealthWebhookHeaders())
        assertEquals(fixture.screenTime.headers, prefs.getScreenTimeWebhookHeaders())
    }

    /**
     * T03 for MQTT, a new finding (F9): the import promises that a backup without secrets keeps
     * the credentials already on the device, and a broker's username and password are secrets
     * to withoutSecrets() and containsSecrets(). Red on main: the broker is written whole, so
     * a secret-free import empties them.
     */
    @Test
    fun secretFreeImportKeepsBrokerCredentials() {
        ConfigBackupManager(context).import(fixture)
        ConfigBackupManager(context).import(fixture.withoutSecrets())
        assertEquals("ci-mqtt-user", prefs.getSharedMqttBroker().username)
        assertEquals("ci-mqtt-password", prefs.getSharedMqttBroker().password)
        assertEquals("ci-screen-password", prefs.getMqttSection(MqttSection.SCREEN_TIME).ownBroker.password)
    }

    /**
     * T04. A new health secret means a new counterpart for Receive: the ledger and the pending
     * acks go. A source URL the section does not have is not taken over.
     */
    @Test
    fun newSecretClearsReceiveBookkeeping() {
        ConfigBackupManager(context).import(fixture)
        prefs.setWriteBackLedger(WriteBackLedger.EMPTY.record("sensor.x@1", 1, "record-1", 1L))
        prefs.setWriteBackReport(WriteBackReport(ack = listOf("sensor.x@1")))

        ConfigBackupManager(context).import(fixture.copy(health = fixture.health.copy(signingSecret = "a-new-secret")))

        assertEquals(WriteBackLedger.EMPTY, prefs.getWriteBackLedger())
        assertTrue(prefs.getWriteBackReport().isEmpty)

        ConfigBackupManager(context).import(fixture.copy(options = fixture.options.copy(receiveSourceUrl = "https://not-in-the-list.invalid/api/webhook/x")))
        assertNull(prefs.getReceiveSettings().sourceUrl)
    }

    /**
     * A file from the iPhone app has no Screen Time, no Screen Time MQTT and no day boundary,
     * which stay as they are, and names the iPhone with its topic and phone name, which this
     * phone does not take over.
     */
    @Test
    fun iPhoneFileKeepsWhatItDoesNotHave() {
        ConfigBackupManager(context).import(fixture)
        val screenTimeUrls = prefs.getScreenTimeWebhookUrls()
        val screenTimeMqtt = prefs.getMqttSection(MqttSection.SCREEN_TIME)
        val healthTopic = prefs.getMqttSection(MqttSection.HEALTH).baseTopic
        val boundary = prefs.getScreenTimeDayBoundaryHour()
        val useBoundary = prefs.useScreenTimeDayBoundary()

        val iPhoneFile = """
            {
              "version" : 1,
              "platform" : "ios",
              "health" : { "webhook_urls" : [ "https://iphone.example.com/health" ], "sync_interval_minutes" : 30 },
              "mqtt" : { "health_base_topic" : "lifedashboard-ios", "health_enabled" : true, "health_use_shared" : true },
              "options" : { "enabled_data_types" : [ "STEPS" ], "phone_name" : "Zoë's iPhone" }
            }
        """.trimIndent()
        ConfigBackupManager(context).import(ConfigBackup.decode(iPhoneFile))

        assertEquals(listOf("https://iphone.example.com/health"), prefs.getHealthWebhookUrls())
        assertEquals(screenTimeUrls, prefs.getScreenTimeWebhookUrls())
        assertEquals(screenTimeMqtt, prefs.getMqttSection(MqttSection.SCREEN_TIME))
        assertEquals(boundary, prefs.getScreenTimeDayBoundaryHour())
        assertEquals(useBoundary, prefs.useScreenTimeDayBoundary())
        assertEquals(healthTopic, prefs.getMqttSection(MqttSection.HEALTH).baseTopic)
        assertEquals("CI Phone", prefs.getPhoneName())
    }
}
