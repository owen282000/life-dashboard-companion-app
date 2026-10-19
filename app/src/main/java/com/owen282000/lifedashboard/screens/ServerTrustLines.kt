package com.owen282000.lifedashboard.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.owen282000.lifedashboard.FailureReason
import com.owen282000.lifedashboard.R
import com.owen282000.lifedashboard.ServerCertificate
import com.owen282000.lifedashboard.ServerTrust
import com.owen282000.lifedashboard.WebhookTrust
import com.owen282000.lifedashboard.ui.theme.ink
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
fun ServerTrustLines(
    urls: List<String>,
    trust: Map<String, ServerTrust>,
    accent: Color,
    onChange: (hostKey: String, trust: ServerTrust) -> Unit
) {
    val hosts = WebhookTrust.hostKeys(urls)
    if (hosts.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.server_trust_title), style = MaterialTheme.typography.bodyMedium)
        Text(
            stringResource(R.string.server_trust_description),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        hosts.forEach { host -> HostTrust(host, trust[host] ?: ServerTrust(), accent) { onChange(host, it) } }
    }
}

@Composable
private fun HostTrust(hostKey: String, trust: ServerTrust, accent: Color, onChange: (ServerTrust) -> Unit) {
    val scope = rememberCoroutineScope()
    var reading by remember(hostKey) { mutableStateOf(false) }
    var problem by remember(hostKey) { mutableStateOf<String?>(null) }
    var offered by remember(hostKey) { mutableStateOf<ServerCertificate?>(null) }
    val name = WebhookTrust.displayName(hostKey)

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(name, style = MaterialTheme.typography.labelLarge, color = accent.ink())
        SwitchLine(
            stringResource(R.string.server_trust_user_cas),
            stringResource(R.string.server_trust_user_cas_description),
            trust.userCas,
            accent
        ) { onChange(trust.copy(userCas = it)) }
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.server_trust_pin_title), style = MaterialTheme.typography.bodyMedium)
                val status = when {
                    reading -> stringResource(R.string.server_trust_reading)
                    problem != null -> stringResource(R.string.server_trust_read_failed, problem!!)
                    trust.pinSha256 != null -> stringResource(R.string.server_trust_pinned, trust.pinSha256.take(23))
                    else -> stringResource(R.string.server_trust_pin_description)
                }
                Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (trust.pinSha256 != null) {
                TextButton(onClick = { onChange(trust.copy(pinSha256 = null)) }) {
                    Text(stringResource(R.string.server_trust_forget), color = accent.ink())
                }
            }
            TextButton(enabled = !reading, onClick = {
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
            }) {
                Text(stringResource(R.string.server_trust_read), color = accent.ink())
            }
        }
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
                            if (certificate.selfSigned) stringResource(R.string.server_trust_self_signed)
                            else stringResource(R.string.server_trust_issuer, certificate.issuer),
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            stringResource(R.string.server_trust_valid_until, DateFormat.getDateInstance(DateFormat.MEDIUM).format(certificate.notAfter)),
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(stringResource(R.string.server_trust_fingerprint), style = MaterialTheme.typography.labelMedium)
                        Text(certificate.sha256, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    onChange(trust.copy(pinSha256 = certificate.sha256))
                    offered = null
                }) { Text(stringResource(R.string.server_trust_confirm), color = accent.ink()) }
            },
            dismissButton = {
                TextButton(onClick = { offered = null }) { Text(stringResource(R.string.common_cancel)) }
            }
        )
    }
}
