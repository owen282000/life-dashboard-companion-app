package com.owen282000.lifedashboard.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.serialization.json.Json
import com.owen282000.lifedashboard.LogType
import com.owen282000.lifedashboard.PreferencesManager
import com.owen282000.lifedashboard.WebhookLog
import com.owen282000.lifedashboard.ui.theme.*
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun LogsScreen() {
    val context = LocalContext.current
    val preferencesManager = remember { PreferencesManager(context) }

    var selectedFilter by remember { mutableStateOf<LogType?>(null) }
    var logs by remember { mutableStateOf(preferencesManager.getWebhookLogs(selectedFilter)) }

    // Update logs when filter changes
    LaunchedEffect(selectedFilter) {
        logs = preferencesManager.getWebhookLogs(selectedFilter)
    }

    Column(
        modifier = Modifier.fillMaxSize()
    ) {
        // Filter chips and clear button
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            FilterChip(
                selected = selectedFilter == null,
                onClick = {
                    selectedFilter = null
                    logs = preferencesManager.getWebhookLogs(null)
                },
                label = { Text("All") }
            )
            FilterChip(
                selected = selectedFilter == LogType.HEALTH_CONNECT,
                onClick = {
                    selectedFilter = LogType.HEALTH_CONNECT
                    logs = preferencesManager.getWebhookLogs(LogType.HEALTH_CONNECT)
                },
                label = { Text("Health") },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = HealthPrimary.copy(alpha = 0.2f),
                    selectedLabelColor = HealthPrimary
                )
            )
            FilterChip(
                selected = selectedFilter == LogType.SCREEN_TIME,
                onClick = {
                    selectedFilter = LogType.SCREEN_TIME
                    logs = preferencesManager.getWebhookLogs(LogType.SCREEN_TIME)
                },
                label = { Text("Screen Time") },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = ScreenTimePrimary.copy(alpha = 0.2f),
                    selectedLabelColor = ScreenTimePrimary
                )
            )

            Spacer(modifier = Modifier.weight(1f))

            if (logs.isNotEmpty()) {
                IconButton(
                    onClick = {
                        preferencesManager.clearWebhookLogs(selectedFilter)
                        logs = emptyList()
                    }
                ) {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = "Clear logs",
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
        }

        if (logs.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "No webhook logs yet",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    Spacer(modifier = Modifier.height(4.dp))
                }

                items(logs) { log ->
                    LogItem(log)
                }

                item {
                    Spacer(modifier = Modifier.height(80.dp))
                }
            }
        }
    }
}

@Composable
private fun LogItem(log: WebhookLog) {
    var expanded by remember { mutableStateOf(false) }

    val isHealthLog = log.logType == LogType.HEALTH_CONNECT.name
    val accentColor = if (isHealthLog) HealthPrimary else ScreenTimePrimary

    val containerColor = if (log.success) {
        accentColor.copy(alpha = 0.1f)
    } else {
        ErrorContainer
    }

    val textColor = if (log.success) {
        MaterialTheme.colorScheme.onSurface
    } else {
        OnErrorContainer
    }

    val logTypeLabel = when (log.logType) {
        LogType.HEALTH_CONNECT.name -> "Health"
        LogType.SCREEN_TIME.name -> "Screen Time"
        else -> "Unknown"
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = containerColor
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // Timestamp and Type
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    formatTimestamp(log.timestamp),
                    style = MaterialTheme.typography.labelSmall,
                    color = textColor.copy(alpha = 0.7f)
                )
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = accentColor.copy(alpha = 0.2f)
                ) {
                    Text(
                        logTypeLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = accentColor,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // URL
            Text(
                log.url,
                style = MaterialTheme.typography.bodySmall,
                color = textColor.copy(alpha = 0.8f),
                maxLines = 1
            )

            Spacer(modifier = Modifier.height(4.dp))

            // Status
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    if (log.success) {
                        "✓ ${log.statusCode ?: "OK"}"
                    } else {
                        "✗ ${log.statusCode ?: "Error"}"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (log.success) Success else Error
                )

                if (log.dataType != null && log.recordCount != null) {
                    Text(
                        "${log.dataType}: ${log.recordCount}",
                        style = MaterialTheme.typography.bodySmall,
                        color = textColor.copy(alpha = 0.6f)
                    )
                }
            }

            // Error message
            if (!log.success && log.errorMessage != null) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    log.errorMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = Error
                )
            }

            // Raw payload dropdown
            if (log.rawPayload != null) {
                Spacer(modifier = Modifier.height(8.dp))
                HorizontalDivider(
                    color = textColor.copy(alpha = 0.1f)
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { expanded = !expanded }
                        .padding(vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Payload",
                        style = MaterialTheme.typography.labelMedium,
                        color = textColor.copy(alpha = 0.7f)
                    )
                    Icon(
                        imageVector = if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                        contentDescription = if (expanded) "Collapse" else "Expand",
                        tint = textColor.copy(alpha = 0.5f),
                        modifier = Modifier.size(20.dp)
                    )
                }

                AnimatedVisibility(
                    visible = expanded,
                    enter = expandVertically(),
                    exit = shrinkVertically()
                ) {
                    val formattedJson = try {
                        val json = Json { prettyPrint = true }
                        val element = Json.parseToJsonElement(log.rawPayload)
                        json.encodeToString(kotlinx.serialization.json.JsonElement.serializer(), element)
                    } catch (e: Exception) {
                        log.rawPayload
                    }

                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.surface,
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .horizontalScroll(rememberScrollState())
                                .padding(8.dp)
                        ) {
                            Text(
                                formattedJson,
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 10.sp,
                                    lineHeight = 14.sp
                                ),
                                color = textColor.copy(alpha = 0.8f)
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun formatTimestamp(timestamp: Long): String {
    val sdf = SimpleDateFormat("MMM dd, HH:mm:ss", Locale.getDefault())
    return sdf.format(Date(timestamp))
}
