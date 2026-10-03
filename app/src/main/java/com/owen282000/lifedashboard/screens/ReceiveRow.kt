package com.owen282000.lifedashboard.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.owen282000.lifedashboard.R
import com.owen282000.lifedashboard.ReceiveSettings
import com.owen282000.lifedashboard.ReceiveStatus
import com.owen282000.lifedashboard.ReceiveSummary
import com.owen282000.lifedashboard.WriteBackType
import com.owen282000.lifedashboard.ui.theme.ink

/**
 * Receive (issue #62): measurements from Home Assistant into Health Connect. Sits under the
 * Webhook row because it rides on that webhook: the integration answers the app's own POST.
 * The switches apply at once; the types offered are the ones the integration reported in
 * its last answer, each with a lock while its write permission is missing.
 */
@Composable
fun ReceiveRow(
    accent: Color,
    receive: ReceiveSettings,
    status: ReceiveStatus,
    grantedPermissions: Set<String>,
    /** A secret and an integration URL are saved, so switching on works; see HealthUiState.receiveAvailable. */
    available: Boolean,
    expanded: Boolean,
    onToggle: () -> Unit,
    onEnabledChange: (Boolean) -> Unit,
    onToggleType: (WriteBackType, Boolean) -> Unit,
    onOlderChange: (Boolean) -> Unit,
    onScanRequested: () -> Unit
) {
    val active = receive.enabled && receive.sourceUrl != null
    val summary = ReceiveSummary.of(receive, status, grantedPermissions, available)
    val subtitle = when (summary) {
        ReceiveSummary.Off -> stringResource(R.string.receive_off)
        ReceiveSummary.NeedsPairing -> stringResource(R.string.receive_summary_needs_pairing)
        ReceiveSummary.IntegrationOutdated -> stringResource(R.string.receive_summary_update_integration)
        ReceiveSummary.AwaitingTypes -> stringResource(R.string.receive_no_types_yet)
        ReceiveSummary.NothingMapped -> stringResource(R.string.receive_summary_nothing_mapped)
        ReceiveSummary.ChooseType -> stringResource(R.string.receive_choose_type)
        is ReceiveSummary.PermissionMissing -> stringResource(
            R.string.receive_permission_missing,
            summary.types.map { stringResource(it.dataType.displayNameRes) }.joinToString(", ")
        )
        is ReceiveSummary.Receiving -> {
            val types = summary.types.map { stringResource(it.dataType.displayNameRes) }.joinToString(", ")
            if (summary.writtenToday > 0) {
                stringResource(
                    R.string.receive_summary_written,
                    types,
                    pluralStringResource(R.plurals.receive_written_today, summary.writtenToday, summary.writtenToday)
                )
            } else {
                types
            }
        }
    }
    ExpandableRow(
        icon = Icons.Outlined.Download,
        accent = accent,
        title = stringResource(R.string.receive_title),
        subtitle = subtitle,
        // The accent is for measurements actually arriving; every other state is a note.
        subtitleAccent = summary is ReceiveSummary.Receiving,
        expanded = expanded,
        onToggle = onToggle
    ) {
        Text(
            stringResource(R.string.receive_subtitle),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        SwitchLine(
            stringResource(R.string.receive_enable),
            stringResource(R.string.receive_enable_description),
            active,
            accent,
            onEnabledChange
        )
        if (!active || !available) {
            // Only without a usable source is pairing the next step; a paired phone just
            // needs the switch above. Switched on with the secret or the URL gone since,
            // the same prompt explains why nothing arrives.
            if (!available) {
                Text(
                    stringResource(R.string.receive_needs_integration),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                TextButton(onClick = onScanRequested) {
                    Text(stringResource(R.string.webhook_scan_lead), color = accent.ink())
                }
            }
            return@ExpandableRow
        }

        Text(
            stringResource(R.string.receive_source, receive.sourceUrl.orEmpty()),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (status.integrationOutdated) {
            Text(
                stringResource(R.string.receive_update_integration),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }

        // Only what the integration has a mapping for; a type it does not offer has no
        // switch here, since switching it on would receive nothing.
        val offered = status.configured.mapNotNull { WriteBackType.fromKey(it) }
        if (offered.isEmpty()) {
            Text(
                stringResource(if (status.answered) R.string.receive_nothing_mapped else R.string.receive_no_types_yet),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        offered.forEach { type ->
            val granted = type.writePermission in grantedPermissions
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .alpha(if (granted || type in receive.types) 1f else 0.6f),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (!granted) {
                        Icon(
                            Icons.Filled.Lock,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                    }
                    Text(stringResource(type.dataType.displayNameRes), style = MaterialTheme.typography.bodyMedium)
                }
                Switch(
                    checked = type in receive.types && granted,
                    onCheckedChange = { onToggleType(type, it) },
                    modifier = Modifier.height(24.dp),
                    colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = accent)
                )
            }
        }
        Text(
            stringResource(R.string.receive_types_chosen_in_ha),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        SwitchLine(
            stringResource(R.string.receive_older_measurements),
            stringResource(R.string.receive_older_measurements_description),
            receive.olderMeasurements,
            accent,
            onOlderChange
        )
    }
}
