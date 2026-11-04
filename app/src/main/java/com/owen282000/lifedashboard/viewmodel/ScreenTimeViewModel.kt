package com.owen282000.lifedashboard.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.owen282000.lifedashboard.AppChoice
import com.owen282000.lifedashboard.AppFilterMode
import com.owen282000.lifedashboard.FailureReason
import com.owen282000.lifedashboard.LogType
import com.owen282000.lifedashboard.MqttSection
import com.owen282000.lifedashboard.NoNewDataToPreview
import com.owen282000.lifedashboard.ScreenTimeAppFilter
import com.owen282000.lifedashboard.ScreenTimeSyncResult
import com.owen282000.lifedashboard.ServerTrust
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
    /** The settings as they are stored. What a page edits, it holds in a draft of its own until its Save. */
    val saved: ScreenTimeDraft,
    val hasUsageAccess: Boolean = false,
    val allowHttpWebhooks: Boolean = false,
    /** KeyChain alias of the client certificate (mTLS) presented to webhooks, null for none. */
    val clientCertAlias: String? = null,
    /** Trust per webhook host beyond the system CAs, keyed by `host:port`; shared by both tabs. */
    val serverTrust: Map<String, ServerTrust> = emptyMap(),
    /** The phone's name for MQTT, shared with the Health Connect tab; null when it has none. */
    val phoneName: String? = null,
    val failureNotificationsEnabled: Boolean = false,
    val failureThreshold: Int = 3,
    val secretsUnavailable: Boolean = false,
    val secretsNeedReentry: Boolean = false,
    val mqttLastStatus: String? = null,
    val isSyncing: Boolean = false,
    /** See HealthUiState.workerSyncing. */
    val workerSyncing: Boolean = false,
    val isPreviewing: Boolean = false,
    val isPinging: Boolean = false,
    val isExporting: Boolean = false,
    val syncMessage: UiMessage? = null,
    /** See HealthUiState.refused. */
    val refused: UiMessage? = null,
    val previewData: String? = null,
    /** Why there is no [previewData], for the page to say and go on saying; null while none was asked for or it came. */
    val previewProblem: UiMessage? = null,
    val exportJson: String? = null,
    /** Bumped after a sync, here or in the worker, so the dashboard card reloads. */
    val refreshKey: Int = 0,
    /** What the app filter's picker offers; null until it has been loaded. */
    val appChoices: List<AppChoice>? = null
) {
    val canSync: Boolean get() = !isSyncBusy && saved.hasDestination && hasUsageAccess

    /** See HealthUiState.isSyncBusy. */
    val isSyncBusy: Boolean get() = isSyncing || workerSyncing
}

interface ScreenTimeActions : SectionActions {
    /** The hour the screen holds in its own draft. */
    fun saveDayBoundaryHour(text: String): UiMessage?

    /** Stored at once. */
    fun setUseDayBoundary(enabled: Boolean)

    fun setAllowHttpWebhooks(enabled: Boolean)
    fun setClientCertAlias(alias: String?)

    /** Sets what [hostKey] is trusted with; the default removes the entry. */
    fun setServerTrust(hostKey: String, trust: ServerTrust)
    fun setPhoneName(name: String)
    fun setFailureNotifications(enabled: Boolean)
    fun setFailureThreshold(threshold: Int)

    /** Stored at once. A change rewrites the last seven days at the receiver: the screen says so. */
    fun setAppFilterMode(mode: AppFilterMode)

    /** Stored at once, see [setAppFilterMode]. */
    fun toggleFilteredApp(packageName: String)

    /** Loads what the app filter's picker offers, once; the list is built from usage statistics. */
    fun loadAppChoices()

    /** See HealthActions.dismissSecretsLost. */
    fun dismissSecretsLost()

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
                hasUsageAccess = ops.hasUsageAccess(),
                allowHttpWebhooks = settings.allowHttpWebhooks(),
                clientCertAlias = settings.clientCertAlias(),
                serverTrust = settings.serverTrust(),
                phoneName = settings.phoneName(),
                failureNotificationsEnabled = settings.failureNotificationsEnabled(),
                failureThreshold = settings.failureThreshold(),
                secretsUnavailable = settings.secretsUnavailable,
                secretsNeedReentry = settings.secretsNeedReentry,
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

    /** What runs again on Try again after a refusal, see [ScreenTimeUiState.refused]. */
    private var refusedAction: (() -> Unit)? = null

    init {
        observeWorker()
    }

    /** See HealthConnectViewModel.observeWorker. */
    private fun observeWorker() {
        viewModelScope.launch {
            ops.dataChanges().collect {
                _state.update { it.copy(mqttLastStatus = settings.lastMqttStatus(MqttSection.SCREEN_TIME), refreshKey = it.refreshKey + 1) }
            }
        }
        viewModelScope.launch {
            ops.workerRunning().collect { running ->
                _state.update {
                    it.copy(workerSyncing = running, syncMessage = if (it.workerSyncing && !running && !it.isSyncing) null else it.syncMessage)
                }
            }
        }
    }

    /** What was just stored. */
    private fun stored(transform: (ScreenTimeDraft) -> ScreenTimeDraft) = _state.update { it.copy(saved = transform(it.saved)) }

    private fun commitAppFilter(filter: ScreenTimeAppFilter) {
        settings.setScreenTimeAppFilter(filter)
        stored { it.copy(appFilter = filter) }
    }

    override fun setAppFilterMode(mode: AppFilterMode) = commitAppFilter(_state.value.saved.appFilter.copy(mode = mode))

    override fun toggleFilteredApp(packageName: String) {
        val filter = _state.value.saved.appFilter
        val packages = filter.packages
        commitAppFilter(filter.copy(packages = if (packageName in packages) packages - packageName else packages + packageName))
    }

    override fun loadAppChoices() {
        if (_state.value.appChoices != null) return
        viewModelScope.launch {
            val choices = ops.appChoices(_state.value.saved.appFilter.packages)
            _state.update { it.copy(appChoices = choices) }
        }
    }

    /** Usage access is granted in system settings, so the screen polls this while visible. */
    fun refreshUsageAccess() {
        val granted = ops.hasUsageAccess()
        if (granted != _state.value.hasUsageAccess) _state.update { it.copy(hasUsageAccess = granted) }
    }

    override fun dismissSecretsLost() {
        settings.dismissSecretsReentry()
        _state.update { it.copy(secretsNeedReentry = settings.secretsNeedReentry) }
    }

    /** See HealthConnectViewModel.refreshSharedSettings: the settings both tabs show. */
    fun refreshSharedSettings() {
        val broker = settings.sharedBroker()
        _state.update {
            it.copy(
                saved = it.saved.copy(mqtt = it.saved.mqtt.withStoredSharedBroker(broker)),
                allowHttpWebhooks = settings.allowHttpWebhooks(),
                clientCertAlias = settings.clientCertAlias(),
                serverTrust = settings.serverTrust(),
                phoneName = settings.phoneName(),
                failureNotificationsEnabled = settings.failureNotificationsEnabled(),
                failureThreshold = settings.failureThreshold(),
                // A secret saved on the other tab may have ended the request to enter them again.
                secretsUnavailable = settings.secretsUnavailable,
                secretsNeedReentry = settings.secretsNeedReentry
            )
        }
    }

    fun requestUsageAccess() {
        _openUsageAccess.tryEmit(Unit)
    }

    override fun setSchedule(schedule: ScheduleDraft) {
        val saved = _state.value.saved.schedule
        val interval = SettingsRules.intervalOrNull(schedule.intervalText)
            ?: SettingsRules.intervalOrNull(saved.intervalText)
            ?: SyncSchedule.DEFAULT_INTERVAL_MINUTES
        val next = schedule.toSchedule(fallbackInterval = interval)
        if (!saved.toSchedule(fallbackInterval = interval).differsFrom(next)) return
        settings.setSchedule(LogType.SCREEN_TIME, next)
        settings.reschedule(LogType.SCREEN_TIME)
        stored { it.copy(schedule = ScheduleDraft.from(next)) }
    }

    override fun saveInterval(text: String): UiMessage? {
        SettingsRules.intervalOrNull(text) ?: return UiMessage.IntervalTooShort
        setSchedule(_state.value.saved.schedule.copy(intervalText = text))
        return null
    }

    // ==================== What the destination screens save ====================
    // The same as on the Health tab, see there.

    override fun destinationDraft(id: String?, address: String?): DestinationDraft {
        val webhook = settings.loadScreenTime().webhook
        return if (id != null && webhook.destinations.any { it.id == id }) {
            DestinationDraft.existing(webhook, id)
        } else {
            DestinationDraft.new(webhook, address.orEmpty())
        }
    }

    override fun saveDestination(draft: DestinationDraft): UiMessage? {
        val stored = settings.loadScreenTime().webhook
        val next = stored.withDestination(draft.destination).trimmed()
        SettingsRules.destinationProblem(next, stored.urls)?.let { return it }
        if (!settings.setDestinations(LogType.SCREEN_TIME, next.destinations)) return UiMessage.SecretsNotReady
        destinationsStored()
        return null
    }

    private fun destinationsStored() {
        val webhook = settings.loadScreenTime().webhook
        refusedAction = null
        stored { it.copy(webhook = webhook) }
        _state.update { it.copy(refused = null, secretsNeedReentry = settings.secretsNeedReentry) }
    }

    private fun refuse(action: () -> Unit) {
        refusedAction = action
        _state.update { it.copy(refused = UiMessage.SecretsNotReady) }
    }

    override fun retryRefused() {
        val action = refusedAction ?: return
        dismissRefused()
        action()
    }

    override fun dismissRefused() {
        refusedAction = null
        _state.update { it.copy(refused = null) }
    }

    override fun testDestination(id: String) {
        val destination = _state.value.saved.webhook.destinations.firstOrNull { it.id == id } ?: return
        ping(WebhookDraft(listOf(destination)))
    }

    override fun mqttDraft(): MqttDraft = settings.loadScreenTime().mqtt

    override fun saveMqtt(mqtt: MqttDraft): UiMessage? {
        SettingsRules.mqttFieldProblem(mqtt)?.let { return it }
        val next = mqtt.withPort()
        val editedShared = next.section.useSharedBroker && next.sharedBroker != _state.value.saved.mqtt.withPort().sharedBroker
        if (!settings.setMqtt(MqttSection.SCREEN_TIME, next.section, next.sharedBroker.takeIf { editedShared })) return UiMessage.SecretsNotReady
        mqttStored()
        return null
    }

    override fun removeMqtt() {
        val stored = settings.loadScreenTime().mqtt
        val shared = stored.section.useSharedBroker
        val otherPublishes = settings.loadHealth().mqtt.section.let { it.enabled && it.useSharedBroker }
        val section = stored.section.copy(
            enabled = false,
            ownBroker = if (shared) stored.section.ownBroker else stored.section.ownBroker.cleared()
        )
        if (!settings.setMqtt(MqttSection.SCREEN_TIME, section, stored.sharedBroker.cleared().takeIf { shared && !otherPublishes })) {
            refuse(::removeMqtt)
            return
        }
        settings.clearMqttStatus(MqttSection.SCREEN_TIME)
        mqttStored()
    }

    private fun mqttStored() {
        val mqtt = settings.loadScreenTime().mqtt
        refusedAction = null
        stored { it.copy(mqtt = mqtt) }
        _state.update { it.copy(refused = null, mqttLastStatus = settings.lastMqttStatus(MqttSection.SCREEN_TIME), secretsNeedReentry = settings.secretsNeedReentry) }
    }

    override fun removeDestination(id: String) {
        val stored = settings.loadScreenTime().webhook
        if (stored.destinations.none { it.id == id }) return
        if (settings.setDestinations(LogType.SCREEN_TIME, stored.without(id).destinations)) {
            destinationsStored()
        } else {
            refuse { removeDestination(id) }
        }
    }

    override fun saveDayBoundaryHour(text: String): UiMessage? {
        val hour = SettingsRules.dayBoundaryHourOrNull(text) ?: return UiMessage.InvalidDayBoundaryHour
        settings.setDayBoundaryHour(hour)
        stored { it.copy(dayBoundaryHour = hour.toString()) }
        return null
    }

    override fun setUseDayBoundary(enabled: Boolean) {
        settings.setUseDayBoundary(enabled)
        stored { it.copy(useDayBoundary = enabled) }
    }

    override fun setAllowHttpWebhooks(enabled: Boolean) {
        settings.setAllowHttpWebhooks(enabled)
        _state.update { it.copy(allowHttpWebhooks = enabled) }
    }

    override fun setClientCertAlias(alias: String?) {
        settings.setClientCertAlias(alias)
        _state.update { it.copy(clientCertAlias = alias) }
    }

    override fun setServerTrust(hostKey: String, trust: ServerTrust) {
        val updated = (settings.serverTrust() + (hostKey to trust)).filterValues { !it.isDefault }
        settings.setServerTrust(updated)
        _state.update { it.copy(serverTrust = updated) }
    }

    override fun setPhoneName(name: String) {
        settings.setPhoneName(name)
        _state.update { it.copy(phoneName = name) }
    }

    override fun setFailureNotifications(enabled: Boolean) {
        settings.setFailureNotificationsEnabled(enabled)
        _state.update { it.copy(failureNotificationsEnabled = enabled) }
    }

    override fun setFailureThreshold(threshold: Int) {
        settings.setFailureThreshold(threshold)
        _state.update { it.copy(failureThreshold = threshold) }
    }

    override fun reloadFromSettings() {
        val saved = settings.loadScreenTime()
        _state.update {
            it.copy(
                saved = saved,
                allowHttpWebhooks = settings.allowHttpWebhooks(),
                clientCertAlias = settings.clientCertAlias(),
                serverTrust = settings.serverTrust(),
                phoneName = settings.phoneName(),
                failureNotificationsEnabled = settings.failureNotificationsEnabled(),
                failureThreshold = settings.failureThreshold(),
                mqttLastStatus = settings.lastMqttStatus(MqttSection.SCREEN_TIME)
            )
        }
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
                // Everything a screen applies is stored by now, and a sync reads what is stored.
                val message = ops.sync().fold(
                    onSuccess = { result ->
                        when (result) {
                            is ScreenTimeSyncResult.NoData -> UiMessage.NoNewData
                            is ScreenTimeSyncResult.Success -> UiMessage.partly(
                                UiMessage.SyncedApps(result.appCount),
                                missed = result.missedUrls.size,
                                total = result.webhookCount
                            )
                            is ScreenTimeSyncResult.Queued -> UiMessage.QueuedApps(result.appCount)
                        }
                    },
                    onFailure = { UiMessage.headerProblem(it) ?: UiMessage.SyncFailed(it.message ?: "") }
                )
                _state.update {
                    // The broker may have failed while the webhook took the payload.
                    it.copy(syncMessage = message, mqttLastStatus = settings.lastMqttStatus(MqttSection.SCREEN_TIME), refreshKey = it.refreshKey + 1)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
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
            _state.update { it.copy(isPreviewing = true, previewProblem = null) }
            try {
                ops.preview().fold(
                    onSuccess = { data -> _state.update { it.copy(previewData = data) } },
                    onFailure = { error ->
                        val problem = if (error is NoNewDataToPreview) UiMessage.NoNewData else UiMessage.PreviewFailed(error.message)
                        _state.update { it.copy(previewProblem = problem) }
                    }
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(previewProblem = UiMessage.PreviewFailed(e.message)) }
            } finally {
                _state.update { it.copy(isPreviewing = false) }
            }
        }
    }

    override fun dismissPreview() = _state.update { it.copy(previewData = null, previewProblem = null) }

    override fun testPing() = ping(_state.value.saved.webhook)

    private fun ping(webhook: WebhookDraft) {
        if (_state.value.isPinging) return
        viewModelScope.launch {
            _state.update { it.copy(isPinging = true) }
            val result = ops.testPing(webhook)
            _state.update { it.copy(isPinging = false) }
            _toasts.tryEmit(
                result.fold(
                    onSuccess = { UiMessage.PingDelivered },
                    onFailure = { UiMessage.headerProblem(it) ?: UiMessage.PingFailedWith(FailureReason.of(it).orEmpty()) }
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
                    onFailure = { _toasts.tryEmit(if (it is NoNewDataToPreview) UiMessage.NoNewData else UiMessage.ExportFailed(it.message)) }
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
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
