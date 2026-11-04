package com.owen282000.lifedashboard.screens.ld

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.owen282000.lifedashboard.MqttSupport
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
import com.owen282000.lifedashboard.ui.ld.LDNoticeLine
import com.owen282000.lifedashboard.ui.ld.LDNoticeTone
import com.owen282000.lifedashboard.ui.ld.LDRefusedBanner
import com.owen282000.lifedashboard.ui.ld.LDScreen
import com.owen282000.lifedashboard.ui.ld.LDSection
import com.owen282000.lifedashboard.ui.ld.LDTheme
import com.owen282000.lifedashboard.ui.ld.LDToggleRow
import com.owen282000.lifedashboard.ui.ld.LDTopBarAction
import com.owen282000.lifedashboard.ui.ld.LocalLDEntryId
import com.owen282000.lifedashboard.ui.ld.LocalLDNavigator
import com.owen282000.lifedashboard.ui.ld.rememberLDDraft
import com.owen282000.lifedashboard.viewmodel.MqttDraft
import com.owen282000.lifedashboard.viewmodel.SettingsRules
import com.owen282000.lifedashboard.viewmodel.UiMessage
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * The section's MQTT broker. Everything is a draft until Save, so a half typed host never reaches a
 * sync in between. The broker fields edit the shared connection while "Use the shared broker" is on,
 * and this section's own broker when it is off; Save writes the shared one only when it was changed
 * here, so a tab that looked a while ago does not put back an old one.
 */
@Composable
fun MqttDestinationScreen(section: LDSection) {
    val ui = ldSection(section)
    val navigator = LocalLDNavigator.current
    val page = LocalLDEntryId.current
    val refusedNow = rememberRefusedNow(section)
    var draft by rememberLDDraft { ui.actions.mqttDraft() }
    var problem by rememberLDDraft<UiMessage?> { null }
    var confirmRemove by remember { mutableStateOf(false) }

    val save = {
        problem = ui.actions.saveMqtt(draft)
        if (problem == null) navigator.pop(page)
    }

    LDScreen(
        title = stringResource(R.string.mqtt_title),
        actions = { LDTopBarAction(stringResource(R.string.ld_save), enabled = SettingsRules.mqttFieldProblem(draft) == null, onClick = save) }
    ) {
        LDHeading(stringResource(R.string.onboarding_mqtt_option))
        LDNote(
            stringResource(if (section == LDSection.Health) R.string.health_mqtt_description else R.string.screentime_mqtt_description),
            top = 4.dp
        )
        if (problem is UiMessage.SecretsNotReady) {
            LDBanner(stringResource(R.string.ld_not_saved), message = stringResource(R.string.secrets_not_ready)) {
                LDBannerAction(stringResource(R.string.ld_try_again), onClick = save)
            }
        }
        LDRefusedBanner(
            ui.refused != null,
            onRetry = {
                ui.actions.retryRefused()
                if (!refusedNow()) navigator.pop(page)
            },
            onDismiss = ui.actions::dismissRefused
        )
        LDGap(12.dp)
        MqttFields(draft, section, onDone = { if (SettingsRules.mqttFieldProblem(draft) == null) save() }) {
            draft = it
            problem = null
        }
        val broker = draft.activeBroker
        if (!broker.useTls && broker.host.isNotBlank() && !MqttSupport.isPrivateHost(broker.host)) {
            LDNoticeLine(stringResource(R.string.mqtt_plaintext_public_host), LDNoticeTone.Warning, top = 10.dp)
        }
        ui.mqttLastStatus?.let { LastStatus(it) }
        if (ui.mqtt.section.enabled || ui.mqtt.activeBroker.host.isNotBlank()) {
            LDGap(10.dp)
            LDButton(stringResource(R.string.ld_dest_remove), kind = LDButtonKind.Danger) { confirmRemove = true }
        }
    }

    if (confirmRemove) {
        RemoveDestinationDialog(
            stringResource(R.string.ld_mqtt_remove_body),
            onConfirm = {
                confirmRemove = false
                ui.actions.removeMqtt()
                if (!refusedNow()) navigator.pop(page)
            },
            onDismiss = { confirmRemove = false }
        )
    }
}

@Composable
private fun MqttFields(mqtt: MqttDraft, section: LDSection, onDone: () -> Unit, onChange: (MqttDraft) -> Unit) {
    val resources = LocalResources.current
    val settings = mqtt.section
    val broker = mqtt.activeBroker
    val otherSection = stringResource(if (section == LDSection.Health) R.string.main_title_screen_time else R.string.main_title_health_connect)
    var passwordRevealed by remember { mutableStateOf(false) }
    LDCard {
        LDToggleRow(stringResource(R.string.mqtt_enable_publishing), settings.enabled, { onChange(mqtt.copy(section = settings.copy(enabled = it))) })
        LDToggleRow(
            stringResource(R.string.mqtt_use_shared_broker),
            settings.useSharedBroker,
            { onChange(mqtt.withShared(it)) },
            subtitle = if (settings.useSharedBroker) stringResource(R.string.mqtt_use_shared_broker_on)
            else stringResource(R.string.mqtt_use_shared_broker_off, otherSection)
        )
        LDFieldRow(
            stringResource(if (settings.useSharedBroker) R.string.mqtt_broker_host_shared else R.string.mqtt_broker_host),
            broker.host,
            onValueChange = { onChange(mqtt.withActiveBroker(broker.copy(host = it))) },
            placeholder = stringResource(R.string.mqtt_broker_host_placeholder),
            error = SettingsRules.mqttHostProblem(mqtt)?.text(resources),
            keyboardOptions = urlKeyboard.copy(imeAction = ImeAction.Next)
        )
        LDFieldRow(
            stringResource(R.string.mqtt_port),
            mqtt.portText,
            onValueChange = { text -> onChange(mqtt.copy(portText = text.filter { it.isDigit() }.take(5))) },
            isCode = false,
            error = SettingsRules.mqttPortProblem(mqtt)?.text(resources),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next)
        )
        LDToggleRow(stringResource(R.string.mqtt_tls), broker.useTls, { onChange(mqtt.withActiveBroker(broker.copy(useTls = it))) })
        LDFieldRow(
            stringResource(R.string.mqtt_username),
            broker.username.orEmpty(),
            onValueChange = { onChange(mqtt.withActiveBroker(broker.copy(username = it.ifBlank { null }))) },
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, imeAction = ImeAction.Next)
        )
        LDFieldRow(
            stringResource(R.string.mqtt_password),
            broker.password.orEmpty(),
            onValueChange = { onChange(mqtt.withActiveBroker(broker.copy(password = it.ifBlank { null }))) },
            isSecure = !passwordRevealed,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, keyboardType = KeyboardType.Password, imeAction = ImeAction.Next),
            trailing = { RevealButton(passwordRevealed) { passwordRevealed = !passwordRevealed } }
        )
        LDFieldRow(
            stringResource(R.string.mqtt_base_topic),
            settings.baseTopic,
            onValueChange = { onChange(mqtt.copy(section = settings.copy(baseTopic = it))) },
            onDone = onDone
        )
    }
}

/** Stored with an English frame; said here in the user's language. */
@Composable
private fun LastStatus(stored: String) {
    val colors = LDTheme.colors
    when (val status = remember(stored) { MqttSupport.publishStatus(stored) }) {
        is MqttSupport.PublishStatus.Published -> LDNote(
            pluralStringResource(R.plurals.mqtt_status_published, status.sensors, status.sensors, formatPublishTime(status.at)),
            color = colors.accentTextSoft,
            top = 10.dp
        )
        is MqttSupport.PublishStatus.Failed -> LDNote(status.reason, color = colors.warning, top = 10.dp)
    }
}

/** When the last publish went out, as the phone writes a short date and time. */
private fun formatPublishTime(at: Instant): String =
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).withZone(ZoneId.systemDefault()).format(at)
