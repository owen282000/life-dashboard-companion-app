package com.owen282000.lifedashboard.viewmodel

import android.content.Context
import com.owen282000.lifedashboard.HealthDataType
import com.owen282000.lifedashboard.LifeDashboardApplication
import com.owen282000.lifedashboard.LogType
import com.owen282000.lifedashboard.MqttSection
import com.owen282000.lifedashboard.PreferencesManager
import com.owen282000.lifedashboard.ReceiveSettings
import com.owen282000.lifedashboard.ReceiveStatus
import com.owen282000.lifedashboard.ServerTrust
import com.owen282000.lifedashboard.SyncFailureNotifier
import com.owen282000.lifedashboard.WriteBackType

/**
 * The slice of persisted settings the two sync tabs read and write, behind an interface so
 * the view models can be unit tested with an in-memory fake. [PreferencesAppSettings] is the
 * real one, backed by the process-wide [PreferencesManager].
 */
interface AppSettings {
    fun loadHealth(): HealthDraft

    /** False when nothing was saved because the secure storage is not ready yet; the draft stays unsaved. */
    fun saveHealth(draft: HealthDraft, interval: Int): Boolean
    fun loadScreenTime(): ScreenTimeDraft

    /** False when nothing was saved, see [saveHealth]. */
    fun saveScreenTime(draft: ScreenTimeDraft, interval: Int, dayBoundaryHour: Int): Boolean

    /** Written straight through when a fresh install pre-selects the types it already has permission for. */
    fun setHealthEnabledTypes(types: Set<HealthDataType>)

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

    override fun saveHealth(draft: HealthDraft, interval: Int): Boolean {
        // First, so a refusal leaves every setting of the tab as it was.
        if (!prefs.setDestinations(LogType.HEALTH_CONNECT, draft.webhook.destinations)) return false
        prefs.setSyncSchedule(LogType.HEALTH_CONNECT, draft.schedule.toSchedule(fallbackInterval = interval))
        prefs.setHealthEnabledDataTypes(draft.enabledTypes)
        prefs.setSeriesResolutions(draft.resolutions)
        val mqtt = draft.mqtt.withPort()
        prefs.setMqttSection(MqttSection.HEALTH, mqtt.section)
        prefs.setSharedMqttBroker(mqtt.sharedBroker)
        (context.applicationContext as? LifeDashboardApplication)?.scheduleHealthSyncWork()
        return true
    }

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

    override fun saveScreenTime(draft: ScreenTimeDraft, interval: Int, dayBoundaryHour: Int): Boolean {
        if (!prefs.setDestinations(LogType.SCREEN_TIME, draft.webhook.destinations)) return false
        prefs.setSyncSchedule(LogType.SCREEN_TIME, draft.schedule.toSchedule(fallbackInterval = interval))
        prefs.setScreenTimeDayBoundaryHour(dayBoundaryHour)
        prefs.setUseScreenTimeDayBoundary(draft.useDayBoundary)
        prefs.setScreenTimeAppFilter(draft.appFilter)
        val mqtt = draft.mqtt.withPort()
        prefs.setMqttSection(MqttSection.SCREEN_TIME, mqtt.section)
        prefs.setSharedMqttBroker(mqtt.sharedBroker)
        (context.applicationContext as? LifeDashboardApplication)?.scheduleScreenTimeSyncWork()
        return true
    }

    override fun setHealthEnabledTypes(types: Set<HealthDataType>) = prefs.setHealthEnabledDataTypes(types)

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
    override fun lastMqttStatus(section: MqttSection) = prefs.getLastMqttStatus(section)
}
