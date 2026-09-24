package com.owen282000.lifedashboard.viewmodel

import android.content.Context
import androidx.health.connect.client.permission.HealthPermission
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.owen282000.lifedashboard.HealthConnectManager
import com.owen282000.lifedashboard.HealthDataType
import com.owen282000.lifedashboard.HealthSyncResult
import com.owen282000.lifedashboard.MqttSection
import com.owen282000.lifedashboard.SeriesResolution
import com.owen282000.lifedashboard.SyncSchedule
import com.owen282000.lifedashboard.appPreferences
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class HealthUiState(
    val saved: HealthDraft,
    val draft: HealthDraft,
    val availability: HcAvailability = HcAvailability.AVAILABLE,
    /** null until the first permission check has run. */
    val hasPermissions: Boolean? = null,
    val grantedPermissions: Set<String> = emptySet(),
    val includeDailyTotals: Boolean = false,
    val allowHttpWebhooks: Boolean = false,
    /** KeyChain alias of the client certificate (mTLS) presented to webhooks, null for none. */
    val clientCertAlias: String? = null,
    val failureNotificationsEnabled: Boolean = false,
    val failureThreshold: Int = 3,
    val secretsUnavailable: Boolean = false,
    val mqttLastStatus: String? = null,
    val isSyncing: Boolean = false,
    val isPreviewing: Boolean = false,
    val isPinging: Boolean = false,
    val isExporting: Boolean = false,
    val backfillProgress: Pair<Int, Int>? = null,
    /** The line under the sync actions; stays until the next action replaces it. */
    val syncMessage: UiMessage? = null,
    val previewData: String? = null,
    val exportJson: String? = null,
    /** A data type whose toggle needs a permission grant first. */
    val permissionPrompt: HealthDataType? = null,
    val backfillDialog: Boolean = false
) {
    val hasChanges: Boolean get() = draft.differsFrom(saved)
    val hasAnyPermission: Boolean get() = grantedPermissions.isNotEmpty()
    val canSync: Boolean get() = !isSyncing && draft.hasDestination && draft.enabledTypes.isNotEmpty()
}

/** Everything the Health Connect screen can ask for; the view model implements it, previews can fake it. */
interface HealthActions {
    fun setSyncInterval(text: String)
    fun setSchedule(schedule: ScheduleDraft)
    fun setResolution(type: HealthDataType, resolution: SeriesResolution)
    fun addUrl(url: String)
    fun removeUrl(index: Int)
    fun addHeader(key: String, value: String)
    fun removeHeader(key: String)
    fun setSecret(secret: String)
    fun toggleType(type: HealthDataType, enabled: Boolean)
    fun dismissPermissionPrompt()
    fun setMqtt(mqtt: MqttDraft)
    fun setIncludeDailyTotals(enabled: Boolean)
    fun setAllowHttpWebhooks(enabled: Boolean)
    fun setClientCertAlias(alias: String?)
    fun setFailureNotifications(enabled: Boolean)
    fun setFailureThreshold(threshold: Int)
    fun save()

    /**
     * Re-read the settings from storage, discarding the draft.
     *
     * For changes made outside this screen, such as QR pairing writing the webhook
     * address and secret for both sections at once. Saved and draft are set together, so
     * the unsaved-changes bar does not appear for something the user did not type.
     */
    fun reloadFromSettings()
    fun syncNow()
    fun preview()
    fun dismissPreview()
    fun testPing()
    fun export()
    fun dismissExport()
    fun openBackfillDialog()
    fun dismissBackfillDialog()
    fun backfill(days: Int)
    fun requestAllPermissions()
    fun requestPermission(permission: String)
}

class HealthConnectViewModel(
    private val settings: AppSettings,
    private val ops: HealthOps
) : ViewModel(), HealthActions {

    private val _state = MutableStateFlow(
        settings.loadHealth().let { saved ->
            HealthUiState(
                saved = saved,
                draft = saved,
                includeDailyTotals = settings.includeDailyTotals(),
                allowHttpWebhooks = settings.allowHttpWebhooks(),
                clientCertAlias = settings.clientCertAlias(),
                failureNotificationsEnabled = settings.failureNotificationsEnabled(),
                failureThreshold = settings.failureThreshold(),
                secretsUnavailable = settings.secretsUnavailable,
                mqttLastStatus = settings.lastMqttStatus(MqttSection.HEALTH)
            )
        }
    )
    val state: StateFlow<HealthUiState> = _state.asStateFlow()

    /** One-shot messages the screen shows as toasts. */
    private val _toasts = MutableSharedFlow<UiMessage>(extraBufferCapacity = 8)
    val toasts: SharedFlow<UiMessage> = _toasts.asSharedFlow()

    /** Permission sets the screen must hand to the Health Connect permission launcher. */
    private val _permissionRequests = MutableSharedFlow<Set<String>>(extraBufferCapacity = 4)
    val permissionRequests: SharedFlow<Set<String>> = _permissionRequests.asSharedFlow()

    private fun editDraft(transform: (HealthDraft) -> HealthDraft) = _state.update { it.copy(draft = transform(it.draft)) }
    private fun editWebhook(transform: (WebhookDraft) -> WebhookDraft) = editDraft { it.copy(webhook = transform(it.webhook)) }

    /** Re-reads availability and granted permissions; called on open and after a grant. */
    fun refreshPermissions() {
        viewModelScope.launch {
            val availability = ops.availability()
            if (availability != HcAvailability.AVAILABLE) {
                _state.update { it.copy(availability = availability, hasPermissions = false) }
                return@launch
            }
            val granted = ops.grantedPermissions()
            _state.update { it.copy(availability = availability, hasPermissions = granted.isNotEmpty(), grantedPermissions = granted) }

            // A fresh install with permissions already granted: pre-select the granted types.
            val current = _state.value
            if (current.draft.enabledTypes.isEmpty() && granted.isNotEmpty()) {
                val grantedTypes = HealthDataType.entries
                    .filter { HealthPermission.getReadPermission(it.recordClass) in granted }
                    .toSet()
                if (grantedTypes.isNotEmpty()) {
                    settings.setHealthEnabledTypes(grantedTypes)
                    _state.update {
                        it.copy(draft = it.draft.copy(enabledTypes = grantedTypes), saved = it.saved.copy(enabledTypes = grantedTypes))
                    }
                }
            }
        }
    }

    override fun setSyncInterval(text: String) = editDraft { it.copy(schedule = it.schedule.copy(intervalText = text)) }

    override fun setSchedule(schedule: ScheduleDraft) = editDraft { it.copy(schedule = schedule) }

    override fun setResolution(type: HealthDataType, resolution: SeriesResolution) = editDraft {
        it.copy(resolutions = it.resolutions + (type to resolution))
    }

    override fun addUrl(url: String) {
        if (!SettingsRules.isValidUrl(url)) {
            _toasts.tryEmit(UiMessage.InvalidUrl)
            return
        }
        editWebhook { it.copy(urls = it.urls + url.trim()) }
    }

    override fun removeUrl(index: Int) = editWebhook { it.copy(urls = it.urls.filterIndexed { i, _ -> i != index }) }

    override fun addHeader(key: String, value: String) {
        if (key.isBlank() || value.isBlank()) {
            _toasts.tryEmit(UiMessage.HeaderNeedsNameAndValue)
            return
        }
        editWebhook { it.copy(headers = it.headers + (key.trim() to value.trim())) }
    }

    override fun removeHeader(key: String) = editWebhook { it.copy(headers = it.headers - key) }
    override fun setSecret(secret: String) = editWebhook { it.copy(secret = secret) }

    override fun toggleType(type: HealthDataType, enabled: Boolean) {
        if (enabled && !_state.value.hasAnyPermission) {
            _state.update { it.copy(permissionPrompt = type) }
            return
        }
        editDraft { it.copy(enabledTypes = if (enabled) it.enabledTypes + type else it.enabledTypes - type) }
    }

    override fun dismissPermissionPrompt() = _state.update { it.copy(permissionPrompt = null) }
    override fun setMqtt(mqtt: MqttDraft) = editDraft { it.copy(mqtt = mqtt) }

    override fun setIncludeDailyTotals(enabled: Boolean) {
        settings.setIncludeDailyTotals(enabled)
        _state.update { it.copy(includeDailyTotals = enabled) }
    }

    override fun setAllowHttpWebhooks(enabled: Boolean) {
        settings.setAllowHttpWebhooks(enabled)
        _state.update { it.copy(allowHttpWebhooks = enabled) }
    }

    override fun setClientCertAlias(alias: String?) {
        settings.setClientCertAlias(alias)
        _state.update { it.copy(clientCertAlias = alias) }
    }

    override fun setFailureNotifications(enabled: Boolean) {
        settings.setFailureNotificationsEnabled(enabled)
        _state.update { it.copy(failureNotificationsEnabled = enabled) }
    }

    override fun setFailureThreshold(threshold: Int) {
        settings.setFailureThreshold(threshold)
        _state.update { it.copy(failureThreshold = threshold) }
    }

    /** Validates, persists and reschedules; returns the message shown either way. */
    private fun persist(): UiMessage {
        val draft = _state.value.draft
        SettingsRules.scheduleProblem(draft.schedule)?.let { return it }
        // In times mode the interval field is hidden and may hold half-typed text; keep what
        // was saved rather than silently resetting it to the default.
        val interval = SettingsRules.intervalOrNull(draft.schedule.intervalText)
            ?: SettingsRules.intervalOrNull(_state.value.saved.schedule.intervalText)
            ?: SyncSchedule.DEFAULT_INTERVAL_MINUTES
        if (!draft.hasDestination) return UiMessage.NoDestination
        settings.saveHealth(draft, interval)
        val saved = draft.copy(
            schedule = draft.schedule.copy(intervalText = interval.toString()),
            mqtt = draft.mqtt.withPort()
        )
        _state.update { it.copy(saved = saved, draft = saved) }
        return UiMessage.Saved
    }

    override fun reloadFromSettings() {
        val saved = settings.loadHealth()
        _state.update {
            it.copy(
                saved = saved,
                draft = saved,
                includeDailyTotals = settings.includeDailyTotals(),
                allowHttpWebhooks = settings.allowHttpWebhooks(),
                clientCertAlias = settings.clientCertAlias()
            )
        }
    }

    override fun save() {
        _toasts.tryEmit(persist())
    }

    override fun syncNow() {
        if (_state.value.isSyncing) return
        viewModelScope.launch {
            _state.update { it.copy(isSyncing = true, syncMessage = null) }
            try {
                if (ops.availability() != HcAvailability.AVAILABLE) {
                    _state.update { it.copy(syncMessage = UiMessage.HealthConnectUnavailable) }
                    return@launch
                }
                if (ops.grantedPermissions().isEmpty()) {
                    _permissionRequests.tryEmit(HealthConnectManager.ALL_PERMISSIONS)
                    return@launch
                }
                // Save first so the sync manager runs with what is on screen.
                val saved = persist()
                if (saved != UiMessage.Saved) {
                    _toasts.tryEmit(saved)
                    return@launch
                }
                val message = ops.sync().fold(
                    onSuccess = { result ->
                        when (result) {
                            is HealthSyncResult.NoData -> UiMessage.NoNewData
                            is HealthSyncResult.Success -> UiMessage.SyncedRecords(result.syncCounts.values.sum())
                            is HealthSyncResult.Queued -> UiMessage.QueuedRecords(result.recordCount)
                        }
                    },
                    onFailure = { UiMessage.SyncFailed(it.message ?: "") }
                )
                _state.update { it.copy(syncMessage = message) }
            } catch (e: Exception) {
                _state.update { it.copy(syncMessage = UiMessage.SyncFailed(e.message ?: "")) }
            } finally {
                _state.update { it.copy(isSyncing = false) }
            }
        }
    }

    override fun preview() {
        if (_state.value.isPreviewing) return
        viewModelScope.launch {
            _state.update { it.copy(isPreviewing = true) }
            try {
                settings.setHealthEnabledTypes(_state.value.draft.enabledTypes)
                ops.preview().fold(
                    onSuccess = { data -> _state.update { it.copy(previewData = data) } },
                    onFailure = { _toasts.tryEmit(UiMessage.PreviewFailed(it.message)) }
                )
            } catch (e: Exception) {
                _toasts.tryEmit(UiMessage.PreviewFailed(e.message))
            } finally {
                _state.update { it.copy(isPreviewing = false) }
            }
        }
    }

    override fun dismissPreview() = _state.update { it.copy(previewData = null) }

    override fun testPing() {
        if (_state.value.isPinging) return
        viewModelScope.launch {
            _state.update { it.copy(isPinging = true) }
            val result = ops.testPing(_state.value.draft.webhook)
            _state.update { it.copy(isPinging = false) }
            _toasts.tryEmit(
                result.fold(
                    onSuccess = { UiMessage.PingDelivered },
                    onFailure = { UiMessage.PingFailedWith(it.message ?: "") }
                )
            )
        }
    }

    override fun export() {
        if (_state.value.isExporting) return
        viewModelScope.launch {
            _state.update { it.copy(isExporting = true) }
            try {
                settings.setHealthEnabledTypes(_state.value.draft.enabledTypes)
                ops.preview().fold(
                    onSuccess = { data -> _state.update { it.copy(exportJson = data) } },
                    onFailure = { _toasts.tryEmit(UiMessage.ExportFailed(it.message)) }
                )
            } catch (e: Exception) {
                _toasts.tryEmit(UiMessage.ExportFailed(e.message))
            } finally {
                _state.update { it.copy(isExporting = false) }
            }
        }
    }

    override fun dismissExport() = _state.update { it.copy(exportJson = null) }

    /** Backfill posts history to webhooks; MQTT only carries the latest value, so say so instead of failing. */
    override fun openBackfillDialog() {
        if (_state.value.draft.webhook.urls.isEmpty()) {
            _toasts.tryEmit(UiMessage.BackfillNeedsWebhook)
            return
        }
        _state.update { it.copy(backfillDialog = true) }
    }

    override fun dismissBackfillDialog() = _state.update { it.copy(backfillDialog = false) }

    override fun backfill(days: Int) {
        _state.update { it.copy(backfillDialog = false) }
        if (_state.value.backfillProgress != null) return
        viewModelScope.launch {
            _state.update { it.copy(backfillProgress = 0 to 1) }
            val result = ops.backfill(days) { done, total -> _state.update { it.copy(backfillProgress = done to total) } }
            _state.update {
                it.copy(
                    backfillProgress = null,
                    syncMessage = result.fold(
                        onSuccess = { count -> UiMessage.BackfillComplete(count) },
                        onFailure = { e -> UiMessage.SyncFailed(e.message ?: "") }
                    )
                )
            }
        }
    }

    override fun requestAllPermissions() {
        _permissionRequests.tryEmit(HealthConnectManager.ALL_PERMISSIONS)
    }

    override fun requestPermission(permission: String) {
        _permissionRequests.tryEmit(setOf(permission))
    }

    companion object {
        fun factory(context: Context): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = context.applicationContext
                HealthConnectViewModel(PreferencesAppSettings(app, app.appPreferences()), RealHealthOps(app))
            }
        }
    }
}
