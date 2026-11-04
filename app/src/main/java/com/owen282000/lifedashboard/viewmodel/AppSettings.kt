package com.owen282000.lifedashboard.viewmodel

import android.content.Context
import com.owen282000.lifedashboard.Destination
import com.owen282000.lifedashboard.HealthDataType
import com.owen282000.lifedashboard.LogType
import com.owen282000.lifedashboard.MqttBroker
import com.owen282000.lifedashboard.MqttSection
import com.owen282000.lifedashboard.MqttSectionSettings
import com.owen282000.lifedashboard.PreferencesManager
import com.owen282000.lifedashboard.ReceiveSettings
import com.owen282000.lifedashboard.ReceiveStatus
import com.owen282000.lifedashboard.ScreenTimeAppFilter
import com.owen282000.lifedashboard.SeriesResolution
import com.owen282000.lifedashboard.ServerTrust
import com.owen282000.lifedashboard.SyncFailureNotifier
import com.owen282000.lifedashboard.SyncSchedule
import com.owen282000.lifedashboard.SyncScheduler
import com.owen282000.lifedashboard.SyncStatusStore
import com.owen282000.lifedashboard.WriteBackType

/**
 * The slice of persisted settings the two sync tabs read and write, behind an interface so
 * the view models can be unit tested with an in-memory fake. [PreferencesAppSettings] is the
 * real one, backed by the process-wide [PreferencesManager].
 */
interface AppSettings {
    fun loadHealth(): HealthDraft
    fun loadScreenTime(): ScreenTimeDraft

    /** Written straight through when a fresh install pre-selects the types it already has permission for. */
    fun setHealthEnabledTypes(types: Set<HealthDataType>)

    /**
     * Whether the user made a choice of data types, an empty one included. Only without one do
     * the granted types become the selection, so None stays None whatever Health Connect grants.
     */
    fun healthTypesChosen(): Boolean
    fun setHealthTypesChosen(chosen: Boolean)
    fun setSeriesResolutions(resolutions: Map<HealthDataType, SeriesResolution>)

    /** Stores the schedule of [source]. WorkManager hears of it through [reschedule]. */
    fun setSchedule(source: LogType, schedule: SyncSchedule)

    /** Applies the stored schedule of [source]; replaces the queued work, so only after a real change. */
    fun reschedule(source: LogType)

    /** False when nothing was stored because the secure storage is not ready yet. */
    fun setDestinations(source: LogType, destinations: List<Destination>): Boolean

    /** The shared broker as it is stored now; either tab can have changed it. */
    fun sharedBroker(): MqttBroker

    /**
     * Stores the settings of [section], and the shared broker unless that is null. False when
     * nothing was stored: the login is a secret, under the same rule as a destination's.
     */
    fun setMqtt(section: MqttSection, settings: MqttSectionSettings, sharedBroker: MqttBroker?): Boolean
    fun clearMqttStatus(section: MqttSection)
    fun setDayBoundaryHour(hour: Int)
    fun setUseDayBoundary(enabled: Boolean)
    fun setScreenTimeAppFilter(filter: ScreenTimeAppFilter)
    fun keepFullPayloads(): Boolean
    fun setKeepFullPayloads(enabled: Boolean)

    /** The records the Health section delivered today, for Today when no figure fits. */
    fun healthRecordsToday(): Int

    fun includeDailyTotals(): Boolean
    fun setIncludeDailyTotals(enabled: Boolean)
    fun includeRecordMetadata(): Boolean
    fun setIncludeRecordMetadata(enabled: Boolean)
    fun allowHttpWebhooks(): Boolean
    fun setAllowHttpWebhooks(enabled: Boolean)
    fun clientCertAlias(): String?
    fun setClientCertAlias(alias: String?)

    /** Trust per webhook host beyond the system CAs (issue #160), keyed by `host:port`. Applied at once. */
    fun serverTrust(): Map<String, ServerTrust>
    fun setServerTrust(trust: Map<String, ServerTrust>)

    /** The phone's name for MQTT, shared by both tabs; null when it has none. */
    fun phoneName(): String?
    fun setPhoneName(name: String?)

    /** Receive (write-back from Home Assistant, issue #62). Applied at once, like the other Advanced settings. */
    fun receiveSettings(): ReceiveSettings
    fun setReceiveEnabled(enabled: Boolean)
    fun setReceiveTypes(types: Set<WriteBackType>)
    fun setReceiveOlderMeasurements(enabled: Boolean)
    fun setReceiveSourceUrl(url: String?)
    fun receiveStatus(): ReceiveStatus
    fun failureNotificationsEnabled(): Boolean
    fun setFailureNotificationsEnabled(enabled: Boolean)
    fun failureThreshold(): Int
    fun setFailureThreshold(threshold: Int)
    val secretsUnavailable: Boolean

    /** Saved secrets were lost and have to be entered again; saving one, or dismissing it, clears it. */
    val secretsNeedReentry: Boolean

    /** The user dismissed the request to enter the secrets again. */
    fun dismissSecretsReentry()
    fun lastMqttStatus(section: MqttSection): String?
}

class PreferencesAppSettings(
    private val context: Context,
    private val prefs: PreferencesManager
) : AppSettings {

    override fun loadHealth() = HealthDraft(
        schedule = ScheduleDraft.from(prefs.getSyncSchedule(LogType.HEALTH_CONNECT)),
        webhook = WebhookDraft(
            destinations = prefs.getDestinations(LogType.HEALTH_CONNECT),
            sectionSecret = prefs.getSectionSecret(LogType.HEALTH_CONNECT).orEmpty()
        ),
        enabledTypes = prefs.getHealthEnabledDataTypes(),
        resolutions = prefs.getSeriesResolutions(),
        mqtt = MqttDraft.from(prefs.getMqttSection(MqttSection.HEALTH), prefs.getSharedMqttBroker())
    )

    override fun loadScreenTime() = ScreenTimeDraft(
        schedule = ScheduleDraft.from(prefs.getSyncSchedule(LogType.SCREEN_TIME)),
        webhook = WebhookDraft(
            destinations = prefs.getDestinations(LogType.SCREEN_TIME),
            sectionSecret = prefs.getSectionSecret(LogType.SCREEN_TIME).orEmpty()
        ),
        dayBoundaryHour = prefs.getScreenTimeDayBoundaryHour().toString(),
        useDayBoundary = prefs.useScreenTimeDayBoundary(),
        mqtt = MqttDraft.from(prefs.getMqttSection(MqttSection.SCREEN_TIME), prefs.getSharedMqttBroker()),
        appFilter = prefs.getScreenTimeAppFilter()
    )

    override fun setHealthEnabledTypes(types: Set<HealthDataType>) = prefs.setHealthEnabledDataTypes(types)
    override fun healthTypesChosen() = prefs.healthTypesChosen()
    override fun setHealthTypesChosen(chosen: Boolean) = prefs.setHealthTypesChosen(chosen)
    override fun setSeriesResolutions(resolutions: Map<HealthDataType, SeriesResolution>) = prefs.setSeriesResolutions(resolutions)
    override fun setSchedule(source: LogType, schedule: SyncSchedule) = prefs.setSyncSchedule(source, schedule)
    override fun reschedule(source: LogType) = SyncScheduler.reschedule(context, source)
    override fun setDestinations(source: LogType, destinations: List<Destination>) = prefs.setDestinations(source, destinations)
    override fun sharedBroker() = prefs.getSharedMqttBroker()

    override fun setMqtt(section: MqttSection, settings: MqttSectionSettings, sharedBroker: MqttBroker?): Boolean {
        val source = if (section == MqttSection.HEALTH) LogType.HEALTH_CONNECT else LogType.SCREEN_TIME
        if (!prefs.canSetDestinations(source, prefs.getDestinations(source))) return false
        prefs.setMqttSection(section, settings)
        sharedBroker?.let { prefs.setSharedMqttBroker(it) }
        return true
    }

    override fun clearMqttStatus(section: MqttSection) = prefs.setLastMqttStatus(section, "")
    override fun setDayBoundaryHour(hour: Int) = prefs.setScreenTimeDayBoundaryHour(hour)
    override fun setUseDayBoundary(enabled: Boolean) = prefs.setUseScreenTimeDayBoundary(enabled)
    override fun setScreenTimeAppFilter(filter: ScreenTimeAppFilter) = prefs.setScreenTimeAppFilter(filter)
    override fun keepFullPayloads() = prefs.keepFullPayloads()
    override fun setKeepFullPayloads(enabled: Boolean) = prefs.setKeepFullPayloads(enabled)
    override fun healthRecordsToday() = SyncStatusStore.read(context, LogType.HEALTH_CONNECT).recordsToday

    override fun includeDailyTotals() = prefs.includeDailyTotals()
    override fun setIncludeDailyTotals(enabled: Boolean) = prefs.setIncludeDailyTotals(enabled)
    override fun includeRecordMetadata() = prefs.includeRecordMetadata()
    override fun setIncludeRecordMetadata(enabled: Boolean) = prefs.setIncludeRecordMetadata(enabled)
    override fun allowHttpWebhooks() = prefs.allowHttpWebhooks()
    override fun setAllowHttpWebhooks(enabled: Boolean) = prefs.setAllowHttpWebhooks(enabled)
    override fun clientCertAlias() = prefs.clientCertAlias()
    override fun setClientCertAlias(alias: String?) = prefs.setClientCertAlias(alias)
    override fun serverTrust() = prefs.serverTrust()
    override fun setServerTrust(trust: Map<String, ServerTrust>) = prefs.setServerTrust(trust)
    override fun phoneName() = prefs.getPhoneName()
    override fun setPhoneName(name: String?) = prefs.setPhoneName(name)
    override fun receiveSettings() = prefs.getReceiveSettings()
    override fun setReceiveEnabled(enabled: Boolean) = prefs.setReceiveEnabled(enabled)
    override fun setReceiveTypes(types: Set<WriteBackType>) = prefs.setReceiveTypes(types)
    override fun setReceiveOlderMeasurements(enabled: Boolean) = prefs.setReceiveOlderMeasurements(enabled)
    override fun setReceiveSourceUrl(url: String?) = prefs.setReceiveSourceUrl(url)
    override fun receiveStatus() = prefs.getReceiveStatus()
    override fun failureNotificationsEnabled() = SyncFailureNotifier.isEnabled(context)
    override fun setFailureNotificationsEnabled(enabled: Boolean) = SyncFailureNotifier.setEnabled(context, enabled)
    override fun failureThreshold() = SyncFailureNotifier.getThreshold(context)
    override fun setFailureThreshold(threshold: Int) = SyncFailureNotifier.setThreshold(context, threshold)
    override val secretsUnavailable: Boolean get() = prefs.secretsUnavailable
    override val secretsNeedReentry: Boolean get() = prefs.secretsNeedReentry
    override fun dismissSecretsReentry() = prefs.dismissSecretsReentry()
    override fun lastMqttStatus(section: MqttSection) = prefs.getLastMqttStatus(section)?.takeIf { it.isNotBlank() }
}
