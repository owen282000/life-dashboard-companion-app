package com.owen282000.lifedashboard.screens

import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import com.owen282000.lifedashboard.*
import com.owen282000.lifedashboard.ui.theme.*

@Composable
fun ScreenTimeScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val preferencesManager = remember { PreferencesManager(context) }
    val screenTimeManager = remember { ScreenTimeManager(context, preferencesManager) }

    var initialSyncInterval by remember { mutableStateOf(preferencesManager.getScreenTimeSyncIntervalMinutes()) }
    var initialWebhookUrls by remember { mutableStateOf(preferencesManager.getScreenTimeWebhookUrls()) }
    var initialDayBoundaryHour by remember { mutableStateOf(preferencesManager.getScreenTimeDayBoundaryHour()) }
    var initialUseDayBoundary by remember { mutableStateOf(preferencesManager.useScreenTimeDayBoundary()) }
    var initialWebhookHeaders by remember { mutableStateOf(preferencesManager.getScreenTimeWebhookHeaders()) }

    var syncInterval by remember { mutableStateOf(initialSyncInterval.toString()) }
    var webhookUrls by remember { mutableStateOf(initialWebhookUrls) }
    var dayBoundaryHour by remember { mutableStateOf(initialDayBoundaryHour.toString()) }
    var useDayBoundary by remember { mutableStateOf(initialUseDayBoundary) }
    var webhookHeaders by remember { mutableStateOf(initialWebhookHeaders) }
    var newHeaderKey by remember { mutableStateOf("") }
    var newHeaderValue by remember { mutableStateOf("") }
    var isHeadersExpanded by remember { mutableStateOf(false) }
    var newUrl by remember { mutableStateOf("") }
    var isSyncing by remember { mutableStateOf(false) }
    var isPreviewing by remember { mutableStateOf(false) }
    var previewData by remember { mutableStateOf<String?>(null) }
    var dayBoundaryExpanded by remember { mutableStateOf(false) }
    var syncMessage by remember { mutableStateOf<String?>(null) }
    var hasPermission by remember { mutableStateOf(screenTimeManager.hasPermission()) }

    LaunchedEffect(Unit) {
        hasPermission = screenTimeManager.hasPermission()
    }

    val hasChanges = remember(syncInterval, webhookUrls, dayBoundaryHour, useDayBoundary, webhookHeaders, initialSyncInterval, initialWebhookUrls, initialDayBoundaryHour, initialUseDayBoundary, initialWebhookHeaders) {
        val currentInterval = syncInterval.toIntOrNull() ?: initialSyncInterval
        val currentBoundaryHour = dayBoundaryHour.toIntOrNull() ?: initialDayBoundaryHour
        currentInterval != initialSyncInterval || webhookUrls != initialWebhookUrls || currentBoundaryHour != initialDayBoundaryHour || useDayBoundary != initialUseDayBoundary || webhookHeaders != initialWebhookHeaders
    }

    val scrollState = rememberScrollState()

    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(1000)
            val newPermissionStatus = screenTimeManager.hasPermission()
            if (newPermissionStatus != hasPermission) {
                hasPermission = newPermissionStatus
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(scrollState)
    ) {
        // Compact gradient status bar (consistent with Health Connect)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp)
                .background(
                    brush = Brush.horizontalGradient(
                        colors = listOf(
                            ScreenTimePrimary,
                            ScreenTimePrimary.copy(alpha = 0.8f)
                        )
                    )
                )
                .padding(horizontal = 16.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            Text(
                "Track your app usage",
                style = MaterialTheme.typography.bodySmall,
                color = Color.White
            )
        }

        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Permission Status - compact inline
            if (!hasPermission) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    color = ErrorContainer
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Warning,
                            contentDescription = null,
                            tint = Error,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            "Permissions required",
                            style = MaterialTheme.typography.bodyMedium,
                            color = OnErrorContainer,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(
                            onClick = {
                                val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
                                context.startActivity(intent)
                            }
                        ) {
                            Text("Grant", color = Error, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }

            // Day Boundary - collapsible settings
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 1.dp
            ) {
                val chevronRotation by animateFloatAsState(
                    targetValue = if (dayBoundaryExpanded) 180f else 0f,
                    label = "chevron"
                )

                Column(modifier = Modifier.padding(14.dp)) {
                    // Header row - always visible
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { dayBoundaryExpanded = !dayBoundaryExpanded },
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "Day Boundary",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                if (useDayBoundary) "Enabled · ${dayBoundaryHour}:00" else "Disabled",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (useDayBoundary) ScreenTimePrimary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Icon(
                            imageVector = Icons.Filled.ExpandMore,
                            contentDescription = if (dayBoundaryExpanded) "Collapse" else "Expand",
                            modifier = Modifier
                                .size(24.dp)
                                .rotate(chevronRotation),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    // Expandable content
                    AnimatedVisibility(
                        visible = dayBoundaryExpanded,
                        enter = expandVertically(),
                        exit = shrinkVertically()
                    ) {
                        Column(modifier = Modifier.padding(top = 12.dp)) {
                            // Toggle row
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    "Enable day boundary",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Switch(
                                    checked = useDayBoundary,
                                    onCheckedChange = { useDayBoundary = it },
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor = Color.White,
                                        checkedTrackColor = ScreenTimePrimary
                                    )
                                )
                            }

                            // Hour input - only when enabled
                            AnimatedVisibility(visible = useDayBoundary) {
                                Column(modifier = Modifier.padding(top = 8.dp)) {
                                    Text(
                                        "Activity before this hour counts as previous day",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    OutlinedTextField(
                                        value = dayBoundaryHour,
                                        onValueChange = { dayBoundaryHour = it },
                                        placeholder = { Text("4") },
                                        label = { Text("Hour (0-23)") },
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(top = 8.dp),
                                        shape = RoundedCornerShape(8.dp),
                                        singleLine = true,
                                        colors = OutlinedTextFieldDefaults.colors(
                                            focusedBorderColor = ScreenTimePrimary,
                                            cursorColor = ScreenTimePrimary
                                        )
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Sync Interval
            SectionCard(
                title = "Sync Interval",
                subtitle = "Minutes between syncs"
            ) {
                OutlinedTextField(
                    value = syncInterval,
                    onValueChange = { syncInterval = it },
                    placeholder = { Text("60") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = ScreenTimePrimary,
                        cursorColor = ScreenTimePrimary
                    )
                )
            }

            // Webhook URLs
            SectionCard(
                title = "Webhook URLs",
                subtitle = "${webhookUrls.size} configured"
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    webhookUrls.forEachIndexed { index, url ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                    RoundedCornerShape(8.dp)
                                )
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = url,
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1
                            )
                            IconButton(
                                onClick = { webhookUrls = webhookUrls.toMutableList().apply { removeAt(index) } },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Close,
                                    contentDescription = "Remove",
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = newUrl,
                            onValueChange = { newUrl = it },
                            placeholder = { Text("https://...") },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(8.dp),
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = ScreenTimePrimary,
                                cursorColor = ScreenTimePrimary
                            )
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        FilledIconButton(
                            onClick = {
                                if (newUrl.isNotBlank() && newUrl.startsWith("http")) {
                                    webhookUrls = webhookUrls + newUrl
                                    newUrl = ""
                                } else {
                                    Toast.makeText(context, "Enter a valid URL", Toast.LENGTH_SHORT).show()
                                }
                            },
                            colors = IconButtonDefaults.filledIconButtonColors(containerColor = ScreenTimePrimary)
                        ) {
                            Icon(Icons.Filled.Add, contentDescription = "Add", tint = Color.White)
                        }
                    }
                }
            }

            // Webhook Headers - collapsible
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 1.dp
            ) {
                val headersChevronRotation by animateFloatAsState(
                    targetValue = if (isHeadersExpanded) 180f else 0f,
                    label = "headersChevron"
                )

                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { isHeadersExpanded = !isHeadersExpanded },
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "Webhook Headers",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                if (webhookHeaders.isEmpty()) "None configured" else "${webhookHeaders.size} header(s)",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (webhookHeaders.isNotEmpty()) ScreenTimePrimary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Icon(
                            imageVector = Icons.Filled.ExpandMore,
                            contentDescription = if (isHeadersExpanded) "Collapse" else "Expand",
                            modifier = Modifier
                                .size(24.dp)
                                .rotate(headersChevronRotation),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    AnimatedVisibility(
                        visible = isHeadersExpanded,
                        enter = expandVertically(),
                        exit = shrinkVertically()
                    ) {
                        Column(
                            modifier = Modifier.padding(top = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            webhookHeaders.forEach { (key, value) ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(
                                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                            RoundedCornerShape(8.dp)
                                        )
                                        .padding(horizontal = 12.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = key,
                                            style = MaterialTheme.typography.bodySmall,
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        Text(
                                            text = value,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1
                                        )
                                    }
                                    IconButton(
                                        onClick = { webhookHeaders = webhookHeaders - key },
                                        modifier = Modifier.size(28.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.Close,
                                            contentDescription = "Remove",
                                            tint = MaterialTheme.colorScheme.error,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            }

                            OutlinedTextField(
                                value = newHeaderKey,
                                onValueChange = { newHeaderKey = it },
                                placeholder = { Text("Header name") },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(8.dp),
                                singleLine = true,
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = ScreenTimePrimary,
                                    cursorColor = ScreenTimePrimary
                                )
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                OutlinedTextField(
                                    value = newHeaderValue,
                                    onValueChange = { newHeaderValue = it },
                                    placeholder = { Text("Header value") },
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(8.dp),
                                    singleLine = true,
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = ScreenTimePrimary,
                                        cursorColor = ScreenTimePrimary
                                    )
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                FilledIconButton(
                                    onClick = {
                                        if (newHeaderKey.isNotBlank() && newHeaderValue.isNotBlank()) {
                                            webhookHeaders = webhookHeaders + (newHeaderKey.trim() to newHeaderValue.trim())
                                            newHeaderKey = ""
                                            newHeaderValue = ""
                                        } else {
                                            Toast.makeText(context, "Enter header name and value", Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = ScreenTimePrimary)
                                ) {
                                    Icon(Icons.Filled.Add, contentDescription = "Add", tint = Color.White)
                                }
                            }
                        }
                    }
                }
            }

            // Manual Sync
            SectionCard(
                title = "Manual Sync",
                subtitle = "Sync now"
            ) {
                Button(
                    onClick = {
                        if (isSyncing) return@Button

                        scope.launch {
                            isSyncing = true
                            syncMessage = null

                            try {
                                if (!screenTimeManager.hasPermission()) {
                                    syncMessage = "Permission not granted"
                                    val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
                                    context.startActivity(intent)
                                    isSyncing = false
                                    return@launch
                                }

                                val syncManager = ScreenTimeSyncManager(context)
                                val result = syncManager.performSync()

                                syncMessage = when {
                                    result.isSuccess -> {
                                        when (val syncResult = result.getOrThrow()) {
                                            is ScreenTimeSyncResult.NoData -> "No new data"
                                            is ScreenTimeSyncResult.Success -> "Synced ${syncResult.appCount} apps"
                                        }
                                    }
                                    else -> "Failed: ${result.exceptionOrNull()?.message}"
                                }
                            } catch (e: Exception) {
                                syncMessage = "Failed: ${e.message}"
                            } finally {
                                isSyncing = false
                            }
                        }
                    },
                    enabled = !isSyncing && webhookUrls.isNotEmpty() && hasPermission,
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = ScreenTimePrimary)
                ) {
                    if (isSyncing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = Color.White
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Text(if (isSyncing) "Syncing..." else "Sync Now")
                }

                OutlinedButton(
                    onClick = {
                        if (isPreviewing) return@OutlinedButton
                        scope.launch {
                            isPreviewing = true
                            try {
                                val syncManager = ScreenTimeSyncManager(context)
                                val result = syncManager.previewData()
                                if (result.isSuccess) {
                                    previewData = result.getOrThrow()
                                } else {
                                    Toast.makeText(context, result.exceptionOrNull()?.message ?: "Preview failed", Toast.LENGTH_SHORT).show()
                                }
                            } catch (e: Exception) {
                                Toast.makeText(context, "Preview failed: ${e.message}", Toast.LENGTH_SHORT).show()
                            } finally {
                                isPreviewing = false
                            }
                        }
                    },
                    enabled = !isPreviewing && hasPermission,
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = ScreenTimePrimary)
                ) {
                    if (isPreviewing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = ScreenTimePrimary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Icon(Icons.Filled.Visibility, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(if (isPreviewing) "Loading..." else "Preview Data")
                }

                AnimatedVisibility(visible = syncMessage != null) {
                    syncMessage?.let { message ->
                        Text(
                            message,
                            modifier = Modifier.padding(top = 8.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (message.startsWith("Failed")) Error else ScreenTimePrimary
                        )
                    }
                }
            }

            // Save Button
            AnimatedVisibility(
                visible = hasChanges,
                enter = expandVertically(),
                exit = shrinkVertically()
            ) {
                Button(
                    onClick = {
                        scope.launch {
                            val interval = syncInterval.toIntOrNull()
                            if (interval == null || interval < 15) {
                                Toast.makeText(context, "Min 15 minutes", Toast.LENGTH_SHORT).show()
                                return@launch
                            }
                            if (webhookUrls.isEmpty()) {
                                Toast.makeText(context, "Add a webhook URL", Toast.LENGTH_SHORT).show()
                                return@launch
                            }
                            val boundaryHour = dayBoundaryHour.toIntOrNull()
                            if (boundaryHour == null || boundaryHour < 0 || boundaryHour > 23) {
                                Toast.makeText(context, "Hour must be 0-23", Toast.LENGTH_SHORT).show()
                                return@launch
                            }

                            preferencesManager.setScreenTimeSyncIntervalMinutes(interval)
                            preferencesManager.setScreenTimeWebhookUrls(webhookUrls)
                            preferencesManager.setScreenTimeDayBoundaryHour(boundaryHour)
                            preferencesManager.setUseScreenTimeDayBoundary(useDayBoundary)
                            preferencesManager.setScreenTimeWebhookHeaders(webhookHeaders)
                            (context.applicationContext as? LifeDashboardApplication)?.scheduleScreenTimeSyncWork()

                            initialSyncInterval = interval
                            initialWebhookUrls = webhookUrls
                            initialDayBoundaryHour = boundaryHour
                            initialUseDayBoundary = useDayBoundary
                            initialWebhookHeaders = webhookHeaders
                            Toast.makeText(context, "Saved!", Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Success)
                ) {
                    Icon(Icons.Filled.Check, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Save Changes", fontWeight = FontWeight.SemiBold)
                }
            }

            // Status
            Text(
                "Syncing every ${syncInterval}min to ${webhookUrls.size} webhook(s)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 4.dp)
            )

            Spacer(modifier = Modifier.height(80.dp))
        }

        // Preview Dialog
        if (previewData != null) {
            AlertDialog(
                onDismissRequest = { previewData = null },
                title = { Text("Data Preview") },
                text = {
                    Column {
                        Text(
                            "This is the JSON payload that will be sent:",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 400.dp),
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant
                        ) {
                            val previewScrollState = rememberScrollState()
                            Text(
                                text = previewData ?: "",
                                modifier = Modifier
                                    .padding(12.dp)
                                    .verticalScroll(previewScrollState),
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                                ),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { previewData = null }) {
                        Text("Close", color = ScreenTimePrimary)
                    }
                }
            )
        }
    }
}

@Composable
private fun SectionCard(
    title: String,
    subtitle: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.height(10.dp))
            content()
        }
    }
}
