package com.owen282000.lifedashboard.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.owen282000.lifedashboard.MqttSection
import com.owen282000.lifedashboard.ScreenTimeSyncResult
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

data class ScreenTimeUiState(
    val saved: ScreenTimeDraft,
    val draft: ScreenTimeDraft,
    val hasUsageAccess: Boolean = false,
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
    val syncMessage: UiMessage? = null,
    val previewData: String? = null,
    val exportJson: String? = null,
    /** Bumped after a manual sync so the dashboard card reloads. */
    val refreshKey: Int = 0
) {
    val hasChanges: Boolean get() = draft.differsFrom(saved)
    val canSync: Boolean get() = !isSyncing && draft.hasDestination && hasUsageAccess
}

interface ScreenTimeActions {
    fun setSyncInterval(text: String)
    fun setSchedule(schedule: ScheduleDraft)
    fun addUrl(url: String)
    fun removeUrl(index: Int)
    fun addHeader(key: String, value: String)
    fun removeHeader(key: String)
    fun setSecret(secret: String)
    fun setDayBoundaryHour(text: String)
    fun setUseDayBoundary(enabled: Boolean)
    fun setMqtt(mqtt: MqttDraft)
    fun setAllowHttpWebhooks(enabled: Boolean)
    fun setClientCertAlias(alias: String?)
    fun setFailureNotifications(enabled: Boolean)
    fun setFailureThreshold(threshold: Int)
    fun save()

    /** See HealthActions.reloadFromSettings: for changes made outside this screen. */
    fun reloadFromSettings()
    fun syncNow()
    fun preview()
    fun dismissPreview()
    fun testPing()
    fun export()
    fun dismissExport()
}

class ScreenTimeViewModel(
    private val settings: AppSettings,
    private val ops: ScreenTimeOps
) : ViewModel(), ScreenTimeActions {

    private val _state = MutableStateFlow(
        settings.loadScreenTime().let { saved ->
            ScreenTimeUiState(
                saved = saved,
                draft = saved,
                hasUsageAccess = ops.hasUsageAccess(),
                allowHttpWebhooks = settings.allowHttpWebhooks(),
                clientCertAlias = settings.clientCertAlias(),
                failureNotificationsEnabled = settings.failureNotificationsEnabled(),
                failureThreshold = settings.failureThreshold(),
                secretsUnavailable = settings.secretsUnavailable,
                mqttLastStatus = settings.lastMqttStatus(MqttSection.SCREEN_TIME)
            )
        }
    )
    val state: StateFlow<ScreenTimeUiState> = _state.asStateFlow()

    private val _toasts = MutableSharedFlow<UiMessage>(extraBufferCapacity = 8)
    val toasts: SharedFlow<UiMessage> = _toasts.asSharedFlow()

    /** Fires when the user needs to visit Android's usage access settings. */
    private val _openUsageAccess = MutableSharedFlow<Unit>(extraBufferCapacity = 2)
    val openUsageAccess: SharedFlow<Unit> = _openUsageAccess.asSharedFlow()

    private fun editDraft(transform: (ScreenTimeDraft) -> ScreenTimeDraft) = _state.update { it.copy(draft = transform(it.draft)) }
    private fun editWebhook(transform: (WebhookDraft) -> WebhookDraft) = editDraft { it.copy(webhook = transform(it.webhook)) }

    /** Usage access is granted in system settings, so the screen polls this while visible. */
    fun refreshUsageAccess() {
        val granted = ops.hasUsageAccess()
        if (granted != _state.value.hasUsageAccess) _state.update { it.copy(hasUsageAccess = granted) }
    }

    /** See HealthConnectViewModel.refreshSharedSettings: the two settings both tabs show. */
    fun refreshSharedSettings() {
        _state.update {
            it.copy(allowHttpWebhooks = settings.allowHttpWebhooks(), clientCertAlias = settings.clientCertAlias())
        }
    }

    fun requestUsageAccess() {
        _openUsageAccess.tryEmit(Unit)
    }

    override fun setSyncInterval(text: String) = editDraft { it.copy(schedule = it.schedule.copy(intervalText = text)) }

    override fun setSchedule(schedule: ScheduleDraft) = editDraft { it.copy(schedule = schedule) }

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
    override fun setDayBoundaryHour(text: String) = editDraft { it.copy(dayBoundaryHour = text) }
    override fun setUseDayBoundary(enabled: Boolean) = editDraft { it.copy(useDayBoundary = enabled) }
    override fun setMqtt(mqtt: MqttDraft) = editDraft { it.copy(mqtt = mqtt) }

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

    private fun persist(): UiMessage {
        val draft = _state.value.draft
        SettingsRules.scheduleProblem(draft.schedule)?.let { return it }
        // In times mode the interval field is hidden and may hold half-typed text; keep what
        // was saved rather than silently resetting it to the default.
        val interval = SettingsRules.intervalOrNull(draft.schedule.intervalText)
            ?: SettingsRules.intervalOrNull(_state.value.saved.schedule.intervalText)
            ?: SyncSchedule.DEFAULT_INTERVAL_MINUTES
        if (!draft.hasDestination) return UiMessage.NoDestination
        val hour = SettingsRules.dayBoundaryHourOrNull(draft.dayBoundaryHour) ?: return UiMessage.InvalidDayBoundaryHour
        settings.saveScreenTime(draft, interval, hour)
        val saved = draft.copy(
            schedule = draft.schedule.copy(intervalText = interval.toString()),
            dayBoundaryHour = hour.toString(),
            mqtt = draft.mqtt.withPort()
        )
        _state.update { it.copy(saved = saved, draft = saved) }
        return UiMessage.Saved
    }

    override fun reloadFromSettings() {
        val saved = settings.loadScreenTime()
        _state.update {
            it.copy(
                saved = saved,
                draft = saved,
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
                if (!ops.hasUsageAccess()) {
                    _state.update { it.copy(syncMessage = UiMessage.UsageAccessMissing, hasUsageAccess = false) }
                    _openUsageAccess.tryEmit(Unit)
                    return@launch
                }
                val message = ops.sync().fold(
                    onSuccess = { result ->
                        when (result) {
                            is ScreenTimeSyncResult.NoData -> UiMessage.NoNewData
                            is ScreenTimeSyncResult.Success -> UiMessage.SyncedApps(result.appCount)
                            is ScreenTimeSyncResult.Queued -> UiMessage.QueuedApps(result.appCount)
                        }
                    },
                    onFailure = { UiMessage.SyncFailed(it.message ?: "") }
                )
                _state.update { it.copy(syncMessage = message, refreshKey = it.refreshKey + 1) }
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

    companion object {
        fun factory(context: Context): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = context.applicationContext
                val prefs = app.appPreferences()
                ScreenTimeViewModel(PreferencesAppSettings(app, prefs), RealScreenTimeOps(app, prefs))
            }
        }
    }
}
