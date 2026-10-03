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
            quietTo = "07:00",
            urlsWithoutHeaders = listOf("https://backup.example.com/h")
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
            failureNotificationThreshold = 5,
            seriesResolutions = mapOf("HEART_RATE" to "ONE_MINUTE", "STEPS" to "HOURLY"),
            phoneName = "Pixel 8",
            receiveEnabled = true,
            receiveTypes = listOf("weight", "blood_pressure"),
            receiveOlderMeasurements = true,
            receiveSourceUrl = "https://example.com/health"
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
            "allow_http_webhooks", "health_base_topic", "phone_name", "receive_types", "receive_source_url"
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
        assertNull(stripped.mqtt.shared?.username)
        assertNull(stripped.mqtt.shared?.password)
        assertNull(stripped.mqtt.screenTimeOwnBroker?.password)

        // Non-secret configuration must survive, that is the point of sharing a setup.
        assertEquals(
            listOf("https://example.com/health", "https://backup.example.com/h"),
            stripped.health.webhookUrls
        )
        assertEquals(listOf("https://backup.example.com/h"), stripped.health.urlsWithoutHeaders)
        assertEquals("mqtt.local", stripped.mqtt.shared?.host)
        assertEquals(8883, stripped.mqtt.shared?.port)
        assertEquals(true, stripped.mqtt.shared?.useTls)
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
        assertEquals(
            BackupSummary(healthWebhooks = 2, screenTimeWebhooks = 1, enabledDataTypes = 3, brokers = 2, includesSecrets = true),
            fullBackup().summarise(currentDataTypes = setOf(HealthDataType.BONE_MASS))
        )
        assertFalse(fullBackup().withoutSecrets().summarise(emptySet()).includesSecrets)
        // A part the file does not have is not counted as zero: the device keeps its own.
        assertEquals(
            BackupSummary(healthWebhooks = null, screenTimeWebhooks = null, enabledDataTypes = null, brokers = 0, includesSecrets = false),
            ConfigBackup.decode("""{"version": 1}""").summarise(emptySet())
        )
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
    fun keepsTheResolutionsThroughTheRoundTrip() {
        val restored = ConfigBackup.decode(fullBackup().encode())
        assertEquals(mapOf("HEART_RATE" to "ONE_MINUTE", "STEPS" to "HOURLY"), restored.options.seriesResolutions)
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
        // That version sent the headers to every URL.
        assertTrue(restored.health.urlsWithoutHeaders.isEmpty())
        assertNull(restored.options.seriesResolutions)
        // Absent, not defaulted: an import must leave the phone name, Receive and its ledger alone.
        assertNull(restored.options.phoneName)
        assertNull(restored.options.receiveEnabled)
        assertNull(restored.options.receiveTypes)
        assertNull(restored.options.receiveOlderMeasurements)
        assertNull(restored.options.receiveSourceUrl)
    }

    @Test
    fun receiveTypesAreProtocolKeysAndEnumNamesFromAPreReleaseBuildStillRead() {
        assertEquals(WriteBackType.BLOOD_PRESSURE, ConfigBackupManager.writeBackTypeFrom("blood_pressure"))
        assertEquals(WriteBackType.BLOOD_PRESSURE, ConfigBackupManager.writeBackTypeFrom("BLOOD_PRESSURE"))
        assertNull(ConfigBackupManager.writeBackTypeFrom("heart_rate"))
    }

    @Test
    fun secretFreeBrokerKeepsTheCredentialsOfTheSameBrokerOnly() {
        val onDevice = MqttBroker("broker.lan", 8883, true, "user", "pass")
        val fromBackup = BrokerConfig(host = "Broker.lan", port = 8883, useTls = true)

        val kept = fromBackup.toBroker(onDevice, backupHasSecrets = false)
        assertEquals("user", kept.username)
        assertEquals("pass", kept.password)

        // Another host, another port or no TLS is another broker: it never gets them, and
        // they never go out in plain text. A backup that carries secrets says what it means.
        assertNull(BrokerConfig(host = "other.lan", port = 8883, useTls = true).toBroker(onDevice, backupHasSecrets = false).username)
        assertNull(fromBackup.copy(port = 1883).toBroker(onDevice, backupHasSecrets = false).username)
        assertNull(fromBackup.copy(useTls = false).toBroker(onDevice, backupHasSecrets = false).password)
        assertNull(fromBackup.toBroker(onDevice, backupHasSecrets = true).username)
        assertEquals("new", fromBackup.copy(username = "new").toBroker(onDevice, backupHasSecrets = true).username)
    }

    @Test
    fun aBackupWithHeadersKeepsItsOwnListOfUrlsWithoutThem() {
        // Its headers were set for its own URLs; what is on the device does not matter.
        val section = fullBackup().health

        val marked = section.urlsWithoutHeadersOnImport(
            deviceUrls = listOf("https://device/hook"),
            deviceUrlsWithoutHeaders = setOf("https://example.com/health"),
            deviceHasHeaders = true
        )

        assertEquals(setOf("https://backup.example.com/h"), marked)
    }

    @Test
    fun theHeadersKeptOnTheDeviceGoOnlyWhereTheyWentBefore() {
        // A backup without secrets keeps the device's headers. They were set for the device's
        // own URLs, so a URL new to the device, or one pairing added there, gets none.
        val section = SectionConfig(
            webhookUrls = listOf("https://mine/hook", "https://paired/hook", "https://shared-setup/hook")
        )

        val marked = section.urlsWithoutHeadersOnImport(
            deviceUrls = listOf("https://mine/hook", "https://paired/hook"),
            deviceUrlsWithoutHeaders = setOf("https://paired/hook"),
            deviceHasHeaders = true
        )

        assertEquals(setOf("https://paired/hook", "https://shared-setup/hook"), marked)
        // With no headers on the device nothing is held back: headers typed in later are
        // typed for the URLs on screen, like on a fresh install.
        assertTrue(section.urlsWithoutHeadersOnImport(emptyList(), emptySet(), deviceHasHeaders = false).isEmpty())
    }

    /**
     * A file as the iPhone app 1.4 writes it (SettingsBackup.export, JSONEncoder with sorted
     * keys): `platform`, no Screen Time section, only the shared broker and the health MQTT
     * keys, and none of the Android-only options.
     */
    private val iPhoneFile = """
        {
          "app_version" : "1.4.0",
          "exported_at" : "2026-09-30T10:15:30Z",
          "health" : {
            "headers" : {
              "Authorization" : "Bearer fixture-token"
            },
            "quiet_from" : "22:00",
            "quiet_to" : "07:00",
            "signing_secret" : "fixture-hmac",
            "sync_days" : "MONDAY,TUESDAY,WEDNESDAY,THURSDAY,FRIDAY",
            "sync_interval_minutes" : 30,
            "sync_mode" : "TIMES",
            "sync_times" : "07:30,21:00",
            "urls_without_headers" : [
              "http://homeassistant.local:8123/api/webhook/abc"
            ],
            "webhook_urls" : [
              "https://example.com/health",
              "http://homeassistant.local:8123/api/webhook/abc"
            ]
          },
          "mqtt" : {
            "health_base_topic" : "lifedashboard-ios",
            "health_enabled" : true,
            "health_use_shared" : true,
            "shared" : {
              "host" : "mqtt.example.com",
              "password" : "fixture-pass",
              "port" : 8883,
              "use_tls" : true,
              "username" : "fixture-user"
            }
          },
          "options" : {
            "allow_http_webhooks" : true,
            "enabled_data_types" : [
              "HEART_RATE",
              "MENSTRUATION_FLOW",
              "MENSTRUATION_PERIOD",
              "STEPS"
            ],
            "failure_notification_threshold" : 10,
            "failure_notifications_enabled" : false,
            "include_daily_totals" : false,
            "phone_name" : "Zoë's iPhone"
          },
          "platform" : "ios",
          "version" : 1
        }
    """.trimIndent()

    private val deviceScreenTime = MqttSectionSettings(
        enabled = true,
        useSharedBroker = false,
        ownBroker = MqttBroker("screen.lan", 1883, false, "screen-user", "screen-pass"),
        baseTopic = "lifedashboard"
    )

    private val deviceHealth = MqttSectionSettings(
        enabled = false,
        useSharedBroker = true,
        ownBroker = MqttBroker("", 1883, false, null, null),
        baseTopic = "lifedashboard"
    )

    @Test
    fun anIPhoneFileKeepsScreenTimeMqttAndTheDayBoundary() {
        val file = ConfigBackup.decode(iPhoneFile)
        assertTrue(file.isFromIPhone)
        val applied = file.forThisPhone()

        // No Screen Time section: the URLs, and with them the marks, stay as they are.
        assertNull(applied.screenTime.webhookUrls)
        assertNull(applied.screenTime.syncIntervalMinutes)
        // Nor the Android-only options: no reset to the defaults.
        assertNull(applied.options.screenTimeDayBoundaryHour)
        assertNull(applied.options.screenTimeUseDayBoundary)
        assertNull(applied.options.keepFullPayloads)
        assertNull(applied.options.seriesResolutions)
        assertNull(applied.options.receiveEnabled)
        // Screen Time MQTT is left whole, switch, own broker and credentials included.
        assertEquals(deviceScreenTime, applied.mqtt.sectionOnImport(MqttSection.SCREEN_TIME, deviceScreenTime, file.containsSecrets()))

        // What the iPhone does have, it brings.
        assertEquals(listOf("https://example.com/health", "http://homeassistant.local:8123/api/webhook/abc"), applied.health.webhookUrls)
        assertEquals("fixture-hmac", applied.health.signingSecret)
        assertEquals(10, applied.options.failureNotificationThreshold)
        assertEquals(false, applied.options.includeDailyTotals)
        assertEquals("mqtt.example.com", applied.mqtt.shared?.host)
        assertEquals(true, applied.mqtt.sectionOnImport(MqttSection.HEALTH, deviceHealth, file.containsSecrets()).enabled)
    }

    @Test
    fun anIPhoneFileDoesNotBringTheIPhonesTopicOrName() {
        val file = ConfigBackup.decode(iPhoneFile)
        val applied = file.forThisPhone()

        assertEquals("lifedashboard", applied.mqtt.sectionOnImport(MqttSection.HEALTH, deviceHealth, file.containsSecrets()).baseTopic)
        assertNull("the phone keeps its own name", applied.options.phoneName)
        assertEquals(
            listOf(
                ImportNote.SCREEN_TIME_KEPT,
                ImportNote.IPHONE_ANDROID_TYPES_KEPT,
                ImportNote.IPHONE_BASE_TOPIC_KEPT,
                ImportNote.IPHONE_PHONE_NAME_KEPT
            ),
            file.importNotes()
        )
        assertNull(file.summarise(emptySet()).screenTimeWebhooks)
    }

    @Test
    fun thePreviewCountsTheTypesThePhoneEndsUpWith() {
        val file = ConfigBackup.decode(iPhoneFile)
        // The file lists 4; the two Android-only types on the phone stay, SLEEP goes off.
        val onPhone = setOf(HealthDataType.BONE_MASS, HealthDataType.SKIN_TEMPERATURE, HealthDataType.SLEEP)
        assertEquals(6, file.summarise(onPhone).enabledDataTypes)
        // A file from this app replaces the list, so its own count is the count.
        assertEquals(3, fullBackup().summarise(onPhone).enabledDataTypes)
    }

    @Test
    fun aTopicTheIPhoneUserChoseIsTakenAsTheIPhoneAppDoes() {
        val file = ConfigBackup.decode(iPhoneFile.replace("\"lifedashboard-ios\"", "\"home/phones\""))
        assertEquals("home/phones", file.forThisPhone().mqtt.sectionOnImport(MqttSection.HEALTH, deviceHealth, false).baseTopic)
        assertFalse(ImportNote.IPHONE_BASE_TOPIC_KEPT in file.importNotes())
    }

    @Test
    fun anAndroidFileStillSetsEveryKeyItHas() {
        // Same topic and name as the iPhone's, but this app wrote it: nothing is dropped.
        val backup = fullBackup().copy(
            mqtt = fullBackup().mqtt.copy(healthBaseTopic = ConfigBackup.IOS_DEFAULT_BASE_TOPIC)
        )
        assertFalse(backup.isFromIPhone)
        assertEquals(backup, backup.forThisPhone())
        assertTrue(backup.importNotes().isEmpty())

        val screenTime = backup.mqtt.sectionOnImport(MqttSection.SCREEN_TIME, deviceScreenTime, backupHasSecrets = true)
        assertEquals(MqttSectionSettings(true, false, MqttBroker("other.local", 1883, false, "u2", "p2"), "lifedash/screen"), screenTime)
    }

    @Test
    fun theTypesTheIPhoneDoesNotHaveArePinned() {
        // When this fails, a type was added on either side: check HealthDataType.swift in the
        // iOS repo and update ConfigBackupManager.IPHONE_DATA_TYPES.
        assertEquals(
            setOf(HealthDataType.BONE_MASS, HealthDataType.BODY_WATER_MASS, HealthDataType.BASAL_METABOLIC_RATE, HealthDataType.SKIN_TEMPERATURE),
            HealthDataType.entries.toSet() - ConfigBackupManager.IPHONE_DATA_TYPES
        )
    }

    @Test
    fun anIPhoneFileDecidesOnlyTheTypesTheIPhoneHas() {
        val file = ConfigBackup.decode(iPhoneFile)
        val onPhone = setOf(HealthDataType.BONE_MASS, HealthDataType.SKIN_TEMPERATURE, HealthDataType.SLEEP, HealthDataType.STEPS)

        val after = ConfigBackupManager.dataTypesOnImport(file.options.enabledDataTypes!!, onPhone, file.isFromIPhone)

        assertEquals(
            setOf(
                // Android-only: the iPhone file cannot say anything about them, they stay on.
                HealthDataType.BONE_MASS, HealthDataType.SKIN_TEMPERATURE,
                // The iPhone has these: the file's list decides, so SLEEP goes off.
                HealthDataType.STEPS, HealthDataType.HEART_RATE, HealthDataType.MENSTRUATION_FLOW, HealthDataType.MENSTRUATION_PERIOD
            ),
            after
        )
        // An Android-only type that was off stays off.
        assertFalse(HealthDataType.BODY_WATER_MASS in after)
    }

    @Test
    fun anAndroidFileStillReplacesTheWholeTypeList() {
        val onPhone = setOf(HealthDataType.BONE_MASS, HealthDataType.SLEEP)
        assertEquals(
            setOf(HealthDataType.STEPS),
            ConfigBackupManager.dataTypesOnImport(listOf("STEPS"), onPhone, fromIPhone = false)
        )
    }

    @Test
    fun thisAppWritesNoPlatform() {
        assertFalse(fullBackup().encode().contains("\"platform\""))
    }

    @Test
    fun aFileWithNothingInItChangesNothing() {
        val empty = ConfigBackup.decode("""{"version": 1}""")
        assertNull(empty.health.webhookUrls)
        assertEquals(OptionsConfig(), empty.options)
        assertEquals(deviceHealth, empty.mqtt.sectionOnImport(MqttSection.HEALTH, deviceHealth, false))
        assertEquals(deviceScreenTime, empty.mqtt.sectionOnImport(MqttSection.SCREEN_TIME, deviceScreenTime, false))
    }
}
