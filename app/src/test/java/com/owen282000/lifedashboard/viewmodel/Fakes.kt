package com.owen282000.lifedashboard.viewmodel

import com.owen282000.lifedashboard.AppChoice
import com.owen282000.lifedashboard.BackfillJob
import com.owen282000.lifedashboard.BackfillStart
import com.owen282000.lifedashboard.BackfillStatus
import com.owen282000.lifedashboard.Destination
import com.owen282000.lifedashboard.HealthDataType
import com.owen282000.lifedashboard.HealthSyncResult
import com.owen282000.lifedashboard.LogType
import com.owen282000.lifedashboard.MqttBroker
import com.owen282000.lifedashboard.MqttSection
import com.owen282000.lifedashboard.MqttSectionSettings
import com.owen282000.lifedashboard.ReceiveSettings
import com.owen282000.lifedashboard.ReceiveStatus
import com.owen282000.lifedashboard.ScreenTimeAppFilter
import com.owen282000.lifedashboard.ScreenTimeSyncResult
import com.owen282000.lifedashboard.SeriesResolution
import com.owen282000.lifedashboard.ServerTrust
import com.owen282000.lifedashboard.SyncSchedule
import com.owen282000.lifedashboard.TodayDay
import com.owen282000.lifedashboard.TodayKind
import com.owen282000.lifedashboard.WriteBackType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow

internal fun emptyMqtt() = MqttDraft.from(
    MqttSectionSettings(enabled = false, useSharedBroker = true, ownBroker = MqttBroker("", 1883, false, null, null), baseTopic = "lifedashboard"),
    MqttBroker("", 1883, false, null, null)
)

internal class FakeAppSettings(
    var health: HealthDraft = HealthDraft(WebhookDraft(), emptySet(), emptyMqtt()),
    var screenTime: ScreenTimeDraft = ScreenTimeDraft(WebhookDraft(), "4", true, emptyMqtt())
) : AppSettings {
    /** The secure storage is not ready: every save is refused and stores nothing. */
    var refuseSaves = false
    var dailyTotals = true
    var allowHttp = false
    var certAlias: String? = null
    var storedPhoneName: String? = null
    var notifications = true
    var threshold = 3
    override var secretsUnavailable = false
    override var secretsNeedReentry = false
    override fun dismissSecretsReentry() {
        secretsNeedReentry = false
    }

    /** The one shared broker both sections read, as in the real settings. */
    var broker: MqttBroker = health.mqtt.sharedBroker
    var brokerWrites = 0
    var destinationWrites = 0
    val reschedules = mutableListOf<LogType>()
    val scheduleWrites = mutableListOf<LogType>()
    var typesChosen = false
    var fullPayloads = false
    var recordsToday = 0
    val clearedStatus = mutableListOf<MqttSection>()

    override fun loadHealth() = health.copy(mqtt = MqttDraft.from(health.mqtt.section, broker))
    override fun loadScreenTime() = screenTime.copy(mqtt = MqttDraft.from(screenTime.mqtt.section, broker))
    override fun healthTypesChosen() = typesChosen
    override fun setHealthTypesChosen(chosen: Boolean) { typesChosen = chosen }
    override fun setSeriesResolutions(resolutions: Map<HealthDataType, SeriesResolution>) {
        health = health.copy(resolutions = resolutions)
    }

    override fun setSchedule(source: LogType, schedule: SyncSchedule) {
        scheduleWrites += source
        val draft = ScheduleDraft.from(schedule)
        if (source == LogType.HEALTH_CONNECT) health = health.copy(schedule = draft) else screenTime = screenTime.copy(schedule = draft)
    }

    override fun reschedule(source: LogType) { reschedules += source }

    /** As PreferencesManager.setDestinations: Receive follows its source, and forgets one that is gone. */
    override fun setDestinations(source: LogType, destinations: List<Destination>): Boolean {
        if (refuseSaves) return false
        destinationWrites++
        if (source == LogType.HEALTH_CONNECT) {
            health = health.copy(webhook = health.webhook.copy(destinations = destinations))
            if (destinations.none { it.url == receive.sourceUrl }) receive = receive.copy(sourceUrl = null)
        } else {
            screenTime = screenTime.copy(webhook = screenTime.webhook.copy(destinations = destinations))
        }
        return true
    }

    override fun sharedBroker() = broker
    override fun setMqtt(section: MqttSection, settings: MqttSectionSettings, sharedBroker: MqttBroker?): Boolean {
        if (refuseSaves) return false
        if (section == MqttSection.HEALTH) {
            health = health.copy(mqtt = health.mqtt.copy(section = settings))
        } else {
            screenTime = screenTime.copy(mqtt = screenTime.mqtt.copy(section = settings))
        }
        if (sharedBroker != null) {
            broker = sharedBroker
            brokerWrites++
        }
        return true
    }

    override fun clearMqttStatus(section: MqttSection) { clearedStatus += section }
    override fun setDayBoundaryHour(hour: Int) { screenTime = screenTime.copy(dayBoundaryHour = hour.toString()) }
    override fun setUseDayBoundary(enabled: Boolean) { screenTime = screenTime.copy(useDayBoundary = enabled) }
    override fun setScreenTimeAppFilter(filter: ScreenTimeAppFilter) { screenTime = screenTime.copy(appFilter = filter) }
    override fun keepFullPayloads() = fullPayloads
    override fun setKeepFullPayloads(enabled: Boolean) { fullPayloads = enabled }
    override fun healthRecordsToday() = recordsToday

    override fun setHealthEnabledTypes(types: Set<HealthDataType>) {
        health = health.copy(enabledTypes = types)
    }

    override fun includeDailyTotals() = dailyTotals
    override fun setIncludeDailyTotals(enabled: Boolean) { dailyTotals = enabled }
    var recordMetadata = false
    override fun includeRecordMetadata() = recordMetadata
    override fun setIncludeRecordMetadata(enabled: Boolean) { recordMetadata = enabled }
    override fun allowHttpWebhooks() = allowHttp
    override fun setAllowHttpWebhooks(enabled: Boolean) { allowHttp = enabled }
    override fun clientCertAlias() = certAlias
    override fun setClientCertAlias(alias: String?) { certAlias = alias }
    var trust: Map<String, ServerTrust> = emptyMap()
    override fun serverTrust() = trust
    override fun setServerTrust(trust: Map<String, ServerTrust>) { this.trust = trust }
    override fun phoneName() = storedPhoneName
    override fun setPhoneName(name: String?) { storedPhoneName = name?.trim()?.takeIf { it.isNotEmpty() } }
    var receive = ReceiveSettings()
    var status = ReceiveStatus()
    override fun receiveSettings() = receive
    override fun setReceiveEnabled(enabled: Boolean) { receive = receive.copy(enabled = enabled) }
    override fun setReceiveTypes(types: Set<WriteBackType>) { receive = receive.copy(types = types) }
    override fun setReceiveOlderMeasurements(enabled: Boolean) { receive = receive.copy(olderMeasurements = enabled) }
    override fun setReceiveSourceUrl(url: String?) { receive = receive.copy(sourceUrl = url) }
    override fun receiveStatus() = status
    override fun failureNotificationsEnabled() = notifications
    override fun setFailureNotificationsEnabled(enabled: Boolean) { notifications = enabled }
    override fun failureThreshold() = threshold
    override fun setFailureThreshold(threshold: Int) { this.threshold = threshold }
    var mqttStatus: String? = null
    override fun lastMqttStatus(section: MqttSection): String? = mqttStatus
}

internal class FakeHealthOps(
    var availability: HcAvailability = HcAvailability.AVAILABLE,
    var granted: Set<String> = setOf("android.permission.health.READ_STEPS"),
    var syncResult: Result<HealthSyncResult> = Result.success(HealthSyncResult.Success(mapOf(HealthDataType.STEPS to 12))),
    var previewResult: Result<String> = Result.success("{}"),
    var pingResult: Result<Unit> = Result.success(Unit),
    var otherSources: List<String> = emptyList()
) : HealthOps {
    var syncs = 0

    /**
     * The backfill job as WorkManager would report it; tests move it along by hand. Every
     * report reaches the screen, also one equal to the last, as WorkManager's do.
     */
    private val backfillReports = MutableSharedFlow<BackfillStatus>(replay = 1, extraBufferCapacity = 16).apply { tryEmit(BackfillStatus.Idle) }
    var backfill: BackfillStatus
        get() = backfillReports.replayCache.last()
        set(value) {
            backfillReports.tryEmit(value)
        }
    val backfillStarts = mutableListOf<Int>()
    var backfillCancels = 0
    var stopped: BackfillJob? = null

    /** The oldest record the dialog and a backfill of all history find; null is none. */
    var earliest: Long? = null
    var earliestLookups = 0

    /** Stop cleared the job, so a start replaces the work that still reports itself running, as BackfillWork does. */
    private var cancelled = false

    /** A sync in the worker, as the store and WorkManager report it. */
    val dataChanged = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
    val workerState = MutableStateFlow(false)
    override fun dataChanges(): Flow<Unit> = dataChanged
    override fun workerRunning(): Flow<Boolean> = workerState

    override suspend fun availability() = availability
    override suspend fun grantedPermissions() = granted
    override suspend fun sync(): Result<HealthSyncResult> { syncs++; return syncResult }
    val previewTypes = mutableListOf<Set<HealthDataType>>()
    val previewResolutions = mutableListOf<Map<HealthDataType, SeriesResolution>>()
    override suspend fun preview(types: Set<HealthDataType>, resolutions: Map<HealthDataType, SeriesResolution>): Result<String> {
        previewTypes += types
        previewResolutions += resolutions
        return previewResult
    }
    override fun backfillStatus(): Flow<BackfillStatus> = backfillReports
    override suspend fun startBackfill(days: Int): BackfillStart {
        if (backfill is BackfillStatus.Running && !cancelled) return BackfillStart.ALREADY_RUNNING
        if (days == BackfillJob.ALL_HISTORY && earliest == null) return BackfillStart.NO_RECORDS
        cancelled = false
        backfillStarts += days
        backfill = BackfillStatus.Running(0, days / 3)
        return BackfillStart.STARTED
    }

    /** Like WorkManager, the cancelled work still reports itself running until a test moves it on. */
    override fun cancelBackfill() {
        backfillCancels++
        cancelled = true
    }
    override fun stoppedBackfill() = stopped
    override suspend fun earliestRecord(): Long? { earliestLookups++; return earliest }
    val pinged = mutableListOf<WebhookDraft>()
    override suspend fun testPing(webhook: WebhookDraft): Result<Unit> {
        pinged += webhook
        return pingResult
    }
    override suspend fun otherSourcesWriting(type: WriteBackType) = otherSources

    /** What Health Connect answers for the Today panel; null is a figure that cannot be read now. */
    var today: Map<TodayKind, List<TodayDay>?> = emptyMap()
    val todayAsked = mutableListOf<Set<TodayKind>>()
    override suspend fun todaySeries(kinds: Set<TodayKind>): Map<TodayKind, List<TodayDay>?> {
        todayAsked += kinds
        return today.filterKeys { it in kinds }
    }
}

internal class FakeScreenTimeOps(
    var usageAccess: Boolean = true,
    var syncResult: Result<ScreenTimeSyncResult> = Result.success(ScreenTimeSyncResult.Success(appCount = 5, dayCount = 1)),
    var previewResult: Result<String> = Result.success("{}"),
    var pingResult: Result<Unit> = Result.success(Unit),
    var choices: List<AppChoice> = emptyList()
) : ScreenTimeOps {
    var syncs = 0
    var choiceLoads = 0

    /** See FakeHealthOps. */
    val dataChanged = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
    val workerState = MutableStateFlow(false)
    override fun dataChanges(): Flow<Unit> = dataChanged
    override fun workerRunning(): Flow<Boolean> = workerState

    override suspend fun appChoices(listed: Set<String>): List<AppChoice> { choiceLoads++; return choices }
    override fun hasUsageAccess() = usageAccess
    override suspend fun sync(): Result<ScreenTimeSyncResult> { syncs++; return syncResult }
    override suspend fun preview() = previewResult
    val pinged = mutableListOf<WebhookDraft>()
    override suspend fun testPing(webhook: WebhookDraft): Result<Unit> {
        pinged += webhook
        return pingResult
    }
}
