package com.owen282000.lifedashboard.screens.ld

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.owen282000.lifedashboard.MqttSupport
import com.owen282000.lifedashboard.R
import com.owen282000.lifedashboard.WebhookTrust
import com.owen282000.lifedashboard.ui.ld.LDCard
import com.owen282000.lifedashboard.ui.ld.LDGap
import com.owen282000.lifedashboard.ui.ld.LDHeading
import com.owen282000.lifedashboard.ui.ld.LDRoute
import com.owen282000.lifedashboard.ui.ld.LDRow
import com.owen282000.lifedashboard.ui.ld.LDScreen
import com.owen282000.lifedashboard.ui.ld.LDSection
import com.owen282000.lifedashboard.ui.ld.LocalLDNavigator
import com.owen282000.lifedashboard.ui.ld.ldLeftToRight
import com.owen282000.lifedashboard.ui.ld.ldStaggerRise
import com.owen282000.lifedashboard.viewmodel.ScreenTimeUiState

@Composable
fun ScreenTimeMoreScreen() {
    val viewModel = ldScreenTimeViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val navigator = LocalLDNavigator.current
    val context = LocalContext.current
    val version = remember(context) {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty()
    }

    LDScreen(title = stringResource(R.string.main_title_screen_time)) {
        LDHeading(stringResource(R.string.ld_title_more))
        LDGap(12.dp)
        LDCard(Modifier.ldStaggerRise(0)) {
            // Both read the phone's usage, so without access they have nothing to show.
            LDRow(
                stringResource(R.string.ld_screen_preview_payload),
                enabled = state.hasUsageAccess,
                onClick = { navigator.push(LDRoute.PayloadPreview(LDSection.ScreenTime)) }
            )
            LDRow(
                stringResource(R.string.ld_title_export_data),
                value = listOf(stringResource(R.string.common_csv), stringResource(R.string.common_json)).joinToString(", "),
                enabled = state.hasUsageAccess,
                onClick = { navigator.push(LDRoute.ExportData(LDSection.ScreenTime)) }
            )
        }
        LDGap(10.dp)
        LDCard(Modifier.ldStaggerRise(1)) {
            LDRow(
                stringResource(R.string.sync_advanced_title),
                value = screenTimeAdvancedSummary(state),
                onClick = { navigator.push(LDRoute.Advanced(LDSection.ScreenTime)) }
            )
            LDRow(
                stringResource(R.string.health_notifications_title),
                value = if (state.failureNotificationsEnabled) {
                    pluralStringResource(R.plurals.health_notifications_on, state.failureThreshold, state.failureThreshold)
                } else {
                    stringResource(R.string.health_notifications_off)
                },
                onClick = { navigator.push(LDRoute.NotificationSettings) }
            )
        }
        LDGap(10.dp)
        LDCard(Modifier.ldStaggerRise(2)) {
            LDRow(
                stringResource(if (state.hasUsageAccess) R.string.screentime_access_granted else R.string.screentime_access_needed),
                value = stringResource(if (state.hasUsageAccess) R.string.screentime_settings else R.string.screentime_allow),
                onClick = viewModel::requestUsageAccess
            )
        }
        LDGap(10.dp)
        LDCard(Modifier.ldStaggerRise(3)) {
            LDRow(
                stringResource(R.string.main_about),
                value = version.ldLeftToRight,
                onClick = { navigator.push(LDRoute.AboutHome) }
            )
        }
    }
}

@Composable
private fun screenTimeAdvancedSummary(state: ScreenTimeUiState): String = listOfNotNull(
    stringResource(if (state.allowHttpWebhooks) R.string.sync_advanced_plain_http_allowed else R.string.sync_advanced_https_only),
    if (state.clientCertAlias != null) stringResource(R.string.sync_advanced_client_cert) else null,
    if (WebhookTrust.hostKeys(state.saved.webhook.urls).any { it in state.serverTrust }) stringResource(R.string.sync_advanced_server_trust) else null,
    MqttSupport.phoneSlug(state.phoneName)?.let { stringResource(R.string.sync_advanced_phone_name, it) }
).joinToString(", ")
