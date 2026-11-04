package com.owen282000.lifedashboard.screens.ld

import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.security.KeyChain
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.owen282000.lifedashboard.MqttSupport
import com.owen282000.lifedashboard.R
import com.owen282000.lifedashboard.ui.ld.LDCard
import com.owen282000.lifedashboard.ui.ld.LDFieldRow
import com.owen282000.lifedashboard.ui.ld.LDGap
import com.owen282000.lifedashboard.ui.ld.LDHeading
import com.owen282000.lifedashboard.ui.ld.LDNote
import com.owen282000.lifedashboard.ui.ld.LDNoteSize
import com.owen282000.lifedashboard.ui.ld.LDRoute
import com.owen282000.lifedashboard.ui.ld.LDRow
import com.owen282000.lifedashboard.ui.ld.LDScreen
import com.owen282000.lifedashboard.ui.ld.LDSection
import com.owen282000.lifedashboard.ui.ld.LDSectionLabel
import com.owen282000.lifedashboard.ui.ld.LDTheme
import com.owen282000.lifedashboard.ui.ld.LDToggleRow
import com.owen282000.lifedashboard.ui.ld.LocalLDNavigator
import com.owen282000.lifedashboard.ui.ld.ldStaggerRise

/** Daily totals and record metadata belong to Health only; the rest is the same setting on both tabs. */
@Composable
fun AdvancedScreen(section: LDSection) {
    val viewModel = ldHealthViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val shared = ldSharedSettings()
    val ui = ldSection(section)
    val navigator = LocalLDNavigator.current
    val hosts = ldTrustableHosts()

    LDScreen(title = stringResource(R.string.ld_title_more)) {
        LDHeading(stringResource(R.string.sync_advanced_title))
        LDSectionLabel(stringResource(R.string.logs_payload))
        LDCard(Modifier.ldStaggerRise(0)) {
            if (section == LDSection.Health) {
                LDToggleRow(
                    stringResource(R.string.health_daily_totals_title),
                    state.includeDailyTotals,
                    viewModel::setIncludeDailyTotals,
                    subtitle = stringResource(R.string.health_daily_totals_description)
                )
                LDToggleRow(
                    stringResource(R.string.health_record_metadata_title),
                    state.includeRecordMetadata,
                    viewModel::setIncludeRecordMetadata,
                    subtitle = stringResource(R.string.health_record_metadata_description)
                )
            }
            LDToggleRow(
                stringResource(R.string.logs_keep_full_payloads),
                state.keepFullPayloads,
                viewModel::setKeepFullPayloads,
                subtitle = stringResource(if (state.keepFullPayloads) R.string.logs_keep_full_payloads_on else R.string.logs_keep_full_payloads_off)
            )
            LDToggleRow(
                stringResource(R.string.webhook_allow_plain_http),
                state.allowHttpWebhooks,
                shared::setAllowHttpWebhooks,
                subtitle = stringResource(R.string.webhook_allow_plain_http_description)
            )
        }

        LDSectionLabel(stringResource(R.string.ld_advanced_connection))
        LDCard(Modifier.ldStaggerRise(1)) {
            ClientCertificateRows(state.clientCertAlias, ui.webhook.urls.firstOrNull(), shared::setClientCertAlias)
            LDRow(
                stringResource(R.string.server_trust_title),
                value = if (hosts.isEmpty()) {
                    stringResource(R.string.client_cert_none)
                } else {
                    pluralStringResource(R.plurals.ld_advanced_hosts, hosts.size, hosts.size)
                },
                onClick = { navigator.push(LDRoute.ServerCertificates) }
            )
        }
        LDNote(stringResource(R.string.client_cert_description), size = LDNoteSize.Small)

        LDGap(12.dp)
        PhoneName(state.phoneName.orEmpty(), shared::setPhoneName)
    }
}

/**
 * The client certificate (mTLS), from the Android credential store. The system picker only runs from
 * here. The choice is remembered, and Android keeps this app's access to the certificate, so
 * background syncs use it without asking again.
 */
@Composable
private fun ClientCertificateRows(alias: String?, webhookUrl: String?, onAliasChange: (String?) -> Unit) {
    val activity = LocalActivity.current
    LDRow(
        stringResource(R.string.client_cert_title),
        subtitle = stringResource(R.string.ld_advanced_client_cert_used),
        value = alias ?: stringResource(R.string.client_cert_none),
        onClick = {
            if (activity != null) {
                // A webhook address, so the picker can suggest the certificate installed for that server.
                val uri = webhookUrl?.let(Uri::parse)
                val main = Handler(Looper.getMainLooper())
                KeyChain.choosePrivateKeyAlias(
                    activity,
                    // Called on a binder thread; null means the picker was dismissed, keep the current one.
                    { chosen -> if (chosen != null) main.post { onAliasChange(chosen) } },
                    arrayOf("RSA", "EC"),
                    null,
                    uri?.host,
                    uri?.port ?: -1,
                    alias
                )
            }
        }
    )
    if (alias != null) {
        LDRow(stringResource(R.string.client_cert_clear), showChevron = false, onClick = { onAliasChange(null) })
    }
}

/**
 * The phone's name for MQTT, stored as it is typed: it only takes effect at the next publish, and
 * no secret or address hangs on it. The slug is what goes on the wire, so that is what the line
 * below shows; a name with nothing usable in it would publish nameless, which is said out loud.
 */
@Composable
private fun PhoneName(stored: String, onChange: (String) -> Unit) {
    var name by rememberSaveable { mutableStateOf(stored) }
    val slug = MqttSupport.phoneSlug(name)
    LDCard(Modifier.ldStaggerRise(2)) {
        LDFieldRow(
            stringResource(R.string.phone_name_title),
            name,
            onValueChange = {
                name = it
                onChange(it)
            },
            placeholder = stringResource(R.string.phone_name_placeholder),
            mono = false,
            isCode = false,
            error = if (name.isNotBlank() && slug == null) stringResource(R.string.phone_name_unusable) else null
        )
    }
    if (slug != null) {
        LDNote(stringResource(R.string.phone_name_device_id, MqttSupport.deviceId(slug)), color = LDTheme.colors.accentTextSoft, size = LDNoteSize.Small)
    }
    LDNote(stringResource(R.string.phone_name_description), size = LDNoteSize.Small)
}
