package com.owen282000.lifedashboard.screens

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.res.Resources
import android.os.Build
import android.os.PersistableBundle
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.MonitorHeart
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.permission.HealthPermission
import com.owen282000.lifedashboard.HealthDataType
import com.owen282000.lifedashboard.R
import com.owen282000.lifedashboard.WebhookSecret
import com.owen282000.lifedashboard.ui.theme.Success
import com.owen282000.lifedashboard.viewmodel.MqttDraft
import com.owen282000.lifedashboard.viewmodel.SettingsRules
import com.owen282000.lifedashboard.viewmodel.UiMessage
import com.owen282000.lifedashboard.viewmodel.WebhookDraft

/*
 * The settings sections the Health Connect and Screen Time tabs share: webhook, MQTT,
 * notifications, the sync interval, and the dialogs. Each is a row for a GroupCard and
 * takes its state and callbacks from the tab's view model.
 */

/** Turns a [UiMessage] into the text the user sees; used for toasts and the sync line. */
fun UiMessage.text(res: Resources): String = when (this) {
    UiMessage.Saved -> res.getString(R.string.common_saved)
    UiMessage.IntervalTooShort -> res.getString(R.string.webhook_min_interval)
    UiMessage.NoSyncTimes -> res.getString(R.string.schedule_no_sync_times)
    UiMessage.ScheduleNeverRuns -> res.getString(R.string.schedule_never_runs)
    UiMessage.NoDestination -> res.getString(R.string.sync_no_destination)
    UiMessage.InvalidDayBoundaryHour -> res.getString(R.string.screentime_day_boundary_hour_invalid)
    UiMessage.InvalidUrl -> res.getString(R.string.webhook_enter_valid_url)
    UiMessage.HeaderNeedsNameAndValue -> res.getString(R.string.webhook_enter_header_name_and_value)
    UiMessage.NoNewData -> res.getString(R.string.sync_no_new_data)
    is UiMessage.SyncedRecords -> res.getQuantityString(R.plurals.health_synced_records, count, count)
    is UiMessage.QueuedRecords -> res.getQuantityString(R.plurals.health_queued_records, count, count)
    is UiMessage.SyncedApps -> res.getQuantityString(R.plurals.screentime_apps_synced, count, count)
    is UiMessage.QueuedApps -> res.getQuantityString(R.plurals.screentime_apps_queued, count, count)
    is UiMessage.SyncFailed -> res.getString(R.string.sync_failed_with_reason, reason)
    UiMessage.HealthConnectUnavailable -> res.getString(R.string.health_connect_not_available_short)
    UiMessage.UsageAccessMissing -> res.getString(R.string.screentime_permission_not_granted)
    is UiMessage.PreviewFailed -> reason?.let { res.getString(R.string.sync_preview_failed_with_reason, it) }
        ?: res.getString(R.string.sync_preview_failed)
    is UiMessage.ExportFailed -> reason?.let { res.getString(R.string.sync_export_failed_with_reason, it) }
        ?: res.getString(R.string.sync_export_failed)
    UiMessage.PingDelivered -> res.getString(R.string.health_test_ping_delivered)
    UiMessage.PingFailed -> res.getString(R.string.health_test_ping_failed)
    is UiMessage.PingFailedWith -> res.getString(R.string.health_test_ping_failed_with_reason, reason)
    is UiMessage.BackfillComplete -> res.getQuantityString(R.plurals.health_backfill_complete, count, count)
    UiMessage.BackfillNeedsWebhook -> res.getString(R.string.health_backfill_needs_webhook)
}

/** The line under the sync actions: the outcome of the last sync, red when it failed. */
@Composable
fun SyncMessageLine(message: UiMessage?, accent: Color) {
    AnimatedVisibility(visible = message != null) {
        message?.let {
            Text(
                it.text(LocalResources.current),
                style = MaterialTheme.typography.bodySmall,
                color = if (it.isFailure) MaterialTheme.colorScheme.error else accent,
                modifier = Modifier.padding(horizontal = 4.dp)
            )
        }
    }
}

/** Webhook URLs, headers and the HMAC secret, folded under one row. */
@Composable
fun WebhookRow(
    accent: Color,
    webhook: WebhookDraft,
    expanded: Boolean,
    onToggle: () -> Unit,
    onAddUrl: (String) -> Unit,
    onRemoveUrl: (Int) -> Unit,
    onAddHeader: (String, String) -> Unit,
    onRemoveHeader: (String) -> Unit,
    onSecretChange: (String) -> Unit
) {
    var newUrl by remember { mutableStateOf("") }
    var newHeaderKey by remember { mutableStateOf("") }
    var newHeaderValue by remember { mutableStateOf("") }

    ExpandableRow(
        icon = Icons.Outlined.Link,
        accent = accent,
        title = stringResource(R.string.webhook_title),
        subtitle = stringResource(R.string.webhook_urls_configured, webhook.urls.size),
        subtitleAccent = webhook.urls.isNotEmpty(),
        expanded = expanded,
        onToggle = onToggle
    ) {
        webhook.urls.forEachIndexed { index, url ->
            ListLine(text = url, onRemove = { onRemoveUrl(index) })
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            FilledField(
                value = newUrl,
                onValueChange = { newUrl = it },
                accent = accent,
                placeholder = stringResource(R.string.webhook_url_placeholder),
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.width(8.dp))
            AddButton(accent) {
                onAddUrl(newUrl)
                if (SettingsRules.isValidUrl(newUrl)) newUrl = ""
            }
        }

        Spacer(modifier = Modifier.height(4.dp))
        Text(stringResource(R.string.webhook_headers_title), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        Text(
            if (webhook.headers.isEmpty()) stringResource(R.string.common_none_configured)
            else pluralStringResource(R.plurals.webhook_headers_count, webhook.headers.size, webhook.headers.size),
            style = MaterialTheme.typography.bodySmall,
            color = if (webhook.headers.isNotEmpty()) accent else MaterialTheme.colorScheme.onSurfaceVariant
        )
        webhook.headers.forEach { (key, value) ->
            ListLine(text = key, secondary = value, onRemove = { onRemoveHeader(key) })
        }
        FilledField(
            value = newHeaderKey,
            onValueChange = { newHeaderKey = it },
            accent = accent,
            placeholder = stringResource(R.string.webhook_header_name)
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            FilledField(
                value = newHeaderValue,
                onValueChange = { newHeaderValue = it },
                accent = accent,
                placeholder = stringResource(R.string.webhook_header_value),
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.width(8.dp))
            AddButton(accent) {
                onAddHeader(newHeaderKey, newHeaderValue)
                if (newHeaderKey.isNotBlank() && newHeaderValue.isNotBlank()) {
                    newHeaderKey = ""
                    newHeaderValue = ""
                }
            }
        }

        Spacer(modifier = Modifier.height(4.dp))
        Text(stringResource(R.string.webhook_hmac_secret_title), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        Text(
            stringResource(R.string.webhook_hmac_secret_description),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        var generated by remember { mutableStateOf(false) }
        val context = LocalContext.current
        Row(verticalAlignment = Alignment.CenterVertically) {
            FilledField(
                value = webhook.secret,
                onValueChange = {
                    onSecretChange(it)
                    generated = false
                },
                accent = accent,
                placeholder = stringResource(R.string.webhook_shared_secret),
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.width(8.dp))
            TextButton(onClick = {
                onSecretChange(WebhookSecret.generate())
                generated = true
            }) {
                Text(stringResource(R.string.webhook_secret_generate), color = accent)
            }
        }
        // Shown only right after generating. The secret is stored encrypted and the field is
        // repopulated from it, but saying so invites people to come back for it later instead
        // of putting it on their server now, which is the step that actually matters.
        if (generated && webhook.secret.isNotBlank()) {
            Text(
                stringResource(R.string.webhook_secret_generated),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            val copied = stringResource(R.string.webhook_secret_copied)
            TextButton(onClick = { copySecret(context, webhook.secret, copied) }) {
                Text(stringResource(R.string.webhook_secret_copy), color = accent)
            }
        }
    }
}

@Composable
private fun AddButton(accent: Color, onClick: () -> Unit) {
    FilledIconButton(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.size(52.dp),
        colors = IconButtonDefaults.filledIconButtonColors(containerColor = accent)
    ) {
        Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.common_add), tint = Color.White)
    }
}

/** One configured value with a remove cross: a URL, or a header name and value. */
@Composable
private fun ListLine(text: String, secondary: String? = null, onRemove: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f), RoundedCornerShape(10.dp))
            .padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = if (secondary != null) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (secondary != null) {
                Text(
                    secondary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        IconButton(onClick = onRemove, modifier = Modifier.size(32.dp)) {
            Icon(
                Icons.Filled.Close,
                contentDescription = stringResource(R.string.common_remove),
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

/**
 * The MQTT settings, identical for Health Connect and Screen Time (issue #52). Each section
 * has its own switch and base topic. The broker fields edit the shared connection while
 * "Use the shared broker" is on, and this section's own broker when it is off.
 */
@Composable
fun MqttRow(
    accent: Color,
    mqtt: MqttDraft,
    onChange: (MqttDraft) -> Unit,
    description: String,
    otherSection: String,
    lastStatus: String?,
    expanded: Boolean,
    onToggle: () -> Unit
) {
    val section = mqtt.section
    val broker = mqtt.activeBroker
    ExpandableRow(
        icon = Icons.Outlined.Home,
        accent = accent,
        title = stringResource(R.string.mqtt_title),
        subtitle = when {
            !section.enabled -> stringResource(R.string.mqtt_disabled)
            broker.host.isBlank() -> stringResource(R.string.mqtt_enabled_no_broker)
            section.useSharedBroker -> stringResource(R.string.mqtt_enabled_shared_broker, broker.host)
            else -> stringResource(R.string.mqtt_enabled_own_broker, broker.host)
        },
        subtitleAccent = section.enabled,
        expanded = expanded,
        onToggle = onToggle
    ) {
        Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SwitchLine(stringResource(R.string.mqtt_enable_publishing), null, section.enabled, accent) {
            onChange(mqtt.copy(section = section.copy(enabled = it)))
        }
        SwitchLine(
            stringResource(R.string.mqtt_use_shared_broker),
            if (section.useSharedBroker) stringResource(R.string.mqtt_use_shared_broker_on)
            else stringResource(R.string.mqtt_use_shared_broker_off, otherSection),
            section.useSharedBroker,
            accent
        ) { onChange(mqtt.withShared(it)) }
        FilledField(
            value = broker.host,
            onValueChange = { onChange(mqtt.withActiveBroker(broker.copy(host = it))) },
            accent = accent,
            label = if (section.useSharedBroker) stringResource(R.string.mqtt_broker_host_shared) else stringResource(R.string.mqtt_broker_host),
            placeholder = stringResource(R.string.mqtt_broker_host_placeholder)
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            FilledField(
                value = mqtt.portText,
                onValueChange = { text -> onChange(mqtt.copy(portText = text.filter { it.isDigit() }.take(5))) },
                accent = accent,
                label = stringResource(R.string.mqtt_port),
                keyboardType = KeyboardType.Number,
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(stringResource(R.string.mqtt_tls), style = MaterialTheme.typography.bodyMedium)
            Spacer(modifier = Modifier.width(6.dp))
            Switch(
                checked = broker.useTls,
                onCheckedChange = { onChange(mqtt.withActiveBroker(broker.copy(useTls = it))) },
                colors = SwitchDefaults.colors(checkedTrackColor = accent)
            )
        }
        FilledField(
            value = broker.username ?: "",
            onValueChange = { onChange(mqtt.withActiveBroker(broker.copy(username = it.ifBlank { null }))) },
            accent = accent,
            label = stringResource(R.string.mqtt_username)
        )
        FilledField(
            value = broker.password ?: "",
            onValueChange = { onChange(mqtt.withActiveBroker(broker.copy(password = it.ifBlank { null }))) },
            accent = accent,
            label = stringResource(R.string.mqtt_password),
            password = true
        )
        FilledField(
            value = section.baseTopic,
            onValueChange = { onChange(mqtt.copy(section = section.copy(baseTopic = it))) },
            accent = accent,
            label = stringResource(R.string.mqtt_base_topic)
        )
        lastStatus?.let { status ->
            Text(
                status,
                style = MaterialTheme.typography.bodySmall,
                color = if (status.startsWith("OK")) accent else MaterialTheme.colorScheme.error
            )
        }
    }
}

/** Failure notifications are app-wide; the row appears on both tabs and edits the same setting. */
@Composable
fun NotificationsRow(
    accent: Color,
    enabled: Boolean,
    threshold: Int,
    expanded: Boolean,
    onToggle: () -> Unit,
    onEnabledChange: (Boolean) -> Unit,
    onThresholdChange: (Int) -> Unit
) {
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    ExpandableRow(
        icon = Icons.Outlined.NotificationsNone,
        accent = accent,
        title = stringResource(R.string.health_notifications_title),
        subtitle = if (enabled) pluralStringResource(R.plurals.health_notifications_on, threshold, threshold)
        else stringResource(R.string.health_notifications_off),
        subtitleAccent = enabled,
        expanded = expanded,
        onToggle = onToggle
    ) {
        SwitchLine(stringResource(R.string.health_notifications_notify_after_failed), null, enabled, accent) { on ->
            onEnabledChange(on)
            if (on && android.os.Build.VERSION.SDK_INT >= 33) {
                notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (enabled) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.health_notifications_after_failures),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                listOf(3, 5, 10).forEach { value ->
                    FilterChip(
                        selected = threshold == value,
                        onClick = { onThresholdChange(value) },
                        label = { Text("$value") },
                        modifier = Modifier.padding(start = 6.dp)
                    )
                }
            }
        }
    }
}

/** The 33 Health Connect types with their toggles, folded under one row. */
@Composable
fun DataTypesRow(
    accent: Color,
    enabledTypes: Set<HealthDataType>,
    grantedPermissions: Set<String>,
    expanded: Boolean,
    onToggle: () -> Unit,
    onToggleType: (HealthDataType, Boolean) -> Unit
) {
    val hasAnyPermission = grantedPermissions.isNotEmpty()
    ExpandableRow(
        icon = Icons.Outlined.MonitorHeart,
        accent = accent,
        title = stringResource(R.string.health_data_types_title),
        subtitle = stringResource(R.string.health_data_types_selected_of, enabledTypes.size, HealthDataType.entries.size),
        subtitleAccent = enabledTypes.isNotEmpty(),
        expanded = expanded,
        onToggle = onToggle
    ) {
        HealthDataType.entries.forEach { dataType ->
            val permission = HealthPermission.getReadPermission(dataType.recordClass)
            val granted = permission in grantedPermissions || hasAnyPermission
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .alpha(if (granted) 1f else 0.5f),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (!granted) {
                        Icon(Icons.Filled.Lock, contentDescription = null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(modifier = Modifier.width(6.dp))
                    }
                    Text(dataType.displayName, style = MaterialTheme.typography.bodyMedium)
                }
                Switch(
                    checked = dataType in enabledTypes,
                    onCheckedChange = { onToggleType(dataType, it) },
                    modifier = Modifier.height(24.dp),
                    colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = accent)
                )
            }
        }
    }
}

/** The green "Save changes" button that appears once the draft differs from what is saved. */
@Composable
fun SaveBar(visible: Boolean, onSave: () -> Unit) {
    AnimatedVisibility(visible = visible, enter = expandVertically(), exit = shrinkVertically()) {
        Button(
            onClick = onSave,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape = RoundedCornerShape(26.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Success)
        ) {
            Icon(Icons.Filled.Check, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text(stringResource(R.string.sync_save_changes), fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
fun PreviewDialog(accent: Color, data: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.sync_data_preview_title)) },
        text = {
            Column {
                Text(
                    stringResource(R.string.sync_preview_payload_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 400.dp),
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Text(
                        text = data,
                        modifier = Modifier
                            .padding(12.dp)
                            .verticalScroll(rememberScrollState()),
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_close), color = accent) }
        }
    )
}

@Composable
fun ExportFormatDialog(accent: Color, description: String, onJson: () -> Unit, onCsv: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.sync_export_data)) },
        text = { Text(description, style = MaterialTheme.typography.bodyMedium) },
        confirmButton = { TextButton(onClick = onJson) { Text(stringResource(R.string.common_json), color = accent) } },
        dismissButton = { TextButton(onClick = onCsv) { Text(stringResource(R.string.common_csv), color = accent) } }
    )
}

/** File name for an export share: `prefix_20260914_120000.ext`. */
fun exportFileName(prefix: String, extension: String): String {
    val timestamp = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
    return "${prefix}_$timestamp.$extension"
}

/**
 * Puts a secret on the clipboard the way a secret should go there. From Android 13 the clip is
 * flagged sensitive, so the clipboard overlay shows dots instead of the value, and the system
 * shows its own "copied" confirmation, so a second one from the app would only stack on top.
 */
private fun copySecret(context: Context, secret: String, copiedMessage: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clip = ClipData.newPlainText("secret", secret)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
    }
    clipboard.setPrimaryClip(clip)
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        Toast.makeText(context, copiedMessage, Toast.LENGTH_SHORT).show()
    }
}
