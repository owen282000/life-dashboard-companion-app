package com.owen282000.lifedashboard.screens

import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.outlined.NetworkPing
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.owen282000.lifedashboard.ExportManager
import com.owen282000.lifedashboard.R
import com.owen282000.lifedashboard.ui.theme.ScreenTimePrimary
import com.owen282000.lifedashboard.viewmodel.ScreenTimeActions
import com.owen282000.lifedashboard.viewmodel.ScreenTimeUiState
import com.owen282000.lifedashboard.viewmodel.ScreenTimeViewModel
import kotlinx.coroutines.delay
import java.time.LocalTime

/** The Screen Time tab: stateful shell around [ScreenTimeContent]. */
@Composable
fun ScreenTimeScreen(
    /** Opens the QR scanner; the Activity owns it, because pairing spans both tabs. */
    onScanRequested: () -> Unit = {}
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val viewModel: ScreenTimeViewModel = viewModel(factory = ScreenTimeViewModel.factory(context))
    val state by viewModel.state.collectAsStateWithLifecycle()

    // Usage access is granted in system settings with no callback, so poll while visible.
    LaunchedEffect(Unit) {
        while (true) {
            viewModel.refreshUsageAccess()
            delay(1000)
        }
    }
    LaunchedEffect(Unit) {
        viewModel.toasts.collect { Toast.makeText(context, it.text(resources), Toast.LENGTH_SHORT).show() }
    }
    LaunchedEffect(Unit) {
        viewModel.openUsageAccess.collect { context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
    }

    ScreenTimeContent(
        state = state,
        actions = viewModel,
        onOpenUsageAccessSettings = viewModel::requestUsageAccess,
        onShareExport = { json, extension, mime ->
            ExportManager(context).shareFile(json, exportFileName("screen_time", extension), mime)
        },
        onScanRequested = onScanRequested
    )
}

@Composable
fun ScreenTimeContent(
    state: ScreenTimeUiState,
    actions: ScreenTimeActions,
    onOpenUsageAccessSettings: () -> Unit,
    onShareExport: (json: String, extension: String, mime: String) -> Unit,
    onScanRequested: () -> Unit = {}
) {
    val accent = ScreenTimePrimary
    val draft = state.draft
    var dayBoundaryExpanded by remember { mutableStateOf(false) }
    var boundaryPicker by remember { mutableStateOf(false) }
    var scheduleExpanded by remember { mutableStateOf(false) }
    var webhookExpanded by remember { mutableStateOf(false) }
    var mqttExpanded by remember { mutableStateOf(false) }
    var advancedExpanded by remember { mutableStateOf(false) }
    var notificationsExpanded by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        StatusBanner(
            accent = accent,
            title = stringResource(R.string.screentime_header),
            subtitle = if (state.hasUsageAccess) stringResource(R.string.screentime_access_granted)
            else stringResource(R.string.screentime_access_needed)
        ) {
            if (state.hasUsageAccess) {
                BannerChip(stringResource(R.string.screentime_settings), accent, icon = Icons.AutoMirrored.Filled.OpenInNew, onClick = onOpenUsageAccessSettings)
            } else {
                BannerChip(stringResource(R.string.screentime_allow), accent, filled = true, onClick = onOpenUsageAccessSettings)
            }
        }

        if (state.hasUsageAccess) {
            ScreenTimeDashboardCard(refreshKey = state.refreshKey)
            if (state.secretsUnavailable) SecretsUnavailableBanner()
        }

        GroupCard {
            ExpandableRow(
                icon = Icons.Outlined.WbSunny,
                accent = accent,
                title = stringResource(R.string.screentime_day_boundary_title),
                subtitle = if (draft.useDayBoundary) stringResource(
                    R.string.screentime_day_boundary_enabled,
                    draft.dayBoundaryHour.toIntOrNull()?.let { formatTime(LocalTime.of(it.coerceIn(0, 23), 0)) } ?: draft.dayBoundaryHour
                )
                else stringResource(R.string.screentime_day_boundary_disabled),
                subtitleAccent = draft.useDayBoundary,
                expanded = dayBoundaryExpanded,
                onToggle = { dayBoundaryExpanded = !dayBoundaryExpanded }
            ) {
                SwitchLine(stringResource(R.string.screentime_day_boundary_enable), null, draft.useDayBoundary, accent, actions::setUseDayBoundary)
                if (draft.useDayBoundary) {
                    Text(
                        stringResource(R.string.screentime_day_boundary_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    // The same clock the sync schedule uses. The boundary is stored as a whole
                    // hour, so the minutes the picker offers are dropped on the way in.
                    TimeChip(
                        label = stringResource(R.string.screentime_day_boundary_time_label),
                        time = draft.dayBoundaryHour.toIntOrNull()?.let { LocalTime.of(it.coerceIn(0, 23), 0) },
                        accent = accent,
                        onClick = { boundaryPicker = true }
                    )
                }
            }
            GroupDivider()
            ScheduleRow(
                accent = accent,
                schedule = draft.schedule,
                expanded = scheduleExpanded,
                onToggle = { scheduleExpanded = !scheduleExpanded },
                onChange = actions::setSchedule
            )
        }

        GroupCard {
            WebhookRow(
                accent = accent,
                webhook = draft.webhook,
                expanded = webhookExpanded,
                onToggle = { webhookExpanded = !webhookExpanded },
                onAddUrl = actions::addUrl,
                onRemoveUrl = actions::removeUrl,
                onAddHeader = actions::addHeader,
                onRemoveHeader = actions::removeHeader,
                onSecretChange = actions::setSecret,
                onScanRequested = onScanRequested
            )
            GroupDivider()
            MqttRow(
                accent = accent,
                mqtt = draft.mqtt,
                onChange = actions::setMqtt,
                description = stringResource(R.string.screentime_mqtt_description),
                otherSection = stringResource(R.string.main_title_health_connect),
                lastStatus = state.mqttLastStatus,
                expanded = mqttExpanded,
                onToggle = { mqttExpanded = !mqttExpanded }
            )
        }

        GroupCard {
            ExpandableRow(
                icon = Icons.Outlined.Tune,
                accent = accent,
                title = stringResource(R.string.sync_advanced_title),
                subtitle = listOfNotNull(
                    if (state.allowHttpWebhooks) stringResource(R.string.sync_advanced_plain_http_allowed) else stringResource(R.string.sync_advanced_https_only),
                    if (state.clientCertAlias != null) stringResource(R.string.sync_advanced_client_cert) else null
                ).joinToString(", "),
                expanded = advancedExpanded,
                onToggle = { advancedExpanded = !advancedExpanded }
            ) {
                SwitchLine(
                    stringResource(R.string.webhook_allow_plain_http),
                    stringResource(R.string.webhook_allow_plain_http_description),
                    state.allowHttpWebhooks,
                    accent,
                    actions::setAllowHttpWebhooks
                )
                ClientCertLine(
                    alias = state.clientCertAlias,
                    webhookUrl = state.draft.webhook.urls.firstOrNull(),
                    accent = accent,
                    onAliasChange = actions::setClientCertAlias
                )
            }
            GroupDivider()
            NotificationsRow(
                accent = accent,
                enabled = state.failureNotificationsEnabled,
                threshold = state.failureThreshold,
                expanded = notificationsExpanded,
                onToggle = { notificationsExpanded = !notificationsExpanded },
                onEnabledChange = actions::setFailureNotifications,
                onThresholdChange = actions::setFailureThreshold
            )
        }

        Spacer(modifier = Modifier.height(4.dp))
        PrimaryPill(
            label = if (state.isSyncing) stringResource(R.string.sync_syncing) else stringResource(R.string.sync_now),
            accent = accent,
            enabled = state.canSync,
            loading = state.isSyncing,
            onClick = actions::syncNow
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionTile(Icons.Outlined.Visibility, stringResource(R.string.sync_action_preview), accent,
                enabled = state.hasUsageAccess, loading = state.isPreviewing, onClick = actions::preview)
            ActionTile(Icons.Outlined.NetworkPing, stringResource(R.string.sync_action_ping), accent,
                enabled = draft.webhook.urls.isNotEmpty(), loading = state.isPinging, onClick = actions::testPing)
            ActionTile(Icons.Outlined.Share, stringResource(R.string.sync_action_export), accent,
                enabled = state.hasUsageAccess, loading = state.isExporting, onClick = actions::export)
        }
        SyncMessageLine(state.syncMessage, accent)

        if (boundaryPicker) {

            TimePickerDialog(

                initial = draft.dayBoundaryHour.toIntOrNull()?.let { LocalTime.of(it.coerceIn(0, 23), 0) } ?: LocalTime.of(4, 0),

                onDismiss = { boundaryPicker = false },

                onPicked = { time ->

                    // Stored as a whole hour since 1.0; the reader builds LocalTime.of(hour, 0).

                    actions.setDayBoundaryHour(time.hour.toString())

                    boundaryPicker = false
                },

                confirmLabel = stringResource(R.string.common_set)

            )
        }

        SaveBar(visible = state.hasChanges, onSave = actions::save)

        ScheduleStatusLine(
            schedule = draft.schedule,
            webhookCount = draft.webhook.urls.size,
            mqttEnabled = draft.mqtt.section.enabled,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
        )
        Spacer(modifier = Modifier.height(72.dp))
    }

    state.previewData?.let { PreviewDialog(accent, it, actions::dismissPreview) }

    state.exportJson?.let { json ->
        ExportFormatDialog(
            accent = accent,
            description = stringResource(R.string.screentime_export_dialog_description),
            onJson = { actions.dismissExport(); onShareExport(json, "json", "application/json") },
            onCsv = { actions.dismissExport(); onShareExport(json, "csv", "text/csv") },
            onDismiss = actions::dismissExport
        )
    }
}
