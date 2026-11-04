package com.owen282000.lifedashboard.viewmodel

import android.content.Context
import androidx.health.connect.client.permission.HealthPermission
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.owen282000.lifedashboard.BackfillJob
import com.owen282000.lifedashboard.BackfillStart
import com.owen282000.lifedashboard.BackfillStatus
import com.owen282000.lifedashboard.FailureReason
import com.owen282000.lifedashboard.HealthConnectManager
import com.owen282000.lifedashboard.HealthDataType
import com.owen282000.lifedashboard.HealthPermissionRequests
import com.owen282000.lifedashboard.HealthSyncResult
import com.owen282000.lifedashboard.LogType
import com.owen282000.lifedashboard.MqttSection
import com.owen282000.lifedashboard.NoNewDataToPreview
import com.owen282000.lifedashboard.ReceiveSettings
import com.owen282000.lifedashboard.ReceiveStatus
import com.owen282000.lifedashboard.SeriesResolution
import com.owen282000.lifedashboard.ServerTrust
import com.owen282000.lifedashboard.SourceApps
import com.owen282000.lifedashboard.SourceUrlChoice
import com.owen282000.lifedashboard.SyncSchedule
import com.owen282000.lifedashboard.TodayKind
import com.owen282000.lifedashboard.TodayStat
import com.owen282000.lifedashboard.TodayState
import com.owen282000.lifedashboard.WriteBackPayload
import com.owen282000.lifedashboard.WriteBackType
import com.owen282000.lifedashboard.appPreferences
import kotlinx.coroutines.Job
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
    /** The settings as they are stored. What a page edits, it holds in a draft of its own until its Save. */
    val saved: HealthDraft,
    val availability: HcAvailability = HcAvailability.AVAILABLE,
    /** Whether any data type can be read; null until the first permission check has run. Background or write access alone does not count. */
    val hasPermissions: Boolean? = null,
    val grantedPermissions: Set<String> = emptySet(),
    /** Bumped after a sync, here or in the worker, so the dashboard card reloads, as on the Screen Time tab. */
    val refreshKey: Int = 0,
    val includeDailyTotals: Boolean = false,
    /** Record metadata on every record (P2-7); applied at once, like daily totals. */
    val includeRecordMetadata: Boolean = false,
    /** Whole payloads in the logs instead of the first part; applied at once. */
    val keepFullPayloads: Boolean = false,
    val allowHttpWebhooks: Boolean = false,
    /** KeyChain alias of the client certificate (mTLS) presented to webhooks, null for none. */
    val clientCertAlias: String? = null,
    /** Trust per webhook host beyond the system CAs, keyed by `host:port`; shared by both tabs. */
    val serverTrust: Map<String, ServerTrust> = emptyMap(),
    /** The phone's name for MQTT, shared with the Screen Time tab; null when it has none. */
    val phoneName: String? = null,
    val failureNotificationsEnabled: Boolean = false,
    val failureThreshold: Int = 3,
    val secretsUnavailable: Boolean = false,
    val secretsNeedReentry: Boolean = false,
    val mqttLastStatus: String? = null,
    val isSyncing: Boolean = false,
    /** A sync in the worker, not asked for on this screen: a schedule, the widget, the tile or a broadcast. */
    val workerSyncing: Boolean = false,
    val isPreviewing: Boolean = false,
    val isPinging: Boolean = false,
    val isExporting: Boolean = false,
    /** Windows done and in total while a backfill job is queued or runs, also after the screen was left. */
    val backfillProgress: Pair<Int, Int>? = null,
    /** The backfill waits for a sync that is running; it starts by itself once that ends. */
    val backfillWaiting: Boolean = false,
    /** Android or the read quota stopped the backfill; WorkManager runs it again by itself. */
    val backfillPaused: Boolean = false,
    /** The enabled data types changed since the backfill started, so it began again at the first window. */
    val backfillRestarted: Boolean = false,
    /** The line under the sync actions; stays until the next action replaces it. */
    val syncMessage: UiMessage? = null,
    /**
     * [UiMessage.SecretsNotReady] after a removal the secure storage refused: nothing changed, and
     * a screen shows it with Try again ([SectionActions.retryRefused]). A screen's own Save gets
     * the refusal as its return value instead, because going back there has to discard the draft.
     */
    val refused: UiMessage? = null,
    /** The figures of the Today panel, read by [HealthConnectViewModel.refreshToday]. */
    val today: TodayState = TodayState(),
    val previewData: String? = null,
    /** Why there is no [previewData], for the page to say and go on saying; null while none was asked for or it came. */
    val previewProblem: UiMessage? = null,
    val exportJson: String? = null,
    /** A data type whose toggle needs a permission grant first. */
    val permissionPrompt: HealthDataType? = null,
    /** Why the last request for a permission did not go out: Health Connect is not there to ask. What is selected stays. */
    val accessBlocked: HcAvailability? = null,
    val backfillDialog: Boolean = false,
    /** A stopped backfill that choosing its length in the dialog continues; read when the dialog opens. */
    val stoppedBackfill: BackfillJob? = null,
    /**
     * The oldest record of the enabled types in epoch milliseconds, looked up when the dialog
     * opens with history access, so the dialog leaves out the lengths that reach back past it.
     * Null before that, without history access (Health Connect then hides older records), and
     * when there is no record or it could not be read.
     */
    val backfillEarliest: Long? = null,
    /** Receive (issue #62): the stored switches, what the integration reported, and the two prompts. */
    val receive: ReceiveSettings = ReceiveSettings(),
    val receiveStatus: ReceiveStatus = ReceiveStatus(),
    /** A Receive type whose write permission has to be granted before its switch goes on. */
    val receivePermissionPrompt: WriteBackType? = null,
    /** More than one webhook looks like the integration's; the user picks the source. */
    val receiveSourceChoice: List<String>? = null
) {
    /**
     * Whether Receive can be switched on: the saved section has a destination that looks like
     * the integration's and has a signing secret. Only then does the Receive row stop asking to pair.
     */
    val receiveAvailable: Boolean get() =
        WriteBackPayload.sourceUrlChoice(saved.webhook.urlsWithSecret) != SourceUrlChoice.None

    /** Sync Now says Syncing for either kind: one started now would only wait for the other. */
    val isSyncBusy: Boolean get() = isSyncing || workerSyncing

    /**
     * A phone that only receives has nothing to read, but every sync is still the round trip that fetches measurements.
     * A running backfill holds the sync lock, so a sync started then would only wait for it; a
     * paused one holds nothing until WorkManager runs it again.
     */
    val canSync: Boolean get() =
        !isSyncBusy && (backfillProgress == null || backfillPaused) && saved.hasDestination &&
            (saved.enabledTypes.isNotEmpty() || receive.enabled)
}

/** Everything the Health Connect screen can ask for; the view model implements it, previews can fake it. */
interface HealthActions : SectionActions {
    /** Stored at once. */
    fun setResolution(type: HealthDataType, resolution: SeriesResolution)

    /** Stored at once when it is off or the permission is there; otherwise asks, see [HealthUiState.permissionPrompt]. */
    fun toggleType(type: HealthDataType, enabled: Boolean)

    /**
     * The shortcuts Essentials, All types and None: sets the switches to [types] at once. The
     * ones without their permission are asked for together and go on once granted.
     */
    fun applyTypes(types: Set<HealthDataType>)
    fun dismissPermissionPrompt()

    fun setIncludeDailyTotals(enabled: Boolean)
    fun setIncludeRecordMetadata(enabled: Boolean)
    fun setKeepFullPayloads(enabled: Boolean)
    fun setAllowHttpWebhooks(enabled: Boolean)
    fun setClientCertAlias(alias: String?)

    /** Sets what [hostKey] is trusted with; the default removes the entry. */
    fun setServerTrust(hostKey: String, trust: ServerTrust)
    fun setPhoneName(name: String)
    fun setFailureNotifications(enabled: Boolean)
    fun setFailureThreshold(threshold: Int)

    /** The banner about lost secrets was dismissed. */
    fun dismissSecretsLost()

    /**
     * Re-read the settings from storage, for changes made outside this view model, such as QR
     * pairing writing the webhook address and secret for both sections at once.
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

    // Receive (issue #62). Applied at once.
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
                includeDailyTotals = settings.includeDailyTotals(),
                includeRecordMetadata = settings.includeRecordMetadata(),
                keepFullPayloads = settings.keepFullPayloads(),
                allowHttpWebhooks = settings.allowHttpWebhooks(),
                clientCertAlias = settings.clientCertAlias(),
                serverTrust = settings.serverTrust(),
                phoneName = settings.phoneName(),
                failureNotificationsEnabled = settings.failureNotificationsEnabled(),
                failureThreshold = settings.failureThreshold(),
                secretsUnavailable = settings.secretsUnavailable,
                secretsNeedReentry = settings.secretsNeedReentry,
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

    /** The data types whose read permission was just asked for; like [pendingReceiveType], they go on once granted. */
    private var pendingTypes: Set<HealthDataType> = emptySet()

    /** What runs again on Try again after a refusal, see [HealthUiState.refused]. */
    private var refusedAction: (() -> Unit)? = null

    private var todayRead: Job? = null

    /** One-shot messages the screen shows as toasts. */
    private val _toasts = MutableSharedFlow<UiMessage>(extraBufferCapacity = 8)
    val toasts: SharedFlow<UiMessage> = _toasts.asSharedFlow()

    /** Permission sets the screen must hand to the Health Connect permission launcher. */
    private val _permissionRequests = MutableSharedFlow<Set<String>>(extraBufferCapacity = 4)
    val permissionRequests: SharedFlow<Set<String>> = _permissionRequests.asSharedFlow()

    /** Set by Stop until the stopped work is gone, see [cancelBackfill]. */
    private var stopping = false

    init {
        observeBackfill()
        observeWorker()
    }

    /**
     * A sync in the worker reports to the store and not to this view model, so what it leaves
     * behind is read here. Its own result is on the rows and the heading, and it makes the line
     * under Sync Now out of date: a failure there would go on saying so next to a delivery.
     */
    private fun observeWorker() {
        viewModelScope.launch {
            ops.dataChanges().collect {
                _state.update {
                    it.copy(
                        receive = settings.receiveSettings(),
                        receiveStatus = settings.receiveStatus(),
                        mqttLastStatus = settings.lastMqttStatus(MqttSection.HEALTH),
                        refreshKey = it.refreshKey + 1
                    )
                }
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
    private fun stored(transform: (HealthDraft) -> HealthDraft) = _state.update { it.copy(saved = transform(it.saved)) }

    override fun dismissSecretsLost() {
        settings.dismissSecretsReentry()
        _state.update { it.copy(secretsNeedReentry = settings.secretsNeedReentry) }
    }

    /**
     * Re-read the settings this tab shares with Screen Time (plain HTTP, client certificate,
     * the shared broker and so on), which that tab may have changed.
     */
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

    /** Re-reads availability and granted permissions; called on open and after a grant. */
    fun refreshPermissions() {
        viewModelScope.launch {
            val availability = ops.availability()
            if (availability != HcAvailability.AVAILABLE) {
                _state.update { it.copy(availability = availability, hasPermissions = false) }
                return@launch
            }
            val granted = ops.grantedPermissions()
            _state.update {
                it.copy(
                    availability = availability,
                    hasPermissions = HealthDataType.entries.any { type -> readPermission(type) in granted },
                    grantedPermissions = granted,
                    accessBlocked = null
                )
            }
            // History access granted from the open backfill dialog: now the oldest record can be found.
            if (_state.value.backfillDialog && _state.value.backfillEarliest == null) loadBackfillEarliest()

            pendingReceiveType?.let { type ->
                pendingReceiveType = null
                if (type.writePermission in granted) enableReceiveType(type)
            }
            val arrived = pendingTypes.filter { readPermission(it) in granted }.toSet()
            pendingTypes = emptySet()
            if (arrived.isNotEmpty()) commitTypes(_state.value.saved.enabledTypes + arrived)

            // A fresh install with permissions already granted: pre-select the granted types.
            // Not once the user has chosen, an empty choice included, or None would not stick.
            if (_state.value.saved.enabledTypes.isEmpty() && granted.isNotEmpty() && !settings.healthTypesChosen()) {
                val grantedTypes = HealthDataType.entries
                    .filter { readPermission(it) in granted }
                    .toSet()
                if (grantedTypes.isNotEmpty()) {
                    settings.setHealthEnabledTypes(grantedTypes)
                    stored { it.copy(enabledTypes = grantedTypes) }
                }
            }
        }
    }

    override fun setSchedule(schedule: ScheduleDraft) {
        val saved = _state.value.saved.schedule
        val interval = SettingsRules.intervalOrNull(schedule.intervalText)
            ?: SettingsRules.intervalOrNull(saved.intervalText)
            ?: SyncSchedule.DEFAULT_INTERVAL_MINUTES
        val next = schedule.toSchedule(fallbackInterval = interval)
        if (!saved.toSchedule(fallbackInterval = interval).differsFrom(next)) return
        settings.setSchedule(LogType.HEALTH_CONNECT, next)
        settings.reschedule(LogType.HEALTH_CONNECT)
        stored { it.copy(schedule = ScheduleDraft.from(next)) }
    }

    override fun saveInterval(text: String): UiMessage? {
        SettingsRules.intervalOrNull(text) ?: return UiMessage.IntervalTooShort
        setSchedule(_state.value.saved.schedule.copy(intervalText = text))
        return null
    }

    override fun setResolution(type: HealthDataType, resolution: SeriesResolution) {
        val next = _state.value.saved.resolutions + (type to resolution)
        settings.setSeriesResolutions(next)
        stored { it.copy(resolutions = next) }
    }

    private fun commitTypes(types: Set<HealthDataType>) {
        settings.setHealthEnabledTypes(types)
        settings.setHealthTypesChosen(true)
        stored { it.copy(enabledTypes = types) }
        _state.update { it.copy(accessBlocked = null) }
    }

    // ==================== What the destination screens save ====================

    override fun destinationDraft(id: String?, address: String?): DestinationDraft {
        val webhook = settings.loadHealth().webhook
        return if (id != null && webhook.destinations.any { it.id == id }) {
            DestinationDraft.existing(webhook, id)
        } else {
            DestinationDraft.new(webhook, address.orEmpty())
        }
    }

    override fun saveDestination(draft: DestinationDraft): UiMessage? {
        val stored = settings.loadHealth().webhook
        val next = stored.withDestination(draft.destination).trimmed()
        SettingsRules.destinationProblem(next, stored.urls)?.let { return it }
        if (!settings.setDestinations(LogType.HEALTH_CONNECT, next.destinations)) return UiMessage.SecretsNotReady
        destinationsStored()
        return null
    }

    /** Receive follows its source destination in the store, so it is read back along with the list. */
    private fun destinationsStored() {
        val webhook = settings.loadHealth().webhook
        refusedAction = null
        stored { it.copy(webhook = webhook) }
        _state.update { it.copy(refused = null, receive = settings.receiveSettings(), secretsNeedReentry = settings.secretsNeedReentry) }
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

    override fun mqttDraft(): MqttDraft = settings.loadHealth().mqtt

    /**
     * Writes the shared broker only when this screen changed it. Whatever is stored by then wins
     * otherwise: the other tab may have changed it since this tab read it.
     */
    override fun saveMqtt(mqtt: MqttDraft): UiMessage? {
        SettingsRules.mqttFieldProblem(mqtt)?.let { return it }
        val next = mqtt.withPort()
        val editedShared = next.section.useSharedBroker && next.sharedBroker != _state.value.saved.mqtt.withPort().sharedBroker
        if (!settings.setMqtt(MqttSection.HEALTH, next.section, next.sharedBroker.takeIf { editedShared })) return UiMessage.SecretsNotReady
        mqttStored()
        return null
    }

    override fun removeMqtt() {
        val stored = settings.loadHealth().mqtt
        val shared = stored.section.useSharedBroker
        val otherPublishes = settings.loadScreenTime().mqtt.section.let { it.enabled && it.useSharedBroker }
        val section = stored.section.copy(
            enabled = false,
            ownBroker = if (shared) stored.section.ownBroker else stored.section.ownBroker.cleared()
        )
        if (!settings.setMqtt(MqttSection.HEALTH, section, stored.sharedBroker.cleared().takeIf { shared && !otherPublishes })) {
            refuse(::removeMqtt)
            return
        }
        settings.clearMqttStatus(MqttSection.HEALTH)
        mqttStored()
    }

    private fun mqttStored() {
        val mqtt = settings.loadHealth().mqtt
        refusedAction = null
        stored { it.copy(mqtt = mqtt) }
        _state.update { it.copy(refused = null, mqttLastStatus = settings.lastMqttStatus(MqttSection.HEALTH), secretsNeedReentry = settings.secretsNeedReentry) }
    }

    override fun removeDestination(id: String) {
        val stored = settings.loadHealth().webhook
        if (stored.destinations.none { it.id == id }) return
        if (settings.setDestinations(LogType.HEALTH_CONNECT, stored.without(id).destinations)) {
            destinationsStored()
        } else {
            refuse { removeDestination(id) }
        }
    }

    /**
     * A type goes on only with its read permission granted. Grant asks for the enabled types
     * alone, so a type switched on later asks for its own permission first.
     */
    override fun toggleType(type: HealthDataType, enabled: Boolean) {
        if (enabled && readPermission(type) !in _state.value.grantedPermissions) {
            if (healthConnectMissing()) return
            _state.update { it.copy(permissionPrompt = type) }
            return
        }
        commitTypes(_state.value.saved.enabledTypes.let { if (enabled) it + type else it - type })
    }

    override fun applyTypes(types: Set<HealthDataType>) {
        val granted = _state.value.grantedPermissions
        val (ready, missing) = types.partition { readPermission(it) in granted }
        // Without Health Connect nothing can be granted, and the selection would be lost for it.
        if (missing.isNotEmpty() && healthConnectMissing()) return
        commitTypes(ready.toSet())
        pendingTypes = missing.toSet()
        if (missing.isNotEmpty()) {
            askPermissions(HealthPermissionRequests.readPermissions(missing) + HealthConnectManager.BACKGROUND_PERMISSION)
        }
    }

    /** True when there is no Health Connect to ask, and then the screen says so in [HealthUiState.accessBlocked]. */
    private fun healthConnectMissing(): Boolean {
        val availability = _state.value.availability
        _state.update { it.copy(accessBlocked = availability.takeIf { missing -> missing != HcAvailability.AVAILABLE }) }
        return availability != HcAvailability.AVAILABLE
    }

    private fun askPermissions(permissions: Set<String>) {
        if (!healthConnectMissing()) _permissionRequests.tryEmit(permissions)
    }

    override fun dismissPermissionPrompt() = _state.update { it.copy(permissionPrompt = null) }

    override fun setIncludeDailyTotals(enabled: Boolean) {
        settings.setIncludeDailyTotals(enabled)
        _state.update { it.copy(includeDailyTotals = enabled) }
    }

    override fun setIncludeRecordMetadata(enabled: Boolean) {
        settings.setIncludeRecordMetadata(enabled)
        _state.update { it.copy(includeRecordMetadata = enabled) }
    }

    override fun setKeepFullPayloads(enabled: Boolean) {
        settings.setKeepFullPayloads(enabled)
        _state.update { it.copy(keepFullPayloads = enabled) }
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

    override fun reloadFromSettings() {
        val saved = settings.loadHealth()
        _state.update {
            it.copy(
                saved = saved,
                includeDailyTotals = settings.includeDailyTotals(),
                includeRecordMetadata = settings.includeRecordMetadata(),
                keepFullPayloads = settings.keepFullPayloads(),
                allowHttpWebhooks = settings.allowHttpWebhooks(),
                clientCertAlias = settings.clientCertAlias(),
                serverTrust = settings.serverTrust(),
                phoneName = settings.phoneName(),
                failureNotificationsEnabled = settings.failureNotificationsEnabled(),
                failureThreshold = settings.failureThreshold(),
                mqttLastStatus = settings.lastMqttStatus(MqttSection.HEALTH),
                receive = settings.receiveSettings(),
                receiveStatus = settings.receiveStatus()
            )
        }
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
                // Everything a screen applies is stored by now, and a sync reads what is stored.
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
                    onFailure = { UiMessage.headerProblem(it) ?: UiMessage.SyncFailed(it.message ?: "") }
                )
                // The sync may have learned which types the integration offers, or written some.
                _state.update {
                    it.copy(
                        syncMessage = message,
                        receiveStatus = settings.receiveStatus(),
                        // The broker may have failed while the webhook took the payload.
                        mqttLastStatus = settings.lastMqttStatus(MqttSection.HEALTH),
                        refreshKey = it.refreshKey + 1
                    )
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
                ops.preview(_state.value.saved.enabledTypes, _state.value.saved.resolutions).fold(
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
                ops.preview(_state.value.saved.enabledTypes, _state.value.saved.resolutions).fold(
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

    /**
     * Reads the Today panel's figures for the enabled types, off the settings as they are stored.
     * A newer call replaces one under way. A figure that cannot be read right now keeps its last
     * value; only the types that are enabled are read, so a sensitive type can never be asked for.
     */
    fun refreshToday() {
        todayRead?.cancel()
        todayRead = viewModelScope.launch {
            val kinds = TodayKind.entries.filter { it.dataType in _state.value.saved.enabledTypes }
            val reads = if (kinds.isEmpty()) emptyMap() else ops.todaySeries(kinds.toSet())
            val previous = _state.value.today.stats
            val stats = kinds.mapNotNull { kind ->
                when (val series = reads[kind]) {
                    null -> previous.firstOrNull { it.kind == kind }
                    else -> series.takeIf { it.isNotEmpty() }?.let { TodayStat(kind, it) }
                }
            }
            _state.update { it.copy(today = TodayState(stats, settings.healthRecordsToday(), hasLoaded = true)) }
        }
    }

    /** Backfill posts history to webhooks; MQTT only carries the latest value, so say so instead of failing. */
    override fun openBackfillDialog() {
        if (_state.value.saved.webhook.urls.isEmpty()) {
            _toasts.tryEmit(UiMessage.BackfillNeedsWebhook)
            return
        }
        _state.update { it.copy(backfillDialog = true, stoppedBackfill = ops.stoppedBackfill(), backfillEarliest = null) }
        loadBackfillEarliest()
    }

    /** Looks up the oldest record for the open dialog, only with history access: without it the answer is cut off. */
    private fun loadBackfillEarliest() {
        if (HealthConnectManager.HISTORY_PERMISSION !in _state.value.grantedPermissions) return
        viewModelScope.launch {
            val earliest = ops.earliestRecord()
            _state.update { if (it.backfillDialog) it.copy(backfillEarliest = earliest) else it }
        }
    }

    override fun dismissBackfillDialog() = _state.update { it.copy(backfillDialog = false) }

    /**
     * The backfill is a WorkManager job (P2-14), so this only asks for it; [observeBackfill]
     * shows where it is. A start while one is queued or running says so instead of doing
     * nothing in silence; one right after Stop replaces the work that is still winding down.
     */
    override fun backfill(days: Int) {
        _state.update { it.copy(backfillDialog = false, syncMessage = null) }
        stopping = false
        viewModelScope.launch {
            try {
                when (ops.startBackfill(days)) {
                    BackfillStart.ALREADY_RUNNING -> _toasts.tryEmit(UiMessage.BackfillAlreadyRunning)
                    BackfillStart.NO_RECORDS -> _toasts.tryEmit(UiMessage.BackfillNoRecords)
                    BackfillStart.STARTED -> Unit
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(syncMessage = UiMessage.SyncFailed(e.message ?: "")) }
            }
        }
    }

    /**
     * Stop takes effect on screen at once. The work it cancels may still report itself running
     * for a moment while its worker unwinds; [observeBackfill] ignores that until the work is
     * gone or a new backfill is started.
     */
    override fun cancelBackfill() {
        stopping = true
        ops.cancelBackfill()
        _state.update { it.copy(backfillProgress = null, backfillWaiting = false, backfillPaused = false, backfillRestarted = false) }
    }

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
                        if (stopping) return@collect
                        sawRunning = true
                        _state.update {
                            it.copy(
                                backfillProgress = status.done to status.total,
                                backfillWaiting = status.waiting,
                                backfillPaused = status.paused,
                                backfillRestarted = status.restarted
                            )
                        }
                    }
                    is BackfillStatus.Finished -> {
                        val message = when {
                            !sawRunning || stopping -> null
                            status.failure != null -> UiMessage.BackfillFailed(status.failure)
                            else -> UiMessage.BackfillComplete(status.records ?: 0)
                        }
                        sawRunning = false
                        stopping = false
                        _state.update {
                            it.copy(
                                backfillProgress = null,
                                backfillWaiting = false,
                                backfillPaused = false,
                                backfillRestarted = false,
                                syncMessage = message ?: it.syncMessage,
                                refreshKey = if (message != null) it.refreshKey + 1 else it.refreshKey
                            )
                        }
                    }
                    BackfillStatus.Idle -> {
                        sawRunning = false
                        stopping = false
                        _state.update { it.copy(backfillProgress = null, backfillWaiting = false, backfillPaused = false, backfillRestarted = false) }
                    }
                }
            }
        }
    }

    private fun readPermission(type: HealthDataType) = HealthPermission.getReadPermission(type.recordClass)

    private fun accessPermissions(): Set<String> = HealthPermissionRequests.forGrant(_state.value.saved.enabledTypes)

    /** With nothing selected every read permission is offered, and what is granted becomes the selection. */
    override fun requestAccess() {
        if (_state.value.saved.enabledTypes.isEmpty()) pendingTypes = HealthDataType.entries.toSet()
        askPermissions(accessPermissions())
    }

    override fun requestHistoryPermission() {
        askPermissions(HealthPermissionRequests.forHistory(_state.value.saved.enabledTypes))
    }

    override fun requestTypePermission() {
        val type = _state.value.permissionPrompt ?: return
        pendingTypes = setOf(type)
        _state.update { it.copy(permissionPrompt = null) }
        askPermissions(setOf(readPermission(type)))
    }

    // ==================== Receive (issue #62) ====================

    /**
     * Switching Receive on needs the integration: a destination that looks like a Home
     * Assistant one, with a signing secret of its own to verify its answers with. The saved
     * section is what counts, since that is what the sync runs with. One candidate is taken;
     * several are offered as a choice; none is explained and the switch stays off.
     */
    override fun setReceiveEnabled(enabled: Boolean) {
        if (!enabled) {
            settings.setReceiveEnabled(false)
            _state.update { it.copy(receive = settings.receiveSettings()) }
            return
        }
        when (val choice = WriteBackPayload.sourceUrlChoice(_state.value.saved.webhook.urlsWithSecret)) {
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
        askPermissions(setOf(type.writePermission))
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
