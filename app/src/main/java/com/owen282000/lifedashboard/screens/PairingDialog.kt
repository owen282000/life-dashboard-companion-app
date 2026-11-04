package com.owen282000.lifedashboard.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.owen282000.lifedashboard.Destination
import com.owen282000.lifedashboard.PairingApply
import com.owen282000.lifedashboard.PairingChoice
import com.owen282000.lifedashboard.PairingLink
import com.owen282000.lifedashboard.PairingSource
import com.owen282000.lifedashboard.PartialDelivery
import com.owen282000.lifedashboard.R
import com.owen282000.lifedashboard.SectionChange
import com.owen282000.lifedashboard.ui.ld.LDButton
import com.owen282000.lifedashboard.ui.ld.LDButtonKind
import com.owen282000.lifedashboard.ui.ld.LDCard
import com.owen282000.lifedashboard.ui.ld.LDCode
import com.owen282000.lifedashboard.ui.ld.LDGap
import com.owen282000.lifedashboard.ui.ld.LDHeading
import com.owen282000.lifedashboard.ui.ld.LDNote
import com.owen282000.lifedashboard.ui.ld.LDNoteSize
import com.owen282000.lifedashboard.ui.ld.LDScreen
import com.owen282000.lifedashboard.ui.ld.LDSectionLabel
import com.owen282000.lifedashboard.ui.ld.LDTheme
import com.owen282000.lifedashboard.ui.ld.LDToggleRow
import com.owen282000.lifedashboard.ui.ld.LDType
import com.owen282000.lifedashboard.ui.ld.ldCodeStyle

/**
 * The one thing that stands between a scanned code and the app's settings.
 *
 * Whether the link arrived from the phone's camera, from the landing page or from the
 * in-app scanner, it lands here first: this is where the user sees which receiver is
 * asking, at which address, and what pairing would change. Nothing is written until Pair.
 * An overlay over the tabs and the wizard, owned by MainActivity.
 */
@Composable
fun PairingDialog(
    link: PairingLink,
    currentHealth: List<Destination>,
    currentScreenTime: List<Destination>,
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
    val colors = LDTheme.colors

    BackHandler(onBack = onDismiss)

    // A Surface, which takes the touches and hides what is under it from TalkBack.
    Surface(modifier = Modifier.fillMaxSize(), color = colors.ground) {
        LDScreen(title = stringResource(R.string.ld_pairing_title), onBack = onDismiss) {
            // A question without the link's name in it: the name reads as a claim, never as a title.
            LDHeading(stringResource(R.string.ld_pairing_heading))
            LDNote(
                link.name?.let { stringResource(R.string.pairing_intro, it) }
                    ?: stringResource(R.string.pairing_intro_unnamed),
                top = 10.dp
            )
            // The host, so the user can tell their own machine from someone else's.
            LDCode {
                Text(
                    link.host,
                    style = LDType.monoField.ldCodeStyle(),
                    color = colors.accentTextSoft,
                    modifier = Modifier.fillMaxWidth().padding(start = LDTheme.textInset, end = LDTheme.textInset, top = 4.dp)
                )
            }
            LDCode {
                Text(
                    link.url,
                    style = LDType.monoValue.ldCodeStyle(),
                    color = colors.muted,
                    maxLines = 1,
                    overflow = TextOverflow.MiddleEllipsis,
                    modifier = Modifier.fillMaxWidth().padding(start = LDTheme.textInset, end = LDTheme.textInset, top = 2.dp)
                )
            }

            LDSectionLabel(stringResource(R.string.pairing_sections_label))
            LDCard {
                if (PairingSource.HEALTH in offered) {
                    LDToggleRow(
                        stringResource(R.string.pairing_section_health),
                        health,
                        { health = it },
                        subtitle = sectionNote(healthChange)
                    )
                }
                if (PairingSource.SCREEN_TIME in offered) {
                    LDToggleRow(
                        stringResource(R.string.pairing_section_screen_time),
                        screenTime,
                        { screenTime = it },
                        subtitle = sectionNote(screenTimeChange)
                    )
                }
                if (link.isPlainHttp) {
                    LDToggleRow(
                        stringResource(R.string.webhook_allow_plain_http),
                        allowPlainHttp,
                        { allowPlainHttp = it },
                        subtitle = stringResource(R.string.webhook_allow_plain_http_description)
                    )
                }
            }
            if (nothingSelected) {
                LDNote(stringResource(R.string.pairing_nothing_selected), color = colors.warning)
            }
            LDNote(stringResource(R.string.pairing_types_note), size = LDNoteSize.Small)

            LDGap(16.dp)
            LDButton(stringResource(R.string.pairing_confirm), enabled = !nothingSelected) {
                onConfirm(PairingChoice(health = health, screenTime = screenTime, allowPlainHttp = allowPlainHttp))
            }
            LDGap(8.dp)
            LDButton(stringResource(R.string.common_cancel), kind = LDButtonKind.Ghost, onClick = onDismiss)
        }
    }
}

/** What this section is in for, when there is anything worth saying. */
@Composable
private fun sectionNote(change: SectionChange): String? {
    if (change.changesNothing) return stringResource(R.string.pairing_already_paired)
    val replaces = if (change.replacesSecret) stringResource(R.string.pairing_replaces_secret) else null
    // Said because 1.27.0 and older did re-sign them, and someone who paired before may expect it.
    val others = if (change.othersKeepTheirOwn.isNotEmpty()) {
        stringResource(R.string.pairing_others_keep_secret, PartialDelivery.hosts(change.othersKeepTheirOwn))
    } else {
        null
    }
    return listOfNotNull(replaces, others).joinToString(" ").ifEmpty { null }
}
