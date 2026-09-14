package com.owen282000.lifedashboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Settings export and import. The round trip has to be exact: a user moving to a new phone
 * imports this file and expects every webhook, header, secret and toggle to come back.
 */
class ConfigBackupTest {

    private fun fullBackup() = ConfigBackup(
        exportedAt = "2026-09-13T12:00:00Z",
        appVersion = "1.11.0",
        health = SectionConfig(
            webhookUrls = listOf("https://example.com/health", "https://backup.example.com/h"),
            headers = mapOf("Authorization" to "Bearer token123", "X-Api-Key" to "abc"),
            signingSecret = "hmac-secret",
            syncIntervalMinutes = 30,
            syncMode = "TIMES",
            syncTimes = "08:00,21:00",
            syncDays = "MONDAY,TUESDAY,WEDNESDAY,THURSDAY,FRIDAY",
            quietFrom = "23:00",
            quietTo = "07:00"
        ),
        screenTime = SectionConfig(
            webhookUrls = listOf("https://example.com/screen"),
            headers = mapOf("Authorization" to "Bearer other"),
            signingSecret = "other-secret",
            syncIntervalMinutes = 60
        ),
        mqtt = MqttConfig(
            shared = BrokerConfig("mqtt.local", 8883, true, "user", "pass"),
            healthEnabled = true,
            healthUseShared = true,
            healthBaseTopic = "lifedash/health",
            screenTimeEnabled = true,
            screenTimeUseShared = false,
            screenTimeBaseTopic = "lifedash/screen",
            screenTimeOwnBroker = BrokerConfig("other.local", 1883, false, "u2", "p2")
        ),
        options = OptionsConfig(
            enabledDataTypes = listOf("STEPS", "HEART_RATE", "SLEEP"),
            includeDailyTotals = false,
            allowHttpWebhooks = true,
            keepFullPayloads = true,
            screenTimeDayBoundaryHour = 3,
            screenTimeUseDayBoundary = false,
            failureNotificationThreshold = 5
        )
    )

    @Test
    fun roundTripsThroughJsonUnchanged() {
        val original = fullBackup()
        assertEquals(original, ConfigBackup.decode(original.encode()))
    }

    @Test
    fun roundTripsAnEmptyConfig() {
        val empty = ConfigBackup()
        assertEquals(empty, ConfigBackup.decode(empty.encode()))
    }

    @Test
    fun exportedJsonUsesStableSnakeCaseKeys() {
        val json = fullBackup().encode()
        listOf(
            "webhook_urls", "signing_secret", "sync_interval_minutes",
            "screen_time", "use_tls", "enabled_data_types", "include_daily_totals",
            "allow_http_webhooks", "health_base_topic"
        ).forEach {
            assertTrue("expected key \"$it\" in export", json.contains("\"$it\""))
        }
    }

    @Test
    fun unknownKeysFromANewerVersionAreIgnored() {
        val withExtra = """
            {
              "version": 1,
              "some_future_field": {"nested": true},
              "health": {"webhook_urls": ["https://example.com/h"], "unknown": 1}
            }
        """.trimIndent()

        val decoded = ConfigBackup.decode(withExtra)
        assertEquals(listOf("https://example.com/h"), decoded.health.webhookUrls)
    }

    @Test
    fun withoutSecretsStripsEveryCredentialButKeepsEverythingElse() {
        val stripped = fullBackup().withoutSecrets()

        assertNull(stripped.health.signingSecret)
        assertNull(stripped.screenTime.signingSecret)
        assertTrue(stripped.health.headers.isEmpty())
        assertTrue(stripped.screenTime.headers.isEmpty())
        assertNull(stripped.mqtt.shared.username)
        assertNull(stripped.mqtt.shared.password)
        assertNull(stripped.mqtt.screenTimeOwnBroker.password)

        // Non-secret configuration must survive, that is the point of sharing a setup.
        assertEquals(
            listOf("https://example.com/health", "https://backup.example.com/h"),
            stripped.health.webhookUrls
        )
        assertEquals("mqtt.local", stripped.mqtt.shared.host)
        assertEquals(8883, stripped.mqtt.shared.port)
        assertTrue(stripped.mqtt.shared.useTls)
        assertEquals(fullBackup().options, stripped.options)
    }

    @Test
    fun containsSecretsDetectsEachKindOfCredential() {
        assertFalse(ConfigBackup().containsSecrets())
        assertFalse(fullBackup().withoutSecrets().containsSecrets())
        assertTrue(fullBackup().containsSecrets())

        assertTrue(
            ConfigBackup(health = SectionConfig(signingSecret = "s")).containsSecrets()
        )
        assertTrue(
            ConfigBackup(health = SectionConfig(headers = mapOf("A" to "b"))).containsSecrets()
        )
        assertTrue(
            ConfigBackup(mqtt = MqttConfig(shared = BrokerConfig(password = "p"))).containsSecrets()
        )
    }

    @Test
    fun strippedExportStillRoundTrips() {
        val stripped = fullBackup().withoutSecrets()
        assertEquals(stripped, ConfigBackup.decode(stripped.encode()))
    }

    @Test
    fun unknownDataTypeNamesAreDropped() {
        val types = ConfigBackupManager.dataTypesFrom(
            listOf("STEPS", "SOMETHING_FROM_A_NEWER_BUILD", "HEART_RATE")
        )
        assertEquals(setOf(HealthDataType.STEPS, HealthDataType.HEART_RATE), types)
    }

    @Test
    fun everyDataTypeNameSurvivesTheRoundTrip() {
        val all = HealthDataType.entries.toSet()
        val names = all.map { it.name }
        assertEquals("every type must map back", all, ConfigBackupManager.dataTypesFrom(names))
    }

    @Test
    fun summaryReportsCountsAndSecretPresence() {
        val summary = fullBackup().summarise()
        assertTrue(summary.any { it == "Health webhooks: 2" })
        assertTrue(summary.any { it == "Screen time webhooks: 1" })
        assertTrue(summary.any { it == "Enabled data types: 3" })
        assertTrue(summary.any { it == "Includes secrets" })

        assertTrue(fullBackup().withoutSecrets().summarise().any { it == "No secrets included" })
    }

    @Test
    fun brokerConvertsToAndFromTheDomainType() {
        val config = BrokerConfig("h", 1883, true, "u", "p")
        assertEquals(config, BrokerConfig.from(config.toBroker()))
    }

    @Test
    fun blankBrokerCredentialsBecomeNullNotEmptyStrings() {
        // writeBroker stores "" for absent credentials, which must not come back as a username.
        val broker = BrokerConfig(host = "h", username = "", password = "").toBroker()
        assertNull(broker.username)
        assertNull(broker.password)
    }

    @Test
    fun keepsTheScheduleThroughTheRoundTrip() {
        val restored = ConfigBackup.decode(fullBackup().encode())
        with(restored.health) {
            assertEquals("TIMES", syncMode)
            assertEquals("08:00,21:00", syncTimes)
            assertEquals("MONDAY,TUESDAY,WEDNESDAY,THURSDAY,FRIDAY", syncDays)
            assertEquals("23:00", quietFrom)
            assertEquals("07:00", quietTo)
        }
    }

    @Test
    fun readsABackupWrittenBeforeSchedulesExisted() {
        // Every schedule field is absent, as in a file exported by 1.13.x. It has to load,
        // with the interval intact and the schedule fields left for the app to default.
        val old = """
            {
              "exported_at": "2026-09-01T10:00:00Z",
              "app_version": "1.13.3",
              "health": { "webhook_urls": ["https://example.com/h"], "sync_interval_minutes": 45 },
              "screen_time": { "webhook_urls": [], "sync_interval_minutes": 60 }
            }
        """.trimIndent()

        val restored = ConfigBackup.decode(old)
        assertEquals(listOf("https://example.com/h"), restored.health.webhookUrls)
        assertEquals(45, restored.health.syncIntervalMinutes)
        assertNull(restored.health.syncMode)
        assertNull(restored.health.syncTimes)
        assertNull(restored.health.quietFrom)
    }

}
