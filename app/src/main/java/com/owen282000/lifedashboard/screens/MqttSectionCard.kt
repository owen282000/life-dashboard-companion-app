package com.owen282000.lifedashboard.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.owen282000.lifedashboard.MqttBroker
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
        title = "MQTT",
        subtitle = when {
            !section.enabled -> "Disabled"
            broker.host.isBlank() -> "Enabled: no broker set"
            section.useSharedBroker -> "Enabled: ${broker.host} (shared broker)"
            else -> "Enabled: ${broker.host} (own broker)"
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
            Text("Enable MQTT publishing", style = MaterialTheme.typography.bodyMedium)
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
                Text("Use the shared broker", style = MaterialTheme.typography.bodyMedium)
                Text(
                    if (section.useSharedBroker) "One connection for Health Connect and Screen Time; the fields below edit it for both"
                    else "This section connects to its own broker; $otherSection keeps the shared one",
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
            placeholder = { Text("Broker host, e.g. 192.168.1.10") },
            label = { Text(if (section.useSharedBroker) "Broker host (shared)" else "Broker host") },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(8.dp),
            singleLine = true,
            colors = fieldColors
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = portText,
                onValueChange = { onPortTextChange(it.filter { c -> c.isDigit() }.take(5)) },
                label = { Text("Port") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(8.dp),
                singleLine = true,
                colors = fieldColors
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text("TLS", style = MaterialTheme.typography.bodyMedium)
            Spacer(modifier = Modifier.width(6.dp))
            Switch(
                checked = broker.useTls,
                onCheckedChange = { onBrokerChange(broker.copy(useTls = it)) }
            )
        }
        OutlinedTextField(
            value = broker.username ?: "",
            onValueChange = { onBrokerChange(broker.copy(username = it.ifBlank { null })) },
            label = { Text("Username (optional)") },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(8.dp),
            singleLine = true,
            colors = fieldColors
        )
        OutlinedTextField(
            value = broker.password ?: "",
            onValueChange = { onBrokerChange(broker.copy(password = it.ifBlank { null })) },
            label = { Text("Password (optional)") },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(8.dp),
            singleLine = true,
            colors = fieldColors
        )
        OutlinedTextField(
            value = section.baseTopic,
            onValueChange = { onSectionChange(section.copy(baseTopic = it)) },
            label = { Text("Base topic") },
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
