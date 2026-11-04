package com.owen282000.lifedashboard.screens.ld

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.owen282000.lifedashboard.PairingSource
import com.owen282000.lifedashboard.R
import com.owen282000.lifedashboard.ui.ld.LDButton
import com.owen282000.lifedashboard.ui.ld.LDButtonKind
import com.owen282000.lifedashboard.ui.ld.LDCard
import com.owen282000.lifedashboard.ui.ld.LDCode
import com.owen282000.lifedashboard.ui.ld.LDGap
import com.owen282000.lifedashboard.ui.ld.LDHeading
import com.owen282000.lifedashboard.ui.ld.LDNote
import com.owen282000.lifedashboard.ui.ld.LDPairedPing
import com.owen282000.lifedashboard.ui.ld.LDPulseVariant
import com.owen282000.lifedashboard.ui.ld.LDRoute
import com.owen282000.lifedashboard.ui.ld.LDScreen
import com.owen282000.lifedashboard.ui.ld.LDTheme
import com.owen282000.lifedashboard.ui.ld.LDToggleRow
import com.owen282000.lifedashboard.ui.ld.LDType
import com.owen282000.lifedashboard.ui.ld.LocalLDEntryId
import com.owen282000.lifedashboard.ui.ld.LocalLDNavigator
import com.owen282000.lifedashboard.ui.ld.LocalLDPairing
import com.owen282000.lifedashboard.ui.ld.PulseLine
import com.owen282000.lifedashboard.ui.ld.ldCodeStyle

/**
 * What the one ping after Pair found. The pairing has already been written by then, so a failure here
 * says the receiver did not answer, not that nothing changed. The result is in memory only, because
 * it holds the link's secret: after a process death there is nothing to show, and the page goes.
 */
@Composable
fun PairedResultScreen() {
    val pairing = LocalLDPairing.current
    val navigator = LocalLDNavigator.current
    val page = LocalLDEntryId.current
    val viewModel = ldHealthViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val result = pairing.result
    LaunchedEffect(result == null) {
        if (result == null) navigator.pop(page)
    }
    if (result == null) return

    val ping = result.ping
    val delivered = ping is LDPairedPing.Delivered
    val failed = ping is LDPairedPing.Failed
    val colors = LDTheme.colors
    // Types are a Health question: a pairing that wrote only Screen Time has no next step here.
    val healthPaired = PairingSource.HEALTH in result.written
    val typesPicked = state.saved.enabledTypes.isNotEmpty()
    val showsPick = healthPaired && !failed && !typesPicked && ping !is LDPairedPing.Sending

    LDScreen(title = stringResource(R.string.ld_title_paired)) {
        LDHeading(
            stringResource(
                when {
                    delivered -> R.string.ld_paired_connected
                    failed -> R.string.ld_paired_not_connected
                    else -> R.string.ld_title_paired
                }
            )
        )
        PulseLine(
            variant = when {
                delivered -> LDPulseVariant.Normal
                failed -> LDPulseVariant.Warning
                else -> LDPulseVariant.Flat
            }
        )
        LDCode {
            Text(
                result.link.host,
                style = LDType.monoField.ldCodeStyle(),
                color = colors.text,
                modifier = Modifier.fillMaxWidth().padding(horizontal = LDTheme.textInset)
            )
        }
        when (ping) {
            LDPairedPing.Sending -> Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = LDTheme.textInset, end = LDTheme.textInset, top = 4.dp)
                    .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CircularProgressIndicator(Modifier.size(16.dp), color = colors.accentTextSoft, strokeWidth = 2.dp)
                Text(stringResource(R.string.ld_paired_checking), style = LDType.note, color = colors.secondary)
            }
            LDPairedPing.Delivered -> LDNote(
                stringResource(R.string.ld_paired_delivered),
                Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                color = colors.accentTextSoft,
                top = 4.dp
            )
            is LDPairedPing.Failed -> LDNote(
                stringResource(R.string.health_test_ping_failed_with_reason, ping.reason),
                Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                color = colors.warning,
                top = 4.dp
            )
        }
        // Not while it failed: the next step is then Try again.
        if (healthPaired && !failed) {
            LDNote(stringResource(if (typesPicked) R.string.ld_paired_next_sync else R.string.ld_paired_next_types))
        }

        LDGap(16.dp)
        // Plain HTTP off is the one failure this page can cure itself, so the switch comes here.
        if (failed && result.link.isPlainHttp && !state.allowHttpWebhooks) {
            LDCard(bottom = 12.dp) {
                LDToggleRow(
                    stringResource(R.string.webhook_allow_plain_http),
                    checked = false,
                    onCheckedChange = viewModel::setAllowHttpWebhooks,
                    subtitle = stringResource(R.string.webhook_allow_plain_http_description)
                )
            }
        }
        if (failed) {
            LDButton(stringResource(R.string.ld_try_again), onClick = pairing::tryAgain)
            LDGap(8.dp)
        }
        if (showsPick) {
            LDButton(stringResource(R.string.ld_today_pick)) { navigator.replaceTop(LDRoute.WhatToSend) }
            LDGap(8.dp)
        }
        LDButton(
            stringResource(R.string.ld_paired_done),
            kind = if (delivered && !showsPick) LDButtonKind.Primary else LDButtonKind.Ghost,
            enabled = ping !is LDPairedPing.Sending
        ) { navigator.pop(page) }
    }
}
