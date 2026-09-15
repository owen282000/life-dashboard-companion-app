package com.owen282000.lifedashboard.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.owen282000.lifedashboard.PairingApply
import com.owen282000.lifedashboard.PairingChoice
import com.owen282000.lifedashboard.PairingLink
import com.owen282000.lifedashboard.PairingSource
import com.owen282000.lifedashboard.R
import com.owen282000.lifedashboard.SectionChange
import com.owen282000.lifedashboard.SectionWebhook

/**
 * The one thing that stands between a scanned code and the app's settings.
 *
 * Whether the link arrived from the phone's camera, from the landing page or from the
 * in-app scanner, it lands here first: this is where the user sees which receiver is
 * asking, at which address, and what pairing would change. Nothing is written until Pair.
 */
@Composable
fun PairingDialog(
    link: PairingLink,
    accent: Color,
    currentHealth: SectionWebhook,
    currentScreenTime: SectionWebhook,
    onDismiss: () -> Unit,
    onConfirm: (PairingChoice) -> Unit
) {
    val offered = PairingApply.offered(link)
    // Default to everything the receiver accepts: that is what the user just scanned for.
    var health by remember { mutableStateOf(PairingSource.HEALTH in offered) }
    var screenTime by remember { mutableStateOf(PairingSource.SCREEN_TIME in offered) }
    // An internal Home Assistant address is http://, and without this the first sync fails
    // with an error the user did not ask for.
    var allowPlainHttp by remember { mutableStateOf(link.isPlainHttp) }

    val healthChange = PairingApply.preview(link, currentHealth)
    val screenTimeChange = PairingApply.preview(link, currentScreenTime)
    val nothingSelected = !health && !screenTime

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.pairing_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    link.name?.let { stringResource(R.string.pairing_intro, it) }
                        ?: stringResource(R.string.pairing_intro_unnamed),
                    style = MaterialTheme.typography.bodyMedium
                )

                // The host, so the user can tell their own machine from someone else's.
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        link.host,
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
                    )
                }

                Text(
                    stringResource(R.string.pairing_sections_label),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (PairingSource.HEALTH in offered) {
                    SwitchLine(
                        title = stringResource(R.string.pairing_section_health),
                        description = sectionNote(healthChange),
                        checked = health,
                        accent = accent,
                        onCheckedChange = { health = it }
                    )
                }
                if (PairingSource.SCREEN_TIME in offered) {
                    SwitchLine(
                        title = stringResource(R.string.pairing_section_screen_time),
                        description = sectionNote(screenTimeChange),
                        checked = screenTime,
                        accent = accent,
                        onCheckedChange = { screenTime = it }
                    )
                }

                if (link.isPlainHttp) {
                    SwitchLine(
                        title = stringResource(R.string.webhook_allow_plain_http),
                        description = stringResource(R.string.webhook_allow_plain_http_description),
                        checked = allowPlainHttp,
                        accent = accent,
                        onCheckedChange = { allowPlainHttp = it }
                    )
                }

                Text(
                    stringResource(R.string.pairing_types_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (nothingSelected) {
                    Text(
                        stringResource(R.string.pairing_nothing_selected),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !nothingSelected,
                onClick = {
                    onConfirm(
                        PairingChoice(
                            health = health,
                            screenTime = screenTime,
                            allowPlainHttp = allowPlainHttp
                        )
                    )
                }
            ) {
                Text(stringResource(R.string.pairing_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        }
    )
}

/** What this section is in for, when there is anything worth saying. */
@Composable
private fun sectionNote(change: SectionChange): String? = when {
    change.changesNothing -> stringResource(R.string.pairing_already_paired)
    change.replacesSecret -> stringResource(R.string.pairing_replaces_secret)
    else -> null
}
