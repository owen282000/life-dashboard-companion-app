package com.owen282000.lifedashboard.screens.ld

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.owen282000.lifedashboard.R
import com.owen282000.lifedashboard.ReceiveSummary
import com.owen282000.lifedashboard.SourceUrlChoice
import com.owen282000.lifedashboard.WriteBackPayload
import com.owen282000.lifedashboard.WriteBackType
import com.owen282000.lifedashboard.ui.ld.LDButton
import com.owen282000.lifedashboard.ui.ld.LDButtonKind
import com.owen282000.lifedashboard.ui.ld.LDCard
import com.owen282000.lifedashboard.ui.ld.LDGap
import com.owen282000.lifedashboard.ui.ld.LDHeading
import com.owen282000.lifedashboard.ui.ld.LDNote
import com.owen282000.lifedashboard.ui.ld.LDNoteSize
import com.owen282000.lifedashboard.ui.ld.LDRow
import com.owen282000.lifedashboard.ui.ld.LDScreen
import com.owen282000.lifedashboard.ui.ld.LDSectionLabel
import com.owen282000.lifedashboard.ui.ld.LDTheme
import com.owen282000.lifedashboard.ui.ld.LDToggleRow
import com.owen282000.lifedashboard.ui.ld.LocalLDPairing
import com.owen282000.lifedashboard.ui.ld.ldStaggerRise
import com.owen282000.lifedashboard.viewmodel.HealthUiState
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Measurements from Home Assistant into Health Connect, on the answer to the app's own POST. It
 * rides on a destination that looks like the integration's and has a signing secret; without one
 * the next step is pairing. Everything here applies at once.
 */
@Composable
fun ReceiveScreen() {
    val viewModel = ldHealthViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val pairing = LocalLDPairing.current
    val receive = state.receive
    val available = state.receiveAvailable
    val active = receive.enabled && receive.sourceUrl != null
    val summary = ReceiveSummary.of(receive, state.receiveStatus, state.grantedPermissions, available)

    LDScreen(title = stringResource(R.string.receive_title)) {
        ReceiveHeader(summary)
        LDAccessBlockedLine(state)
        LDGap(12.dp)
        LDCard(Modifier.ldStaggerRise(0)) {
            LDToggleRow(
                stringResource(R.string.ld_receive_measurements),
                checked = active,
                onCheckedChange = viewModel::setReceiveEnabled,
                subtitle = stringResource(R.string.receive_subtitle)
            )
            if (active && available) {
                val several = WriteBackPayload.sourceUrlChoice(state.saved.webhook.urlsWithSecret) is SourceUrlChoice.Several
                LDRow(
                    stringResource(R.string.ld_receive_source),
                    value = receive.sourceUrl?.let { it.toHttpUrlOrNull()?.host ?: it },
                    showChevron = several,
                    // Asks which one, the same as switching on with several candidates.
                    onClick = if (several) ({ viewModel.setReceiveEnabled(true) }) else null
                )
            }
        }
        // Only without a usable source is pairing the next step; a paired phone just needs the
        // switch. Switched on with the secret or the URL gone since, this explains why nothing arrives.
        if (!available) {
            LDNote(stringResource(R.string.receive_needs_integration))
            LDGap(12.dp)
            LDButton(stringResource(R.string.webhook_scan_lead), kind = LDButtonKind.Ghost) { pairing.startScan() }
        }
        if (active && available) {
            ReceiveTypes(state, onToggle = viewModel::toggleReceiveType)
            LDGap(10.dp)
            LDCard(Modifier.ldStaggerRise(2)) {
                LDToggleRow(
                    stringResource(R.string.receive_older_measurements),
                    checked = receive.olderMeasurements,
                    onCheckedChange = viewModel::setReceiveOlderMeasurements,
                    subtitle = stringResource(R.string.receive_older_measurements_description)
                )
            }
        }
    }
}

/** The heading says the state, the line under it what to do about it. */
@Composable
private fun ReceiveHeader(summary: ReceiveSummary) {
    when (summary) {
        ReceiveSummary.Off -> {
            LDHeading(stringResource(R.string.ld_receive_heading_off))
            LDNote(stringResource(R.string.receive_enable_description), top = 6.dp)
        }
        ReceiveSummary.NeedsPairing -> LDHeading(stringResource(R.string.receive_summary_needs_pairing))
        ReceiveSummary.IntegrationOutdated -> {
            LDHeading(stringResource(R.string.receive_summary_update_integration))
            LDNote(stringResource(R.string.receive_update_integration), color = LDTheme.colors.warningText, top = 6.dp)
        }
        ReceiveSummary.AwaitingTypes -> {
            LDHeading(stringResource(R.string.ld_receive_heading_waiting))
            LDNote(stringResource(R.string.receive_no_types_yet), top = 6.dp)
        }
        ReceiveSummary.NothingMapped -> {
            LDHeading(stringResource(R.string.receive_summary_nothing_mapped))
            LDNote(stringResource(R.string.receive_nothing_mapped), top = 6.dp)
        }
        ReceiveSummary.ChooseType -> {
            LDHeading(stringResource(R.string.ld_receive_heading_choose))
            LDNote(stringResource(R.string.receive_choose_type), top = 6.dp)
        }
        is ReceiveSummary.PermissionMissing -> {
            LDHeading(stringResource(R.string.ld_receive_heading_permission))
            LDNote(stringResource(R.string.receive_permission_missing, summary.types.map { stringResource(it.dataType.displayNameRes) }.joinToString(", ")), top = 6.dp)
        }
        is ReceiveSummary.Receiving -> {
            LDHeading(stringResource(R.string.ld_receive_heading_on))
            LDNote(stringResource(R.string.ld_receive_intro), top = 6.dp)
            if (summary.writtenToday > 0) {
                LDNote(pluralStringResource(R.plurals.receive_written_today, summary.writtenToday, summary.writtenToday), top = 2.dp)
            }
        }
    }
}

/** Only what the integration has a mapping for: a type it does not offer would receive nothing. */
@Composable
private fun ReceiveTypes(state: HealthUiState, onToggle: (WriteBackType, Boolean) -> Unit) {
    val offered = state.receiveStatus.configured.mapNotNull { WriteBackType.fromKey(it) }
    if (offered.isEmpty()) return
    LDSectionLabel(stringResource(R.string.ld_receive_types))
    LDCard(Modifier.ldStaggerRise(1)) {
        offered.forEach { type ->
            val granted = type.writePermission in state.grantedPermissions
            LDToggleRow(
                stringResource(type.dataType.displayNameRes),
                checked = type in state.receive.types && granted,
                onCheckedChange = { onToggle(type, it) },
                locked = !granted
            )
        }
    }
    LDNote(stringResource(R.string.receive_types_chosen_in_ha), size = LDNoteSize.Small)
}
