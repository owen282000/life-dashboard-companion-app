package com.owen282000.lifedashboard.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.owen282000.lifedashboard.MqttBroker
import com.owen282000.lifedashboard.R
import com.owen282000.lifedashboard.MqttSectionSettings

/**
 * The MQTT settings card, identical for Health Connect and Screen Time (issue #52). Each
 * section has its own switch and base topic. The broker fields edit the shared connection
 * while "Use the shared broker" is on, and this section's own broker when it is off, so either
 * section can be set up first and the other can join or diverge later.
 */
@Composable
internal fun MqttSectionCard(
    description: String,
    otherSection: String,
    accent: Color,
    section: MqttSectionSettings,
    onSectionChange: (MqttSectionSettings) -> Unit,
    sharedBroker: MqttBroker,
    onSharedBrokerChange: (MqttBroker) -> Unit,
    portText: String,
    onPortTextChange: (String) -> Unit,
    lastStatus: String?,
    expanded: Boolean,
    onToggle: () -> Unit
) {
    val broker = if (section.useSharedBroker) sharedBroker else section.ownBroker
    val onBrokerChange: (MqttBroker) -> Unit = { updated ->
        if (section.useSharedBroker) onSharedBrokerChange(updated)
        else onSectionChange(section.copy(ownBroker = updated))
    }
    val fieldColors = OutlinedTextFieldDefaults.colors(focusedBorderColor = accent, cursorColor = accent)

    CollapsibleCard(
        title = stringResource(R.string.mqtt_title),
        subtitle = when {
            !section.enabled -> stringResource(R.string.mqtt_disabled)
            broker.host.isBlank() -> stringResource(R.string.mqtt_enabled_no_broker)
            section.useSharedBroker -> stringResource(R.string.mqtt_enabled_shared_broker, broker.host)
            else -> stringResource(R.string.mqtt_enabled_own_broker, broker.host)
        },
        subtitleColor = if (section.enabled) accent else MaterialTheme.colorScheme.onSurfaceVariant,
        expanded = expanded,
        onToggle = onToggle
    ) {
        Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(stringResource(R.string.mqtt_enable_publishing), style = MaterialTheme.typography.bodyMedium)
            Switch(
                checked = section.enabled,
                onCheckedChange = { onSectionChange(section.copy(enabled = it)) }
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.mqtt_use_shared_broker), style = MaterialTheme.typography.bodyMedium)
                Text(
                    if (section.useSharedBroker) stringResource(R.string.mqtt_use_shared_broker_on)
                    else stringResource(R.string.mqtt_use_shared_broker_off, otherSection),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = section.useSharedBroker,
                onCheckedChange = { shared ->
                    onSectionChange(section.copy(useSharedBroker = shared))
                    onPortTextChange((if (shared) sharedBroker.port else section.ownBroker.port).toString())
                }
            )
        }
        OutlinedTextField(
            value = broker.host,
            onValueChange = { onBrokerChange(broker.copy(host = it)) },
            placeholder = { Text(stringResource(R.string.mqtt_broker_host_placeholder)) },
            label = { Text(if (section.useSharedBroker) stringResource(R.string.mqtt_broker_host_shared) else stringResource(R.string.mqtt_broker_host)) },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(8.dp),
            singleLine = true,
            colors = fieldColors
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = portText,
                onValueChange = { onPortTextChange(it.filter { c -> c.isDigit() }.take(5)) },
                label = { Text(stringResource(R.string.mqtt_port)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(8.dp),
                singleLine = true,
                colors = fieldColors
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(stringResource(R.string.mqtt_tls), style = MaterialTheme.typography.bodyMedium)
            Spacer(modifier = Modifier.width(6.dp))
            Switch(
                checked = broker.useTls,
                onCheckedChange = { onBrokerChange(broker.copy(useTls = it)) }
            )
        }
        OutlinedTextField(
            value = broker.username ?: "",
            onValueChange = { onBrokerChange(broker.copy(username = it.ifBlank { null })) },
            label = { Text(stringResource(R.string.mqtt_username)) },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(8.dp),
            singleLine = true,
            colors = fieldColors
        )
        OutlinedTextField(
            value = broker.password ?: "",
            onValueChange = { onBrokerChange(broker.copy(password = it.ifBlank { null })) },
            label = { Text(stringResource(R.string.mqtt_password)) },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(8.dp),
            singleLine = true,
            colors = fieldColors
        )
        OutlinedTextField(
            value = section.baseTopic,
            onValueChange = { onSectionChange(section.copy(baseTopic = it)) },
            label = { Text(stringResource(R.string.mqtt_base_topic)) },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(8.dp),
            singleLine = true,
            colors = fieldColors
        )
        lastStatus?.let { status ->
            Text(
                status,
                style = MaterialTheme.typography.bodySmall,
                color = if (status.startsWith("OK")) accent else MaterialTheme.colorScheme.error
            )
        }
    }
}
