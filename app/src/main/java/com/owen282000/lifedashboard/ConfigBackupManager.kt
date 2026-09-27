package com.owen282000.lifedashboard

import android.content.Context
import java.time.Instant
import java.time.LocalTime

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
        val receive = prefs.getReceiveSettings()

        return ConfigBackup(
            exportedAt = Instant.now().toString(),
            appVersion = runCatching {
                context.packageManager.getPackageInfo(context.packageName, 0).versionName
            }.getOrNull(),
            health = sectionConfig(
                LogType.HEALTH_CONNECT,
                webhookUrls = prefs.getHealthWebhookUrls(),
                headers = prefs.getHealthWebhookHeaders(),
                signingSecret = prefs.getHealthWebhookSecret(),
                urlsWithoutHeaders = prefs.getHealthUrlsWithoutHeaders()
            ),
            screenTime = sectionConfig(
                LogType.SCREEN_TIME,
                webhookUrls = prefs.getScreenTimeWebhookUrls(),
                headers = prefs.getScreenTimeWebhookHeaders(),
                signingSecret = prefs.getScreenTimeWebhookSecret(),
                urlsWithoutHeaders = prefs.getScreenTimeUrlsWithoutHeaders()
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
                failureNotificationThreshold = SyncFailureNotifier.getThreshold(context),
                seriesResolutions = prefs.getSeriesResolutions()
                    .filterValues { it != DEFAULT_RESOLUTION }
                    .entries.sortedBy { it.key.name }
                    .associate { it.key.name to it.value.name },
                // Empty rather than absent for "none", so a new export can clear what an
                // older one must leave alone.
                phoneName = prefs.getPhoneName().orEmpty(),
                receiveEnabled = receive.enabled,
                receiveTypes = WriteBackType.entries.filter { it in receive.types }.map { it.key },
                receiveOlderMeasurements = receive.olderMeasurements,
                receiveSourceUrl = receive.sourceUrl.orEmpty()
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
            // Worked out against the device before its URLs and headers are replaced.
            prefs.setHealthUrlsWithoutHeaders(
                urlsWithoutHeadersOnImport(
                    prefs.getHealthWebhookUrls(),
                    prefs.getHealthUrlsWithoutHeaders(),
                    prefs.getHealthWebhookHeaders().isNotEmpty()
                )
            )
            prefs.setHealthWebhookUrls(webhookUrls)
            if (headers.isNotEmpty()) prefs.setHealthWebhookHeaders(headers)
            if (!signingSecret.isNullOrBlank()) prefs.setHealthWebhookSecret(signingSecret)
            syncIntervalMinutes?.let { prefs.setHealthSyncIntervalMinutes(it) }
            restoreSchedule(LogType.HEALTH_CONNECT, this)
        }

        with(backup.screenTime) {
            prefs.setScreenTimeUrlsWithoutHeaders(
                urlsWithoutHeadersOnImport(
                    prefs.getScreenTimeWebhookUrls(),
                    prefs.getScreenTimeUrlsWithoutHeaders(),
                    prefs.getScreenTimeWebhookHeaders().isNotEmpty()
                )
            )
            prefs.setScreenTimeWebhookUrls(webhookUrls)
            if (headers.isNotEmpty()) prefs.setScreenTimeWebhookHeaders(headers)
            if (!signingSecret.isNullOrBlank()) prefs.setScreenTimeWebhookSecret(signingSecret)
            syncIntervalMinutes?.let { prefs.setScreenTimeSyncIntervalMinutes(it) }
            restoreSchedule(LogType.SCREEN_TIME, this)
        }

        val hasSecrets = backup.containsSecrets()
        with(backup.mqtt) {
            prefs.setSharedMqttBroker(shared.toBroker(prefs.getSharedMqttBroker(), hasSecrets))
            prefs.setMqttSection(
                MqttSection.HEALTH,
                MqttSectionSettings(
                    enabled = healthEnabled,
                    useSharedBroker = healthUseShared,
                    ownBroker = healthOwnBroker.toBroker(prefs.getMqttSection(MqttSection.HEALTH).ownBroker, hasSecrets),
                    baseTopic = healthBaseTopic
                )
            )
            prefs.setMqttSection(
                MqttSection.SCREEN_TIME,
                MqttSectionSettings(
                    enabled = screenTimeEnabled,
                    useSharedBroker = screenTimeUseShared,
                    ownBroker = screenTimeOwnBroker.toBroker(prefs.getMqttSection(MqttSection.SCREEN_TIME).ownBroker, hasSecrets),
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
            // A backup from before 1.20.0 carries none of these: the phone name, the Receive
            // switches and the source URL (and with it the ledger) stay as they are.
            phoneName?.let { prefs.setPhoneName(it) }
            receiveEnabled?.let { prefs.setReceiveEnabled(it) }
            receiveTypes?.let { names -> prefs.setReceiveTypes(names.mapNotNull { ConfigBackupManager.writeBackTypeFrom(it) }.toSet()) }
            receiveOlderMeasurements?.let { prefs.setReceiveOlderMeasurements(it) }
            // Only a URL the health section actually has; the URLs were written above.
            receiveSourceUrl?.let { url -> prefs.setReceiveSourceUrl(url.takeIf { it in backup.health.webhookUrls }) }
            // Null means a backup from before resolutions existed: leave the setting alone.
            seriesResolutions?.let { stored ->
                prefs.setSeriesResolutions(
                    stored.mapNotNull { (type, resolution) ->
                        runCatching { HealthDataType.valueOf(type) }.getOrNull()?.let { it to SeriesResolution.from(resolution) }
                    }.toMap()
                )
            }
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

        /** A Receive type by its protocol key, or by the enum name a pre-release build wrote; unknown is dropped. */
        fun writeBackTypeFrom(name: String): WriteBackType? =
            WriteBackType.fromKey(name) ?: WriteBackType.entries.firstOrNull { it.name == name }
    }

    /** One section's webhook and schedule settings, as the backup stores them. */
    private fun sectionConfig(
        source: LogType,
        webhookUrls: List<String>,
        headers: Map<String, String>,
        signingSecret: String?,
        urlsWithoutHeaders: Set<String>
    ): SectionConfig {
        val schedule = prefs.getSyncSchedule(source)
        return SectionConfig(
            webhookUrls = webhookUrls,
            headers = headers,
            signingSecret = signingSecret,
            urlsWithoutHeaders = webhookUrls.filter { it in urlsWithoutHeaders },
            syncIntervalMinutes = schedule.intervalMinutes,
            syncMode = schedule.mode.name,
            syncTimes = SyncSchedule.formatTimes(schedule.times),
            syncDays = SyncSchedule.formatDays(schedule.days),
            quietFrom = schedule.quietWindow?.from?.toString(),
            quietTo = schedule.quietWindow?.to?.toString()
        )
    }

    /**
     * Applies the schedule fields of a backup. A backup from before 1.14.0 carries none of
     * them, so the stored schedule stays as it is and only the interval, handled above, moves.
     */
    private fun restoreSchedule(source: LogType, config: SectionConfig) {
        if (config.syncMode == null && config.syncTimes == null && config.syncDays == null &&
            config.quietFrom == null && config.quietTo == null
        ) return

        val current = prefs.getSyncSchedule(source)
        val quiet = if (config.quietFrom != null && config.quietTo != null) {
            runCatching { QuietWindow(LocalTime.parse(config.quietFrom), LocalTime.parse(config.quietTo)) }.getOrNull()
        } else null
        prefs.setSyncSchedule(
            source,
            current.copy(
                mode = config.syncMode?.let { name -> runCatching { SyncMode.valueOf(name) }.getOrNull() } ?: current.mode,
                intervalMinutes = config.syncIntervalMinutes ?: current.intervalMinutes,
                times = config.syncTimes?.let { SyncSchedule.parseTimes(it) } ?: current.times,
                days = config.syncDays?.let { SyncSchedule.parseDays(it) } ?: current.days,
                quietWindow = quiet
            )
        )
    }
}
