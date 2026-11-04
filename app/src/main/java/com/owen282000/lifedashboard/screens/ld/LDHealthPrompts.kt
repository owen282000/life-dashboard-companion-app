package com.owen282000.lifedashboard.screens.ld

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.owen282000.lifedashboard.R
import com.owen282000.lifedashboard.ui.ld.LDNoticeLine
import com.owen282000.lifedashboard.ui.ld.LDNoticeTone
import com.owen282000.lifedashboard.viewmodel.HcAvailability
import com.owen282000.lifedashboard.viewmodel.HealthActions
import com.owen282000.lifedashboard.viewmodel.HealthUiState

/**
 * What the Health view model asks before a switch goes on: a read or write permission, and which of
 * several webhooks is Home Assistant. The shell shows this once, over whichever page raised it: a
 * page leaves the composition when another covers it, so a page could not be relied on for it.
 */
@Composable
fun LDHealthPrompts(state: HealthUiState, actions: HealthActions) {
    state.permissionPrompt?.let { type ->
        AlertDialog(
            onDismissRequest = actions::dismissPermissionPrompt,
            title = { Text(stringResource(R.string.health_permission_required_title)) },
            text = { Text(stringResource(R.string.health_permission_needed, stringResource(type.displayNameRes))) },
            confirmButton = { TextButton(onClick = actions::requestTypePermission) { Text(stringResource(R.string.common_grant)) } },
            dismissButton = { TextButton(onClick = actions::dismissPermissionPrompt) { Text(stringResource(R.string.common_cancel)) } }
        )
    }
    state.receivePermissionPrompt?.let { type ->
        AlertDialog(
            onDismissRequest = actions::dismissReceivePermissionPrompt,
            title = { Text(stringResource(R.string.health_permission_required_title)) },
            text = { Text(stringResource(R.string.receive_permission_needed, stringResource(type.dataType.displayNameRes))) },
            confirmButton = { TextButton(onClick = actions::requestReceivePermission) { Text(stringResource(R.string.common_grant)) } },
            dismissButton = { TextButton(onClick = actions::dismissReceivePermissionPrompt) { Text(stringResource(R.string.common_cancel)) } }
        )
    }
    state.receiveSourceChoice?.let { urls ->
        AlertDialog(
            onDismissRequest = actions::dismissReceiveSourceChoice,
            title = { Text(stringResource(R.string.receive_choose_source_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.receive_choose_source_body))
                    urls.forEach { url ->
                        TextButton(onClick = { actions.chooseReceiveSource(url) }) { Text(url) }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = actions::dismissReceiveSourceChoice) { Text(stringResource(R.string.common_cancel)) } }
        )
    }
}

/** Why a switch or a shortcut did nothing, right where it was tapped: there is no Health Connect to ask. */
@Composable
fun LDAccessBlockedLine(state: HealthUiState) {
    val text = when (state.accessBlocked) {
        null, HcAvailability.AVAILABLE -> return
        HcAvailability.NOT_INSTALLED -> R.string.ld_access_blocked_not_installed
        HcAvailability.NEEDS_UPDATE -> R.string.ld_access_blocked_needs_update
        HcAvailability.UNAVAILABLE -> R.string.ld_access_blocked_unavailable
    }
    LDNoticeLine(stringResource(text), LDNoticeTone.Warning)
}
