package com.owen282000.lifedashboard.viewmodel

import android.content.Context
import com.owen282000.lifedashboard.HealthDataType
import com.owen282000.lifedashboard.LifeDashboardApplication
import com.owen282000.lifedashboard.LogType
import com.owen282000.lifedashboard.MqttSection
import com.owen282000.lifedashboard.PreferencesManager
import com.owen282000.lifedashboard.ReceiveSettings
import com.owen282000.lifedashboard.ReceiveStatus
import com.owen282000.lifedashboard.SyncFailureNotifier
import com.owen282000.lifedashboard.WriteBackType

/**
 * The slice of persisted settings the two sync tabs read and write, behind an interface so
 * the view models can be unit tested with an in-memory fake. [PreferencesAppSettings] is the
 * real one, backed by the process-wide [PreferencesManager].
 */
interface AppSettings {
    fun loadHealth(): HealthDraft
    fun saveHealth(draft: HealthDraft, interval: Int)
    fun loadScreenTime(): ScreenTimeDraft
    fun saveScreenTime(draft: ScreenTimeDraft, interval: Int, dayBoundaryHour: Int)

    /** Written straight through before a preview or export, so the sync manager sees the current toggles. */
    fun setHealthEnabledTypes(types: Set<HealthDataType>)

    fun includeDailyTotals(): Boolean
    fun setIncludeDailyTotals(enabled: Boolean)
    fun allowHttpWebhooks(): Boolean
    fun setAllowHttpWebhooks(enabled: Boolean)
    fun clientCertAlias(): String?
    fun setClientCertAlias(alias: String?)

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
    fun lastMqttStatus(section: MqttSection): String?
}

class PreferencesAppSettings(
    private val context: Context,
    private val prefs: PreferencesManager
) : AppSettings {

    override fun loadHealth() = HealthDraft(
        schedule = ScheduleDraft.from(prefs.getSyncSchedule(LogType.HEALTH_CONNECT)),
        webhook = WebhookDraft(
            urls = prefs.getHealthWebhookUrls(),
            headers = prefs.getHealthWebhookHeaders(),
            secret = prefs.getHealthWebhookSecret() ?: "",
            urlsWithoutHeaders = prefs.getHealthUrlsWithoutHeaders()
        ),
        enabledTypes = prefs.getHealthEnabledDataTypes(),
        resolutions = prefs.getSeriesResolutions(),
        mqtt = MqttDraft.from(prefs.getMqttSection(MqttSection.HEALTH), prefs.getSharedMqttBroker())
    )

    override fun saveHealth(draft: HealthDraft, interval: Int) {
        prefs.setSyncSchedule(LogType.HEALTH_CONNECT, draft.schedule.toSchedule(fallbackInterval = interval))
        prefs.setHealthWebhookUrls(draft.webhook.urls)
        prefs.setHealthEnabledDataTypes(draft.enabledTypes)
        prefs.setSeriesResolutions(draft.resolutions)
        prefs.setHealthWebhookHeaders(draft.webhook.headers)
        prefs.setHealthUrlsWithoutHeaders(draft.webhook.urlsWithoutHeaders)
        prefs.setHealthWebhookSecret(draft.webhook.secret.trim())
        val mqtt = draft.mqtt.withPort()
        prefs.setMqttSection(MqttSection.HEALTH, mqtt.section)
        prefs.setSharedMqttBroker(mqtt.sharedBroker)
        (context.applicationContext as? LifeDashboardApplication)?.scheduleHealthSyncWork()
    }

    override fun loadScreenTime() = ScreenTimeDraft(
        schedule = ScheduleDraft.from(prefs.getSyncSchedule(LogType.SCREEN_TIME)),
        webhook = WebhookDraft(
            urls = prefs.getScreenTimeWebhookUrls(),
            headers = prefs.getScreenTimeWebhookHeaders(),
            secret = prefs.getScreenTimeWebhookSecret() ?: "",
            urlsWithoutHeaders = prefs.getScreenTimeUrlsWithoutHeaders()
        ),
        dayBoundaryHour = prefs.getScreenTimeDayBoundaryHour().toString(),
        useDayBoundary = prefs.useScreenTimeDayBoundary(),
        mqtt = MqttDraft.from(prefs.getMqttSection(MqttSection.SCREEN_TIME), prefs.getSharedMqttBroker()),
        appFilter = prefs.getScreenTimeAppFilter()
    )

    override fun saveScreenTime(draft: ScreenTimeDraft, interval: Int, dayBoundaryHour: Int) {
        prefs.setSyncSchedule(LogType.SCREEN_TIME, draft.schedule.toSchedule(fallbackInterval = interval))
        prefs.setScreenTimeWebhookUrls(draft.webhook.urls)
        prefs.setScreenTimeDayBoundaryHour(dayBoundaryHour)
        prefs.setUseScreenTimeDayBoundary(draft.useDayBoundary)
        prefs.setScreenTimeAppFilter(draft.appFilter)
        prefs.setScreenTimeWebhookHeaders(draft.webhook.headers)
        prefs.setScreenTimeUrlsWithoutHeaders(draft.webhook.urlsWithoutHeaders)
        prefs.setScreenTimeWebhookSecret(draft.webhook.secret.trim())
        val mqtt = draft.mqtt.withPort()
        prefs.setMqttSection(MqttSection.SCREEN_TIME, mqtt.section)
        prefs.setSharedMqttBroker(mqtt.sharedBroker)
        (context.applicationContext as? LifeDashboardApplication)?.scheduleScreenTimeSyncWork()
    }

    override fun setHealthEnabledTypes(types: Set<HealthDataType>) = prefs.setHealthEnabledDataTypes(types)

    override fun includeDailyTotals() = prefs.includeDailyTotals()
    override fun setIncludeDailyTotals(enabled: Boolean) = prefs.setIncludeDailyTotals(enabled)
    override fun allowHttpWebhooks() = prefs.allowHttpWebhooks()
    override fun setAllowHttpWebhooks(enabled: Boolean) = prefs.setAllowHttpWebhooks(enabled)
    override fun clientCertAlias() = prefs.clientCertAlias()
    override fun setClientCertAlias(alias: String?) = prefs.setClientCertAlias(alias)
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
    override fun lastMqttStatus(section: MqttSection) = prefs.getLastMqttStatus(section)
}
