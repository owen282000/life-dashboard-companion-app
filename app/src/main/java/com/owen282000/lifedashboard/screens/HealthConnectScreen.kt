package com.owen282000.lifedashboard.screens

import android.content.Intent
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
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.NetworkPing
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.permission.HealthPermission
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.owen282000.lifedashboard.ExportManager
import com.owen282000.lifedashboard.HealthConnectManager
import com.owen282000.lifedashboard.R
import com.owen282000.lifedashboard.ui.theme.HealthPrimary
import com.owen282000.lifedashboard.viewmodel.HcAvailability
import com.owen282000.lifedashboard.viewmodel.HealthActions
import com.owen282000.lifedashboard.viewmodel.HealthConnectViewModel
import com.owen282000.lifedashboard.viewmodel.HealthUiState

private const val HEALTH_CONNECT_PACKAGE = "com.google.android.apps.healthdata"
private const val HEALTH_CONNECT_STORE_URL = "https://play.google.com/store/apps/details?id=$HEALTH_CONNECT_PACKAGE"

/**
 * The Health Connect tab. This is the stateful entry point: it owns the view model, wires
 * the permission launcher, toasts and lifecycle, and hands everything else to
 * [HealthConnectContent], which is a pure function of state and callbacks.
 */
@Composable
fun HealthConnectScreen(
    permissionLauncher: androidx.activity.result.ActivityResultLauncher<Set<String>>,
    onPermissionResult: ((Boolean) -> Unit)? = null
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val viewModel: HealthConnectViewModel = viewModel(factory = HealthConnectViewModel.factory(context))
    val state by viewModel.state.collectAsStateWithLifecycle()

    // Permissions are granted in Health Connect's own UI, so re-check whenever we come back.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refreshPermissions()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(Unit) {
        viewModel.toasts.collect { Toast.makeText(context, it.text(resources), Toast.LENGTH_SHORT).show() }
    }
    LaunchedEffect(Unit) {
        viewModel.permissionRequests.collect { permissions ->
            try {
                permissionLauncher.launch(permissions)
            } catch (e: Exception) {
                Toast.makeText(context, resources.getString(R.string.health_error_with_reason, e.message ?: ""), Toast.LENGTH_LONG).show()
            }
        }
    }
    LaunchedEffect(state.hasPermissions) {
        state.hasPermissions?.let { onPermissionResult?.invoke(it) }
    }

    HealthConnectContent(
        state = state,
        actions = viewModel,
        onOpenHealthConnect = {
            try {
                val intent = context.packageManager.getLaunchIntentForPackage(HEALTH_CONNECT_PACKAGE)
                if (intent != null) {
                    context.startActivity(intent)
                } else {
                    try {
                        context.startActivity(Intent("android.health.connect.action.HEALTH_HOME_SETTINGS"))
                    } catch (e: Exception) {
                        context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(HEALTH_CONNECT_STORE_URL)))
                    }
                }
            } catch (e: Exception) {
                Toast.makeText(context, resources.getString(R.string.health_could_not_open), Toast.LENGTH_SHORT).show()
            }
        },
        onInstallHealthConnect = {
            context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(HEALTH_CONNECT_STORE_URL)))
        },
        onShareExport = { json, extension, mime ->
            ExportManager(context).shareFile(json, exportFileName("health_data", extension), mime)
        }
    )
}

@Composable
fun HealthConnectContent(
    state: HealthUiState,
    actions: HealthActions,
    onOpenHealthConnect: () -> Unit,
    onInstallHealthConnect: () -> Unit,
    onShareExport: (json: String, extension: String, mime: String) -> Unit
) {
    val accent = HealthPrimary
    val draft = state.draft
    var dataTypesExpanded by remember { mutableStateOf(false) }
    var webhookExpanded by remember { mutableStateOf(false) }
    var mqttExpanded by remember { mutableStateOf(false) }
    var advancedExpanded by remember { mutableStateOf(false) }
    var notificationsExpanded by remember { mutableStateOf(false) }
    var showBackfillDialog by remember { mutableStateOf(false) }

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
            title = stringResource(R.string.main_title_health_connect),
            subtitle = when {
                state.availability == HcAvailability.NOT_INSTALLED -> stringResource(R.string.health_connect_not_installed)
                state.availability == HcAvailability.NEEDS_UPDATE -> stringResource(R.string.health_connect_needs_update)
                state.availability == HcAvailability.UNAVAILABLE -> stringResource(R.string.health_connect_not_available)
                state.hasPermissions == false -> stringResource(R.string.health_access_not_granted)
                else -> pluralStringResource(R.plurals.health_data_types_selected, draft.enabledTypes.size, draft.enabledTypes.size)
            }
        ) {
            when {
                state.availability == HcAvailability.NEEDS_UPDATE ->
                    BannerChip(stringResource(R.string.health_update), accent, filled = true, onClick = onInstallHealthConnect)
                state.availability != HcAvailability.AVAILABLE ->
                    BannerChip(stringResource(R.string.health_install), accent, filled = true, onClick = onInstallHealthConnect)
                state.hasPermissions == false ->
                    BannerChip(stringResource(R.string.common_grant), accent, filled = true, onClick = actions::requestAllPermissions)
                else ->
                    BannerChip(stringResource(R.string.health_open_app), accent, icon = Icons.AutoMirrored.Filled.OpenInNew, onClick = onOpenHealthConnect)
            }
        }

        DashboardCard()

        // Keystore outage: secrets cannot be read or saved, so say so rather than let
        // syncs fail with unexplained auth errors.
        if (state.secretsUnavailable) SecretsUnavailableBanner()

        GroupCard {
            DataTypesRow(
                accent = accent,
                enabledTypes = draft.enabledTypes,
                grantedPermissions = state.grantedPermissions,
                expanded = dataTypesExpanded,
                onToggle = { dataTypesExpanded = !dataTypesExpanded },
                onToggleType = actions::toggleType
            )
            GroupDivider()
            SyncIntervalRow(accent, draft.syncInterval, actions::setSyncInterval)
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
                onSecretChange = actions::setSecret
            )
            GroupDivider()
            MqttRow(
                accent = accent,
                mqtt = draft.mqtt,
                onChange = actions::setMqtt,
                description = stringResource(R.string.health_mqtt_description),
                otherSection = stringResource(R.string.main_title_screen_time),
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
                subtitle = (if (state.includeDailyTotals) stringResource(R.string.health_daily_totals) else stringResource(R.string.health_no_daily_totals)) + ", " +
                    (if (state.allowHttpWebhooks) stringResource(R.string.sync_advanced_plain_http_allowed) else stringResource(R.string.sync_advanced_https_only)),
                expanded = advancedExpanded,
                onToggle = { advancedExpanded = !advancedExpanded }
            ) {
                SwitchLine(
                    stringResource(R.string.health_daily_totals_title),
                    stringResource(R.string.health_daily_totals_description),
                    state.includeDailyTotals,
                    accent,
                    actions::setIncludeDailyTotals
                )
                SwitchLine(
                    stringResource(R.string.webhook_allow_plain_http),
                    stringResource(R.string.webhook_allow_plain_http_description),
                    state.allowHttpWebhooks,
                    accent,
                    actions::setAllowHttpWebhooks
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
                enabled = draft.enabledTypes.isNotEmpty(), loading = state.isPreviewing, onClick = actions::preview)
            ActionTile(Icons.Outlined.NetworkPing, stringResource(R.string.sync_action_ping), accent,
                enabled = draft.webhook.urls.isNotEmpty(), loading = state.isPinging, onClick = actions::testPing)
            ActionTile(Icons.Outlined.Share, stringResource(R.string.sync_action_export), accent,
                enabled = draft.enabledTypes.isNotEmpty(), loading = state.isExporting, onClick = actions::export)
            ActionTile(Icons.Outlined.History, stringResource(R.string.sync_action_backfill), accent,
                enabled = draft.enabledTypes.isNotEmpty(), loading = state.backfillProgress != null,
                onClick = { showBackfillDialog = true })
        }
        state.backfillProgress?.let { (done, total) ->
            Text(
                stringResource(R.string.health_backfill_progress, done, total),
                style = MaterialTheme.typography.bodySmall,
                color = accent,
                modifier = Modifier.padding(horizontal = 4.dp)
            )
        }
        SyncMessageLine(state.syncMessage, accent)

        SaveBar(visible = state.hasChanges, onSave = actions::save)

        Text(
            pluralStringResource(R.plurals.webhook_status_syncing, draft.webhook.urls.size, draft.syncInterval, draft.webhook.urls.size),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
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
            description = stringResource(R.string.health_export_dialog_description),
            onJson = { actions.dismissExport(); onShareExport(json, "json", "application/json") },
            onCsv = { actions.dismissExport(); onShareExport(json, "csv", "text/csv") },
            onDismiss = actions::dismissExport
        )
    }

    state.permissionPrompt?.let { type ->
        AlertDialog(
            onDismissRequest = actions::dismissPermissionPrompt,
            title = { Text(stringResource(R.string.health_permission_required_title)) },
            text = { Text(stringResource(R.string.health_permission_needed, type.displayName)) },
            confirmButton = {
                TextButton(onClick = {
                    actions.requestPermission(HealthPermission.getReadPermission(type.recordClass))
                    actions.dismissPermissionPrompt()
                }) { Text(stringResource(R.string.common_grant), color = accent) }
            },
            dismissButton = {
                TextButton(onClick = actions::dismissPermissionPrompt) { Text(stringResource(R.string.common_cancel)) }
            }
        )
    }

    if (showBackfillDialog) {
        // Without READ_HEALTH_DATA_HISTORY Health Connect only exposes the 30 days before the
        // first permission grant, so a 90/365 day backfill would silently return recent data
        // only (#39). Granting happens via the normal permission flow.
        val hasHistoryPermission = HealthConnectManager.HISTORY_PERMISSION in state.grantedPermissions
        AlertDialog(
            onDismissRequest = { showBackfillDialog = false },
            title = { Text(stringResource(R.string.health_backfill_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.health_backfill_dialog_description))
                    if (!hasHistoryPermission) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            stringResource(R.string.health_backfill_history_permission_warning),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                        TextButton(onClick = actions::requestAllPermissions) {
                            Text(stringResource(R.string.health_backfill_grant_history))
                        }
                    }
                }
            },
            confirmButton = {
                Row {
                    listOf(30, 90, 365).forEach { days ->
                        TextButton(onClick = {
                            showBackfillDialog = false
                            actions.backfill(days)
                        }) { Text(stringResource(R.string.health_backfill_days, days)) }
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { showBackfillDialog = false }) { Text(stringResource(R.string.common_cancel)) }
            }
        )
    }
}
