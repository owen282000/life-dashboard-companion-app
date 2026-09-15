package com.owen282000.lifedashboard

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.owen282000.lifedashboard.ui.theme.LifeDashboardTheme
import kotlinx.serialization.json.Json
import java.text.SimpleDateFormat
import java.util.*

class LogsActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            LifeDashboardTheme {
                LogsScreen()
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
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

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(R.string.main_title_webhook_logs)) },
                    navigationIcon = {
                        IconButton(onClick = { finish() }) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.common_back)
                            )
                        }
                    },
                    actions = {
                        if (logs.isNotEmpty()) {
                            TextButton(onClick = {
                                preferencesManager.clearWebhookLogs(selectedFilter)
                                logs = emptyList()
                            }) {
                                Text(stringResource(R.string.logs_clear))
                            }
                        }
                    }
                )
            }
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                // Filter chips
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = selectedFilter == null,
                        onClick = {
                            selectedFilter = null
                            logs = preferencesManager.getWebhookLogs(null)
                        },
                        label = { Text(stringResource(R.string.logs_filter_all)) }
                    )
                    FilterChip(
                        selected = selectedFilter == LogType.HEALTH_CONNECT,
                        onClick = {
                            selectedFilter = LogType.HEALTH_CONNECT
                            logs = preferencesManager.getWebhookLogs(LogType.HEALTH_CONNECT)
                        },
                        label = { Text(stringResource(R.string.logs_filter_health)) }
                    )
                    FilterChip(
                        selected = selectedFilter == LogType.SCREEN_TIME,
                        onClick = {
                            selectedFilter = LogType.SCREEN_TIME
                            logs = preferencesManager.getWebhookLogs(LogType.SCREEN_TIME)
                        },
                        label = { Text(stringResource(R.string.logs_filter_screen_time)) }
                    )
                }

                if (logs.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            stringResource(R.string.logs_empty),
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
                            Spacer(modifier = Modifier.height(8.dp))
                        }

                        items(logs) { log ->
                            LogItem(log)
                        }

                        item {
                            Spacer(modifier = Modifier.height(8.dp))
                        }
                    }
                }
            }
        }
    }

    @Composable
    fun LogItem(log: WebhookLog) {
        var expanded by remember { mutableStateOf(false) }
        val textColor = if (log.success) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.onErrorContainer
        }

        val logTypeLabel = when (log.logType) {
            LogType.HEALTH_CONNECT.name -> stringResource(R.string.logs_filter_health)
            LogType.SCREEN_TIME.name -> stringResource(R.string.logs_filter_screen_time)
            else -> stringResource(R.string.logs_filter_unknown)
        }

        Card(
            colors = CardDefaults.cardColors(
                containerColor = if (log.success) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.errorContainer
                }
            )
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
                        color = textColor
                    )
                    Text(
                        logTypeLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = textColor
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                // URL
                Text(
                    log.url,
                    style = MaterialTheme.typography.bodyMedium,
                    color = textColor
                )

                Spacer(modifier = Modifier.height(4.dp))

                // Status
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        if (log.success) {
                            stringResource(R.string.logs_success_with_status, log.statusCode?.toString() ?: stringResource(R.string.logs_unknown_status))
                        } else {
                            stringResource(R.string.logs_failed_with_status, log.statusCode?.toString() ?: stringResource(R.string.logs_error))
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = textColor
                    )

                    if (log.dataType != null && log.recordCount != null) {
                        Text(
                            stringResource(R.string.logs_record_count, log.dataType, log.recordCount),
                            style = MaterialTheme.typography.bodySmall,
                            color = textColor
                        )
                    }
                }

                // Error message
                if (!log.success && log.errorMessage != null) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        log.errorMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }

                // Delivery note (e.g. succeeded after retries)
                if (log.note != null) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        log.note,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // Raw payload dropdown
                if (log.rawPayload != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    HorizontalDivider(
                        color = textColor.copy(alpha = 0.2f)
                    )
                    Spacer(modifier = Modifier.height(4.dp))

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { expanded = !expanded }
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            stringResource(R.string.logs_raw_payload),
                            style = MaterialTheme.typography.labelMedium,
                            color = textColor
                        )
                        Icon(
                            imageVector = if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                            contentDescription = if (expanded) stringResource(R.string.common_collapse) else stringResource(R.string.common_expand),
                            tint = textColor
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
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 4.dp),
                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.5f),
                            shape = MaterialTheme.shapes.small
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
                                    color = textColor
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    private fun formatTimestamp(timestamp: Long): String {
        val sdf = SimpleDateFormat("MMM dd, yyyy HH:mm:ss", Locale.getDefault())
        return sdf.format(Date(timestamp))
    }
}
