package com.owen282000.lifedashboard.viewmodel

import android.content.Context
import androidx.health.connect.client.permission.HealthPermission
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.owen282000.lifedashboard.BackfillJob
import com.owen282000.lifedashboard.BackfillStatus
import com.owen282000.lifedashboard.HealthDataType
import com.owen282000.lifedashboard.HealthPermissionRequests
import com.owen282000.lifedashboard.HealthSyncResult
import com.owen282000.lifedashboard.MqttSection
import com.owen282000.lifedashboard.ReceiveSettings
import com.owen282000.lifedashboard.ReceiveStatus
import com.owen282000.lifedashboard.SeriesResolution
import com.owen282000.lifedashboard.SourceApps
import com.owen282000.lifedashboard.SourceUrlChoice
import com.owen282000.lifedashboard.SyncSchedule
import com.owen282000.lifedashboard.WriteBackPayload
import com.owen282000.lifedashboard.WriteBackType
import com.owen282000.lifedashboard.appPreferences
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

data class HealthUiState(
    val saved: HealthDraft,
    val draft: HealthDraft,
    val availability: HcAvailability = HcAvailability.AVAILABLE,
    /** null until the first permission check has run. */
    val hasPermissions: Boolean? = null,
    val grantedPermissions: Set<String> = emptySet(),
    /** Bumped after a manual sync so the dashboard card reloads, as on the Screen Time tab. */
    val refreshKey: Int = 0,
    val includeDailyTotals: Boolean = false,
    val allowHttpWebhooks: Boolean = false,
    /** KeyChain alias of the client certificate (mTLS) presented to webhooks, null for none. */
    val clientCertAlias: String? = null,
    /** The phone's name for MQTT, shared with the Screen Time tab; null when it has none. */
    val phoneName: String? = null,
    val failureNotificationsEnabled: Boolean = false,
    val failureThreshold: Int = 3,
    val secretsUnavailable: Boolean = false,
    val mqttLastStatus: String? = null,
    val isSyncing: Boolean = false,
    val isPreviewing: Boolean = false,
    val isPinging: Boolean = false,
    val isExporting: Boolean = false,
    /** Windows done and in total while a backfill job is queued or runs, also after the screen was left. */
    val backfillProgress: Pair<Int, Int>? = null,
    /** The backfill waits for a sync that is running; it starts by itself once that ends. */
    val backfillWaiting: Boolean = false,
    /** Android or the read quota stopped the backfill; WorkManager runs it again by itself. */
    val backfillPaused: Boolean = false,
    /** The line under the sync actions; stays until the next action replaces it. */
    val syncMessage: UiMessage? = null,
    val previewData: String? = null,
    val exportJson: String? = null,
    /** A data type whose toggle needs a permission grant first. */
    val permissionPrompt: HealthDataType? = null,
    val backfillDialog: Boolean = false,
    /** A stopped backfill that choosing its length in the dialog continues; read when the dialog opens. */
    val stoppedBackfill: BackfillJob? = null,
    /** Receive (issue #62): the stored switches, what the integration reported, and the two prompts. */
    val receive: ReceiveSettings = ReceiveSettings(),
    val receiveStatus: ReceiveStatus = ReceiveStatus(),
    /** A Receive type whose write permission has to be granted before its switch goes on. */
    val receivePermissionPrompt: WriteBackType? = null,
    /** More than one webhook looks like the integration's; the user picks the source. */
    val receiveSourceChoice: List<String>? = null
) {
    val hasChanges: Boolean get() = draft.differsFrom(saved)

    /**
     * Whether Receive can be switched on: the saved section has a signing secret and a URL
     * that looks like the integration's. Only then does the Receive row stop asking to pair.
     */
    val receiveAvailable: Boolean get() =
        saved.webhook.secret.isNotBlank() && WriteBackPayload.sourceUrlChoice(saved.webhook.urls) != SourceUrlChoice.None

    /**
     * A phone that only receives has nothing to read, but every sync is still the round trip that fetches measurements.
     * A running backfill holds the sync lock, so a sync started then would only wait for it; a
     * paused one holds nothing until WorkManager runs it again.
     */
    val canSync: Boolean get() =
        !isSyncing && (backfillProgress == null || backfillPaused) && draft.hasDestination &&
            (draft.enabledTypes.isNotEmpty() || receive.enabled)
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
    fun setPhoneName(name: String)
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

    /** Stops the running backfill; what was sent stays with the receiver. */
    fun cancelBackfill()

    /** The Grant button: the enabled types' reads and background reading. */
    fun requestAccess()

    /** History access for a backfill past 30 days; asked for from the backfill dialog only. */
    fun requestHistoryPermission()

    /** The read permission of the type in [HealthUiState.permissionPrompt]; the switch goes on once it is granted. */
    fun requestTypePermission()

    // Receive (issue #62). Applied at once, like the other settings that are not part of the draft.
    fun setReceiveEnabled(enabled: Boolean)
    fun toggleReceiveType(type: WriteBackType, enabled: Boolean)
    fun setReceiveOlderMeasurements(enabled: Boolean)
    fun chooseReceiveSource(url: String)
    fun dismissReceiveSourceChoice()
    fun requestReceivePermission()
    fun dismissReceivePermissionPrompt()
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
                phoneName = settings.phoneName(),
                failureNotificationsEnabled = settings.failureNotificationsEnabled(),
                failureThreshold = settings.failureThreshold(),
                secretsUnavailable = settings.secretsUnavailable,
                mqttLastStatus = settings.lastMqttStatus(MqttSection.HEALTH),
                receive = settings.receiveSettings(),
                receiveStatus = settings.receiveStatus()
            )
        }
    )
    val state: StateFlow<HealthUiState> = _state.asStateFlow()

    /**
     * The Receive type whose write permission was just asked for. The grant happens in Health
     * Connect's own UI, so the switch goes on in [refreshPermissions] once the permission is
     * there, and stays off when it is not: a refused permission means off.
     */
    private var pendingReceiveType: WriteBackType? = null

    /** The data type whose read permission was just asked for; like [pendingReceiveType], it goes on once granted. */
    private var pendingType: HealthDataType? = null

    /** One-shot messages the screen shows as toasts. */
    private val _toasts = MutableSharedFlow<UiMessage>(extraBufferCapacity = 8)
    val toasts: SharedFlow<UiMessage> = _toasts.asSharedFlow()

    /** Permission sets the screen must hand to the Health Connect permission launcher. */
    private val _permissionRequests = MutableSharedFlow<Set<String>>(extraBufferCapacity = 4)
    val permissionRequests: SharedFlow<Set<String>> = _permissionRequests.asSharedFlow()

    init {
        observeBackfill()
    }

    private fun editDraft(transform: (HealthDraft) -> HealthDraft) = _state.update { it.copy(draft = transform(it.draft)) }
    private fun editWebhook(transform: (WebhookDraft) -> WebhookDraft) = editDraft { it.copy(webhook = transform(it.webhook)) }

    /** Re-reads availability and granted permissions; called on open and after a grant. */
    /**
     * Re-read the settings this tab shares with Screen Time (plain HTTP, client
     * certificate), which that tab may have changed. The draft is left alone, so an
     * unsaved edit here survives a look at the other tab.
     */
    fun refreshSharedSettings() {
        _state.update {
            it.copy(
                allowHttpWebhooks = settings.allowHttpWebhooks(),
                clientCertAlias = settings.clientCertAlias(),
                phoneName = settings.phoneName()
            )
        }
    }

    fun refreshPermissions() {
        viewModelScope.launch {
            val availability = ops.availability()
            if (availability != HcAvailability.AVAILABLE) {
                _state.update { it.copy(availability = availability, hasPermissions = false) }
                return@launch
            }
            val granted = ops.grantedPermissions()
            _state.update { it.copy(availability = availability, hasPermissions = granted.isNotEmpty(), grantedPermissions = granted) }

            pendingReceiveType?.let { type ->
                pendingReceiveType = null
                if (type.writePermission in granted) enableReceiveType(type)
            }
            pendingType?.let { type ->
                pendingType = null
                if (readPermission(type) in granted) editDraft { it.copy(enabledTypes = it.enabledTypes + type) }
            }

            // A fresh install with permissions already granted: pre-select the granted types.
            val current = _state.value
            if (current.draft.enabledTypes.isEmpty() && granted.isNotEmpty()) {
                val grantedTypes = HealthDataType.entries
                    .filter { readPermission(it) in granted }
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
        editWebhook { it.withUrl(url.trim()) }
    }

    override fun removeUrl(index: Int) = editWebhook { it.withoutUrlAt(index) }

    override fun addHeader(key: String, value: String) {
        if (key.isBlank() || value.isBlank()) {
            _toasts.tryEmit(UiMessage.HeaderNeedsNameAndValue)
            return
        }
        editWebhook { it.copy(headers = it.headers + (key.trim() to value.trim())) }
    }

    override fun removeHeader(key: String) = editWebhook { it.copy(headers = it.headers - key) }
    override fun setSecret(secret: String) = editWebhook { it.copy(secret = secret) }

    /**
     * A type goes on only with its read permission granted. Grant asks for the enabled types
     * alone, so a type switched on later asks for its own permission first.
     */
    override fun toggleType(type: HealthDataType, enabled: Boolean) {
        if (enabled && readPermission(type) !in _state.value.grantedPermissions) {
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

    /** Applies at once, like the other shared settings under Advanced; the field shows what is typed, the store keeps it trimmed. */
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
                clientCertAlias = settings.clientCertAlias(),
                phoneName = settings.phoneName(),
                receive = settings.receiveSettings(),
                receiveStatus = settings.receiveStatus()
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
                    _permissionRequests.tryEmit(accessPermissions())
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
                            is HealthSyncResult.Success -> {
                                val count = result.syncCounts.values.sum()
                                UiMessage.partly(
                                    if (result.written > 0) UiMessage.SyncedRecordsWritten(count, result.written) else UiMessage.SyncedRecords(count),
                                    missed = result.missedUrls.size,
                                    total = result.webhookCount
                                )
                            }
                            is HealthSyncResult.Queued -> UiMessage.QueuedRecords(result.recordCount)
                        }
                    },
                    onFailure = { UiMessage.SyncFailed(it.message ?: "") }
                )
                // The sync may have learned which types the integration offers, or written some.
                _state.update {
                    it.copy(syncMessage = message, receiveStatus = settings.receiveStatus(), refreshKey = it.refreshKey + 1)
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
            _state.update { it.copy(isPreviewing = true) }
            try {
                settings.setHealthEnabledTypes(_state.value.draft.enabledTypes)
                ops.preview().fold(
                    onSuccess = { data -> _state.update { it.copy(previewData = data) } },
                    onFailure = { _toasts.tryEmit(UiMessage.PreviewFailed(it.message)) }
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
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

    /** Backfill posts history to webhooks; MQTT only carries the latest value, so say so instead of failing. */
    override fun openBackfillDialog() {
        if (_state.value.draft.webhook.urls.isEmpty()) {
            _toasts.tryEmit(UiMessage.BackfillNeedsWebhook)
            return
        }
        _state.update { it.copy(backfillDialog = true, stoppedBackfill = ops.stoppedBackfill()) }
    }

    override fun dismissBackfillDialog() = _state.update { it.copy(backfillDialog = false) }

    /**
     * The backfill is a WorkManager job (P2-14), so this only asks for it; [observeBackfill]
     * shows where it is. A start while one is queued or running does nothing.
     */
    override fun backfill(days: Int) {
        _state.update { it.copy(backfillDialog = false, syncMessage = null) }
        if (_state.value.backfillProgress != null) return
        viewModelScope.launch {
            try {
                ops.startBackfill(days)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(syncMessage = UiMessage.SyncFailed(e.message ?: "")) }
            }
        }
    }

    override fun cancelBackfill() = ops.cancelBackfill()

    /**
     * Follows the backfill job for as long as the view model lives, so leaving the screen and
     * coming back shows it again. The outcome is said once, when a run this view model saw
     * finishes; a job that finished before it looked says nothing, its row is in the Logs tab.
     */
    private fun observeBackfill() {
        viewModelScope.launch {
            var sawRunning = false
            ops.backfillStatus().collect { status ->
                when (status) {
                    is BackfillStatus.Running -> {
                        sawRunning = true
                        _state.update {
                            it.copy(
                                backfillProgress = status.done to status.total,
                                backfillWaiting = status.waiting,
                                backfillPaused = status.paused
                            )
                        }
                    }
                    is BackfillStatus.Finished -> {
                        val message = if (!sawRunning) null else if (status.error != null) {
                            UiMessage.SyncFailed(status.error)
                        } else {
                            UiMessage.BackfillComplete(status.records ?: 0)
                        }
                        sawRunning = false
                        _state.update {
                            it.copy(
                                backfillProgress = null,
                                backfillWaiting = false,
                                backfillPaused = false,
                                syncMessage = message ?: it.syncMessage,
                                refreshKey = if (message != null) it.refreshKey + 1 else it.refreshKey
                            )
                        }
                    }
                    BackfillStatus.Idle -> {
                        sawRunning = false
                        _state.update { it.copy(backfillProgress = null, backfillWaiting = false, backfillPaused = false) }
                    }
                }
            }
        }
    }

    private fun readPermission(type: HealthDataType) = HealthPermission.getReadPermission(type.recordClass)

    private fun accessPermissions(): Set<String> = HealthPermissionRequests.forGrant(_state.value.draft.enabledTypes)

    override fun requestAccess() {
        _permissionRequests.tryEmit(accessPermissions())
    }

    override fun requestHistoryPermission() {
        _permissionRequests.tryEmit(HealthPermissionRequests.forHistory(_state.value.draft.enabledTypes))
    }

    override fun requestTypePermission() {
        val type = _state.value.permissionPrompt ?: return
        pendingType = type
        _state.update { it.copy(permissionPrompt = null) }
        _permissionRequests.tryEmit(setOf(readPermission(type)))
    }

    // ==================== Receive (issue #62) ====================

    /**
     * Switching Receive on needs the integration: a webhook URL that looks like a Home
     * Assistant one, and a signing secret to verify its answers with. The saved section is
     * what counts, since that is what the sync runs with. One candidate is taken; several
     * are offered as a choice; none, or no secret, is explained and the switch stays off.
     */
    override fun setReceiveEnabled(enabled: Boolean) {
        if (!enabled) {
            settings.setReceiveEnabled(false)
            _state.update { it.copy(receive = settings.receiveSettings()) }
            return
        }
        val saved = _state.value.saved.webhook
        if (saved.secret.isBlank()) {
            _toasts.tryEmit(UiMessage.ReceiveNeedsIntegration)
            return
        }
        when (val choice = WriteBackPayload.sourceUrlChoice(saved.urls)) {
            SourceUrlChoice.None -> _toasts.tryEmit(UiMessage.ReceiveNeedsIntegration)
            is SourceUrlChoice.One -> enableReceiveWith(choice.url)
            is SourceUrlChoice.Several -> _state.update { it.copy(receiveSourceChoice = choice.urls) }
        }
    }

    override fun chooseReceiveSource(url: String) {
        _state.update { it.copy(receiveSourceChoice = null) }
        enableReceiveWith(url)
    }

    override fun dismissReceiveSourceChoice() = _state.update { it.copy(receiveSourceChoice = null) }

    private fun enableReceiveWith(url: String) {
        settings.setReceiveSourceUrl(url)
        settings.setReceiveEnabled(true)
        _state.update { it.copy(receive = settings.receiveSettings()) }
    }

    /**
     * A type goes on only with its write permission in hand; without it the prompt asks for
     * that one permission and the switch waits for the answer. Switching off needs nothing.
     */
    override fun toggleReceiveType(type: WriteBackType, enabled: Boolean) {
        if (!enabled) {
            settings.setReceiveTypes(_state.value.receive.types - type)
            _state.update { it.copy(receive = settings.receiveSettings()) }
            return
        }
        if (type.writePermission !in _state.value.grantedPermissions) {
            _state.update { it.copy(receivePermissionPrompt = type) }
            return
        }
        enableReceiveType(type)
    }

    private fun enableReceiveType(type: WriteBackType) {
        settings.setReceiveTypes(_state.value.receive.types + type)
        _state.update { it.copy(receive = settings.receiveSettings()) }
        // Two readings a day is the usual surprise: the scale's own app already writes to
        // Health Connect, and now Home Assistant does too. Said once, when the switch goes on.
        viewModelScope.launch {
            // Bounded like every Health Connect call: a warning is never worth a hang.
            withTimeoutOrNull(OTHER_SOURCES_TIMEOUT_MS) { ops.otherSourcesWriting(type) }
                ?.firstOrNull()
                ?.let { _toasts.tryEmit(UiMessage.OtherSourceWrites(SourceApps.label(it), type)) }
        }
    }

    override fun setReceiveOlderMeasurements(enabled: Boolean) {
        settings.setReceiveOlderMeasurements(enabled)
        _state.update { it.copy(receive = settings.receiveSettings()) }
    }

    override fun requestReceivePermission() {
        val type = _state.value.receivePermissionPrompt ?: return
        pendingReceiveType = type
        _state.update { it.copy(receivePermissionPrompt = null) }
        _permissionRequests.tryEmit(setOf(type.writePermission))
    }

    override fun dismissReceivePermissionPrompt() = _state.update { it.copy(receivePermissionPrompt = null) }

    companion object {
        private const val OTHER_SOURCES_TIMEOUT_MS = 5_000L

        fun factory(context: Context): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = context.applicationContext
                HealthConnectViewModel(PreferencesAppSettings(app, app.appPreferences()), RealHealthOps(app))
            }
        }
    }
}
