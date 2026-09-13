package com.owen282000.lifedashboard

import android.content.Context
import java.time.Instant

/**
 * Reads the current settings into a [ConfigBackup] and writes one back.
 *
 * Deliberately does not touch sync state (last-sync watermarks, webhook logs, lifetime stats):
 * those describe this install's progress against Health Connect, and restoring them on another
 * device would make the next sync skip everything written before the imported watermark.
 */
class ConfigBackupManager(private val context: Context) {

    private val prefs = PreferencesManager(context)

    /** Snapshot of every user-configurable setting. */
    fun export(): ConfigBackup {
        val healthSection = prefs.getMqttSection(MqttSection.HEALTH)
        val screenTimeSection = prefs.getMqttSection(MqttSection.SCREEN_TIME)

        return ConfigBackup(
            exportedAt = Instant.now().toString(),
            appVersion = runCatching {
                context.packageManager.getPackageInfo(context.packageName, 0).versionName
            }.getOrNull(),
            health = SectionConfig(
                webhookUrls = prefs.getHealthWebhookUrls(),
                headers = prefs.getHealthWebhookHeaders(),
                signingSecret = prefs.getHealthWebhookSecret(),
                syncIntervalMinutes = prefs.getHealthSyncIntervalMinutes()
            ),
            screenTime = SectionConfig(
                webhookUrls = prefs.getScreenTimeWebhookUrls(),
                headers = prefs.getScreenTimeWebhookHeaders(),
                signingSecret = prefs.getScreenTimeWebhookSecret(),
                syncIntervalMinutes = prefs.getScreenTimeSyncIntervalMinutes()
            ),
            mqtt = MqttConfig(
                shared = BrokerConfig.from(prefs.getSharedMqttBroker()),
                healthEnabled = healthSection.enabled,
                healthUseShared = healthSection.useSharedBroker,
                healthBaseTopic = healthSection.baseTopic,
                healthOwnBroker = BrokerConfig.from(healthSection.ownBroker),
                screenTimeEnabled = screenTimeSection.enabled,
                screenTimeUseShared = screenTimeSection.useSharedBroker,
                screenTimeBaseTopic = screenTimeSection.baseTopic,
                screenTimeOwnBroker = BrokerConfig.from(screenTimeSection.ownBroker)
            ),
            options = OptionsConfig(
                enabledDataTypes = prefs.getHealthEnabledDataTypes().map { it.name }.sorted(),
                includeDailyTotals = prefs.includeDailyTotals(),
                allowHttpWebhooks = prefs.allowHttpWebhooks(),
                keepFullPayloads = prefs.keepFullPayloads(),
                screenTimeDayBoundaryHour = prefs.getScreenTimeDayBoundaryHour(),
                screenTimeUseDayBoundary = prefs.useScreenTimeDayBoundary(),
                failureNotificationThreshold = SyncFailureNotifier.getThreshold(context)
            )
        )
    }

    /**
     * Applies [backup] to the live settings.
     *
     * Secrets are only written when the backup actually carries them, so importing a
     * secret-free export keeps the credentials already on the device instead of wiping them.
     */
    fun import(backup: ConfigBackup) {
        with(backup.health) {
            prefs.setHealthWebhookUrls(webhookUrls)
            if (headers.isNotEmpty()) prefs.setHealthWebhookHeaders(headers)
            if (!signingSecret.isNullOrBlank()) prefs.setHealthWebhookSecret(signingSecret)
            syncIntervalMinutes?.let { prefs.setHealthSyncIntervalMinutes(it) }
        }

        with(backup.screenTime) {
            prefs.setScreenTimeWebhookUrls(webhookUrls)
            if (headers.isNotEmpty()) prefs.setScreenTimeWebhookHeaders(headers)
            if (!signingSecret.isNullOrBlank()) prefs.setScreenTimeWebhookSecret(signingSecret)
            syncIntervalMinutes?.let { prefs.setScreenTimeSyncIntervalMinutes(it) }
        }

        with(backup.mqtt) {
            prefs.setSharedMqttBroker(shared.toBroker())
            prefs.setMqttSection(
                MqttSection.HEALTH,
                MqttSectionSettings(
                    enabled = healthEnabled,
                    useSharedBroker = healthUseShared,
                    ownBroker = healthOwnBroker.toBroker(),
                    baseTopic = healthBaseTopic
                )
            )
            prefs.setMqttSection(
                MqttSection.SCREEN_TIME,
                MqttSectionSettings(
                    enabled = screenTimeEnabled,
                    useSharedBroker = screenTimeUseShared,
                    ownBroker = screenTimeOwnBroker.toBroker(),
                    baseTopic = screenTimeBaseTopic
                )
            )
        }

        with(backup.options) {
            prefs.setHealthEnabledDataTypes(ConfigBackupManager.dataTypesFrom(enabledDataTypes))
            prefs.setIncludeDailyTotals(includeDailyTotals)
            prefs.setAllowHttpWebhooks(allowHttpWebhooks)
            prefs.setKeepFullPayloads(keepFullPayloads)
            prefs.setScreenTimeDayBoundaryHour(screenTimeDayBoundaryHour)
            prefs.setUseScreenTimeDayBoundary(screenTimeUseDayBoundary)
            failureNotificationThreshold?.let { SyncFailureNotifier.setThreshold(context, it) }
        }
    }

    companion object {
        /** Filename of a plain export; the encrypted one gets [ENCRYPTED_SUFFIX]. */
        const val FILENAME = "life-dashboard-config.json"
        const val ENCRYPTED_FILENAME = "life-dashboard-config.encrypted.json"
        const val ENCRYPTED_SUFFIX = ".encrypted"

        /**
         * Maps stored enum names back to data types, dropping any this build does not know.
         * Keeps an export from a newer version importable into an older one.
         */
        fun dataTypesFrom(names: List<String>): Set<HealthDataType> {
            val known = HealthDataType.entries.associateBy { it.name }
            return names.mapNotNull { known[it] }.toSet()
        }
    }
}
