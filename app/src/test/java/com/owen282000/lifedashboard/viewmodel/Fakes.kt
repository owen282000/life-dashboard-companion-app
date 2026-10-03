package com.owen282000.lifedashboard.viewmodel

import com.owen282000.lifedashboard.BackfillJob
import com.owen282000.lifedashboard.BackfillStatus
import com.owen282000.lifedashboard.HealthDataType
import com.owen282000.lifedashboard.HealthSyncResult
import com.owen282000.lifedashboard.MqttBroker
import com.owen282000.lifedashboard.MqttSection
import com.owen282000.lifedashboard.MqttSectionSettings
import com.owen282000.lifedashboard.ReceiveSettings
import com.owen282000.lifedashboard.ReceiveStatus
import com.owen282000.lifedashboard.ScreenTimeSyncResult
import com.owen282000.lifedashboard.WriteBackType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

internal fun emptyMqtt() = MqttDraft.from(
    MqttSectionSettings(enabled = false, useSharedBroker = true, ownBroker = MqttBroker("", 1883, false, null, null), baseTopic = "lifedashboard"),
    MqttBroker("", 1883, false, null, null)
)

internal class FakeAppSettings(
    var health: HealthDraft = HealthDraft(WebhookDraft(), emptySet(), emptyMqtt()),
    var screenTime: ScreenTimeDraft = ScreenTimeDraft(WebhookDraft(), "4", true, emptyMqtt())
) : AppSettings {
    var savedHealth = 0
    var savedScreenTime = 0
    var dailyTotals = true
    var allowHttp = false
    var certAlias: String? = null
    var storedPhoneName: String? = null
    var notifications = true
    var threshold = 3
    override var secretsUnavailable = false

    override fun loadHealth() = health
    override fun saveHealth(draft: HealthDraft, interval: Int) {
        savedHealth++
        health = draft.copy(schedule = draft.schedule.copy(intervalText = interval.toString()))
    }

    override fun loadScreenTime() = screenTime
    override fun saveScreenTime(draft: ScreenTimeDraft, interval: Int, dayBoundaryHour: Int) {
        savedScreenTime++
        screenTime = draft.copy(schedule = draft.schedule.copy(intervalText = interval.toString()), dayBoundaryHour = dayBoundaryHour.toString())
    }

    override fun setHealthEnabledTypes(types: Set<HealthDataType>) {
        health = health.copy(enabledTypes = types)
    }

    override fun includeDailyTotals() = dailyTotals
    override fun setIncludeDailyTotals(enabled: Boolean) { dailyTotals = enabled }
    override fun allowHttpWebhooks() = allowHttp
    override fun setAllowHttpWebhooks(enabled: Boolean) { allowHttp = enabled }
    override fun clientCertAlias() = certAlias
    override fun setClientCertAlias(alias: String?) { certAlias = alias }
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
    override fun lastMqttStatus(section: MqttSection): String? = null
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

    /** The backfill job as WorkManager would report it; tests move it along by hand. */
    val backfill = MutableStateFlow<BackfillStatus>(BackfillStatus.Idle)
    val backfillStarts = mutableListOf<Int>()
    var backfillCancels = 0
    var stopped: BackfillJob? = null
    override suspend fun availability() = availability
    override suspend fun grantedPermissions() = granted
    override suspend fun sync(): Result<HealthSyncResult> { syncs++; return syncResult }
    override suspend fun preview() = previewResult
    override fun backfillStatus(): Flow<BackfillStatus> = backfill
    override suspend fun startBackfill(days: Int) {
        backfillStarts += days
        backfill.value = BackfillStatus.Running(0, days / 3)
    }
    override fun cancelBackfill() {
        backfillCancels++
        backfill.value = BackfillStatus.Idle
    }
    override fun stoppedBackfill() = stopped
    override suspend fun testPing(webhook: WebhookDraft) = pingResult
    override suspend fun otherSourcesWriting(type: WriteBackType) = otherSources
}

internal class FakeScreenTimeOps(
    var usageAccess: Boolean = true,
    var syncResult: Result<ScreenTimeSyncResult> = Result.success(ScreenTimeSyncResult.Success(appCount = 5, dayCount = 1)),
    var previewResult: Result<String> = Result.success("{}"),
    var pingResult: Result<Unit> = Result.success(Unit)
) : ScreenTimeOps {
    var syncs = 0
    override fun hasUsageAccess() = usageAccess
    override suspend fun sync(): Result<ScreenTimeSyncResult> { syncs++; return syncResult }
    override suspend fun preview() = previewResult
    override suspend fun testPing(webhook: WebhookDraft) = pingResult
}
