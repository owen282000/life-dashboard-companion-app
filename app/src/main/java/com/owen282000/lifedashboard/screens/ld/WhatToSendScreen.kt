package com.owen282000.lifedashboard.screens.ld

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.permission.HealthPermission
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.owen282000.lifedashboard.HealthDataType
import com.owen282000.lifedashboard.OnboardingSupport
import com.owen282000.lifedashboard.R
import com.owen282000.lifedashboard.ResolutionFamily
import com.owen282000.lifedashboard.SeriesResolution
import com.owen282000.lifedashboard.screens.text
import com.owen282000.lifedashboard.ui.ld.LDBackfillStage
import com.owen282000.lifedashboard.ui.ld.LDCard
import com.owen282000.lifedashboard.ui.ld.LDChip
import com.owen282000.lifedashboard.ui.ld.LDChips
import com.owen282000.lifedashboard.ui.ld.LDGap
import com.owen282000.lifedashboard.ui.ld.LDHeading
import com.owen282000.lifedashboard.ui.ld.LDNote
import com.owen282000.lifedashboard.ui.ld.LDRoute
import com.owen282000.lifedashboard.ui.ld.LDRow
import com.owen282000.lifedashboard.ui.ld.LDScreen
import com.owen282000.lifedashboard.ui.ld.LDSectionLabel
import com.owen282000.lifedashboard.ui.ld.LDTheme
import com.owen282000.lifedashboard.ui.ld.LDTypeCategory
import com.owen282000.lifedashboard.ui.ld.LocalLDNavigator
import com.owen282000.lifedashboard.ui.ld.ldStaggerRise
import com.owen282000.lifedashboard.viewmodel.HcAvailability
import com.owen282000.lifedashboard.viewmodel.HealthConnectViewModel
import com.owen282000.lifedashboard.viewmodel.HealthUiState
import com.owen282000.lifedashboard.viewmodel.UiMessage

private const val HEALTH_CONNECT_PACKAGE = "com.google.android.apps.healthdata"
private const val HEALTH_CONNECT_STORE_URL = "https://play.google.com/store/apps/details?id=$HEALTH_CONNECT_PACKAGE"

/**
 * Which types go out and how: the shortcuts, the categories, resolution, history and preview, and
 * Health Connect access. The chips only set the switches, so none is shown as selected unless the
 * selection is exactly that shortcut.
 */
@Composable
fun WhatToSendScreen() {
    val viewModel = ldHealthViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val navigator = LocalLDNavigator.current
    val resources = LocalResources.current
    val enabled = state.saved.enabledTypes
    LDScreen(title = stringResource(R.string.ld_title_what_to_send)) {
        LDHeading(stringResource(R.string.ld_what_heading, enabled.size, HealthDataType.entries.size))
        LDSectionLabel(stringResource(R.string.ld_what_start_from))
        LDChips {
            val everything = HealthDataType.entries.toSet()
            LDChip(stringResource(R.string.ld_what_essentials), selected = enabled == OnboardingSupport.ESSENTIAL_TYPES) {
                viewModel.applyTypes(OnboardingSupport.ESSENTIAL_TYPES)
            }
            LDChip(stringResource(R.string.ld_what_all_types), selected = enabled == everything) {
                viewModel.applyTypes(everything)
            }
            LDChip(stringResource(R.string.ld_what_none), selected = enabled.isEmpty()) {
                viewModel.applyTypes(emptySet())
            }
        }
        LDAccessBlockedLine(state)
        LDSectionLabel(stringResource(R.string.ld_what_by_category))
        LDCard(Modifier.ldStaggerRise(0)) {
            LDTypeCategory.entries.forEach { category ->
                LDRow(
                    stringResource(category.title),
                    value = stringResource(R.string.ld_what_count_of, category.types.count { it in enabled }, category.types.size),
                    onClick = { navigator.push(LDRoute.TypeCategory(category)) }
                )
            }
        }
        LDSectionLabel(stringResource(R.string.ld_what_how_it_goes_out))
        LDCard(Modifier.ldStaggerRise(1)) {
            val bucketed = ResolutionFamily.configurableTypes.count {
                it in enabled && (state.saved.resolutions[it] ?: SeriesResolution.RAW) != SeriesResolution.RAW
            }
            LDRow(
                stringResource(R.string.resolution_title),
                value = if (bucketed == 0) stringResource(R.string.resolution_subtitle_raw) else stringResource(R.string.resolution_subtitle_count, bucketed),
                onClick = { navigator.push(LDRoute.DataResolution) }
            )
            SendHistoryRow(state)
            LDRow(
                stringResource(R.string.ld_what_preview_payload),
                enabled = enabled.isNotEmpty(),
                onClick = { navigator.push(LDRoute.PayloadPreview()) }
            )
        }
        (state.syncMessage as? UiMessage.BackfillComplete)?.let {
            LDNote(it.text(resources), color = LDTheme.colors.accentTextSoft)
        }
        LDGap(10.dp)
        LDCard(Modifier.ldStaggerRise(2)) {
            HealthConnectAccessRow(state, viewModel)
        }
    }
}

/** A backfill under way or stopped on a failure continues on its own page. */
@Composable
private fun SendHistoryRow(state: HealthUiState) {
    val navigator = LocalLDNavigator.current
    val failed = state.syncMessage is UiMessage.BackfillFailed
    val progress = state.backfillProgress
    LDRow(
        stringResource(R.string.ld_what_send_history),
        value = when {
            progress != null && state.backfillWaiting -> stringResource(R.string.ld_backfill_short_waiting)
            progress != null && state.backfillPaused -> stringResource(R.string.ld_backfill_short_paused, progress.first, progress.second)
            progress != null -> stringResource(R.string.health_backfill_progress, progress.first, progress.second)
            failed -> stringResource(R.string.logs_failed)
            else -> stringResource(R.string.sync_action_backfill)
        },
        valueColor = if (failed && progress == null) LDTheme.colors.warning else LDTheme.colors.muted,
        onClick = {
            navigator.push(LDRoute.Backfill(if (progress != null || failed) LDBackfillStage.Running else LDBackfillStage.Choose))
        }
    )
}

/** Every state of Health Connect, with what can be done about it from here. */
@Composable
private fun HealthConnectAccessRow(state: HealthUiState, viewModel: HealthConnectViewModel) {
    val context = LocalContext.current
    val title = stringResource(R.string.ld_what_access)
    val soft = LDTheme.colors.accentTextSoft
    when {
        state.availability == HcAvailability.NOT_INSTALLED -> LDRow(
            title,
            subtitle = stringResource(R.string.health_connect_not_installed),
            value = stringResource(R.string.health_install),
            valueColor = soft,
            onClick = { openHealthConnectInStore(context) }
        )
        state.availability == HcAvailability.NEEDS_UPDATE -> LDRow(
            title,
            subtitle = stringResource(R.string.health_connect_needs_update),
            value = stringResource(R.string.health_update),
            valueColor = soft,
            onClick = { openHealthConnectInStore(context) }
        )
        // Android 8 or a work profile: the Play Store cannot help there.
        state.availability == HcAvailability.UNAVAILABLE -> LDRow(
            title,
            subtitle = stringResource(R.string.health_connect_not_available),
            showChevron = false
        )
        // Not read yet: nothing to say rather than a Grant that goes away a moment later.
        state.hasPermissions == null -> LDRow(title, showChevron = false)
        // Allowed in Health Connect, and nothing selected yet: "what you selected" would say nothing.
        state.hasPermissions == true && state.saved.enabledTypes.isEmpty() -> LDRow(
            title,
            subtitle = stringResource(
                R.string.ld_what_access_allowed_count,
                HealthDataType.entries.count { HealthPermission.getReadPermission(it.recordClass) in state.grantedPermissions },
                HealthDataType.entries.size
            ),
            value = stringResource(R.string.health_open_app),
            onClick = { openHealthConnect(context) }
        )
        state.hasPermissions == false || state.saved.enabledTypes.any { HealthPermission.getReadPermission(it.recordClass) !in state.grantedPermissions } -> LDRow(
            title,
            subtitle = stringResource(R.string.health_access_not_granted),
            value = stringResource(R.string.common_grant),
            valueColor = soft,
            onClick = viewModel::requestAccess
        )
        else -> LDRow(
            title,
            subtitle = stringResource(R.string.ld_what_access_granted),
            value = stringResource(R.string.health_open_app),
            onClick = { openHealthConnect(context) }
        )
    }
}

private fun openHealthConnect(context: Context) {
    try {
        val intent = context.packageManager.getLaunchIntentForPackage(HEALTH_CONNECT_PACKAGE)
        if (intent != null) {
            context.startActivity(intent)
        } else {
            try {
                context.startActivity(Intent("android.health.connect.action.HEALTH_HOME_SETTINGS"))
            } catch (e: ActivityNotFoundException) {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(HEALTH_CONNECT_STORE_URL)))
            }
        }
    } catch (e: Exception) {
        Toast.makeText(context, context.getString(R.string.health_could_not_open), Toast.LENGTH_SHORT).show()
    }
}

/** A phone without the Play Store and without a browser has nothing to open the link. */
private fun openHealthConnectInStore(context: Context) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(HEALTH_CONNECT_STORE_URL)))
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, context.getString(R.string.health_could_not_open), Toast.LENGTH_SHORT).show()
    }
}
