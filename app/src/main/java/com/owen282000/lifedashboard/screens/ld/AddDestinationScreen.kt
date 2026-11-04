package com.owen282000.lifedashboard.screens.ld

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.owen282000.lifedashboard.R
import com.owen282000.lifedashboard.screens.text
import com.owen282000.lifedashboard.ui.ld.LDBanner
import com.owen282000.lifedashboard.ui.ld.LDBannerAction
import com.owen282000.lifedashboard.ui.ld.LDButton
import com.owen282000.lifedashboard.ui.ld.LDButtonKind
import com.owen282000.lifedashboard.ui.ld.LDCard
import com.owen282000.lifedashboard.ui.ld.LDFieldRow
import com.owen282000.lifedashboard.ui.ld.LDGap
import com.owen282000.lifedashboard.ui.ld.LDHeading
import com.owen282000.lifedashboard.ui.ld.LDNote
import com.owen282000.lifedashboard.ui.ld.LDRoute
import com.owen282000.lifedashboard.ui.ld.LDRow
import com.owen282000.lifedashboard.ui.ld.LDScreen
import com.owen282000.lifedashboard.ui.ld.LDSection
import com.owen282000.lifedashboard.ui.ld.LDSectionLabel
import com.owen282000.lifedashboard.ui.ld.LocalLDNavigator
import com.owen282000.lifedashboard.ui.ld.LocalLDPairing
import com.owen282000.lifedashboard.ui.ld.ldStaggerRise
import com.owen282000.lifedashboard.ui.ld.rememberLDDraft
import com.owen282000.lifedashboard.viewmodel.UiMessage

/**
 * Where a destination starts: a pairing code, an address typed by hand, or the MQTT broker. An
 * address is stored as soon as it passes, so Send test works on the next page.
 */
@Composable
fun AddDestinationScreen(section: LDSection) {
    val ui = ldSection(section)
    val navigator = LocalLDNavigator.current
    val pairing = LocalLDPairing.current
    val resources = LocalResources.current
    // In memory only: an address can hold a credential until the check below has said so.
    var address by rememberLDDraft { "" }
    var problem by rememberLDDraft<UiMessage?> { null }
    val add = {
        val draft = ui.actions.destinationDraft(null, address.trim())
        problem = draft.urlProblem ?: ui.actions.saveDestination(draft)
        if (problem == null) navigator.replaceTop(LDRoute.WebhookDestination(section, draft.id))
    }
    LDScreen(title = stringResource(R.string.ld_title_add_destination)) {
        LDHeading(stringResource(R.string.ld_add_heading))
        LDGap(12.dp)
        LDButton(stringResource(R.string.webhook_scan_lead), Modifier.ldStaggerRise(0), onClick = pairing::startScan)
        LDNote(stringResource(R.string.ld_add_pair_note))

        LDSectionLabel(stringResource(R.string.ld_add_own_server))
        LDCard(Modifier.ldStaggerRise(1)) {
            LDFieldRow(
                stringResource(R.string.ld_add_webhook_url),
                address,
                onValueChange = {
                    address = it
                    problem = null
                },
                placeholder = stringResource(R.string.ld_add_webhook_example),
                error = problem?.takeIf { it !is UiMessage.SecretsNotReady }?.text(resources),
                keyboardOptions = urlKeyboard,
                onDone = { if (address.isNotBlank()) add() }
            )
        }
        if (problem is UiMessage.SecretsNotReady) {
            LDBanner(stringResource(R.string.ld_not_saved), message = stringResource(R.string.secrets_not_ready)) {
                LDBannerAction(stringResource(R.string.ld_try_again), onClick = add)
            }
        }
        LDGap(10.dp)
        LDButton(stringResource(R.string.ld_add_webhook), kind = LDButtonKind.Ghost, enabled = address.isNotBlank(), onClick = add)

        LDSectionLabel(stringResource(R.string.mqtt_title))
        LDCard(Modifier.ldStaggerRise(2)) {
            LDRow(
                stringResource(R.string.onboarding_mqtt_option),
                subtitle = stringResource(R.string.ld_add_mqtt_subtitle),
                value = mqttStatus(ui),
                onClick = { navigator.push(LDRoute.MqttDestination(section)) }
            )
        }
    }
}

internal val urlKeyboard = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, keyboardType = KeyboardType.Uri)

@Composable
private fun mqttStatus(ui: LDSectionUi): String {
    if (!ui.mqtt.section.enabled) return stringResource(R.string.receive_off)
    val host = ui.mqtt.activeBroker.host.trim()
    return if (host.isEmpty()) stringResource(R.string.mqtt_enabled_no_broker) else stringResource(R.string.ld_add_mqtt_on, host)
}
