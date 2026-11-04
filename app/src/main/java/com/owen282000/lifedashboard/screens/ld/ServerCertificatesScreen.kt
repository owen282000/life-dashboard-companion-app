package com.owen282000.lifedashboard.screens.ld

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.owen282000.lifedashboard.FailureReason
import com.owen282000.lifedashboard.R
import com.owen282000.lifedashboard.ServerCertificate
import com.owen282000.lifedashboard.ServerTrust
import com.owen282000.lifedashboard.WebhookTrust
import com.owen282000.lifedashboard.ui.ld.LDCard
import com.owen282000.lifedashboard.ui.ld.LDCode
import com.owen282000.lifedashboard.ui.ld.LDGap
import com.owen282000.lifedashboard.ui.ld.LDHeading
import com.owen282000.lifedashboard.ui.ld.LDNote
import com.owen282000.lifedashboard.ui.ld.LDNoteSize
import com.owen282000.lifedashboard.ui.ld.LDRow
import com.owen282000.lifedashboard.ui.ld.LDRowSpinner
import com.owen282000.lifedashboard.ui.ld.LDScreen
import com.owen282000.lifedashboard.ui.ld.LDSectionLabel
import com.owen282000.lifedashboard.ui.ld.LDTheme
import com.owen282000.lifedashboard.ui.ld.LDToggleRow
import com.owen282000.lifedashboard.ui.ld.LDType
import com.owen282000.lifedashboard.ui.ld.ldCodeStyle
import com.owen282000.lifedashboard.ui.ld.ldStaggerRise
import com.owen282000.lifedashboard.ui.ld.rememberLDDraft
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat

/**
 * What each https webhook host is trusted with beyond the system CAs (issue #160): the CA
 * certificates installed on the phone, and one certificate the user confirmed. Both off until
 * switched on, and each for that host alone. There is no switch that accepts any certificate.
 */
@Composable
fun ServerCertificatesScreen() {
    val state by ldHealthViewModel().state.collectAsStateWithLifecycle()
    val shared = ldSharedSettings()
    val hosts = ldTrustableHosts()

    LDScreen(title = stringResource(R.string.sync_advanced_title)) {
        LDHeading(stringResource(R.string.ld_title_server_certificates))
        LDNote(stringResource(R.string.server_trust_description), top = 6.dp)
        if (hosts.isEmpty()) {
            LDGap(4.dp)
            LDNote(stringResource(R.string.ld_certificates_empty))
        }
        hosts.forEachIndexed { index, host ->
            key(host) {
                HostTrust(host, state.serverTrust[host] ?: ServerTrust(), index) { shared.setServerTrust(host, it) }
            }
        }
    }
}

@Composable
private fun HostTrust(hostKey: String, trust: ServerTrust, index: Int, onChange: (ServerTrust) -> Unit) {
    val scope = rememberCoroutineScope()
    var reading by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    var offered by rememberLDDraft<ServerCertificate?> { null }
    val name = WebhookTrust.displayName(hostKey)

    LDSectionLabel(name)
    LDCard(Modifier.ldStaggerRise(index)) {
        LDToggleRow(
            stringResource(R.string.server_trust_user_cas),
            trust.userCas,
            { onChange(trust.copy(userCas = it)) },
            subtitle = stringResource(R.string.server_trust_user_cas_description)
        )
        LDRow(
            stringResource(R.string.server_trust_pin_title),
            subtitle = when {
                reading -> stringResource(R.string.server_trust_reading)
                trust.pinSha256 != null -> stringResource(R.string.server_trust_pinned, trust.pinSha256.take(23))
                else -> stringResource(R.string.server_trust_pin_description)
            },
            value = if (reading) null else stringResource(R.string.server_trust_read),
            showChevron = false,
            enabled = !reading,
            onClick = {
                reading = true
                problem = null
                scope.launch {
                    try {
                        offered = withContext(Dispatchers.IO) { WebhookTrust.fetchCertificate(hostKey) }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        problem = FailureReason.shorten(e.message) ?: e.javaClass.simpleName
                    } finally {
                        reading = false
                    }
                }
            },
            trailing = if (reading) {
                { LDRowSpinner() }
            } else {
                null
            }
        )
        if (trust.pinSha256 != null) {
            LDRow(stringResource(R.string.server_trust_forget), showChevron = false, onClick = { onChange(trust.copy(pinSha256 = null)) })
        }
    }
    problem?.let {
        LDNote(
            stringResource(R.string.server_trust_read_failed, it),
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            color = LDTheme.colors.warning,
            size = LDNoteSize.Small
        )
    }

    offered?.let { certificate ->
        AlertDialog(
            onDismissRequest = { offered = null },
            title = { Text(stringResource(R.string.server_trust_dialog_title)) },
            text = {
                SelectionContainer {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.server_trust_dialog_body, name), style = MaterialTheme.typography.bodyMedium)
                        Text(stringResource(R.string.server_trust_subject, certificate.subject), style = MaterialTheme.typography.bodySmall)
                        Text(
                            if (certificate.selfSigned) {
                                stringResource(R.string.server_trust_self_signed)
                            } else {
                                stringResource(R.string.server_trust_issuer, certificate.issuer)
                            },
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            stringResource(R.string.server_trust_valid_until, DateFormat.getDateInstance(DateFormat.MEDIUM).format(certificate.notAfter)),
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(stringResource(R.string.server_trust_fingerprint), style = MaterialTheme.typography.labelMedium)
                        LDCode { Text(certificate.sha256, style = LDType.monoValue.ldCodeStyle()) }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    onChange(trust.copy(pinSha256 = certificate.sha256))
                    offered = null
                }) { Text(stringResource(R.string.server_trust_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { offered = null }) { Text(stringResource(R.string.common_cancel)) }
            }
        )
    }
}
