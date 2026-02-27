package com.owen282000.lifedashboard.screens

import android.content.Intent
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import kotlinx.coroutines.launch
import com.owen282000.lifedashboard.*
import com.owen282000.lifedashboard.ui.theme.*

@Composable
fun HealthConnectScreen(
    permissionLauncher: androidx.activity.result.ActivityResultLauncher<Set<String>>,
    onPermissionResult: ((Boolean) -> Unit)? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val preferencesManager = remember { PreferencesManager(context) }

    var initialSyncInterval by remember { mutableStateOf(preferencesManager.getHealthSyncIntervalMinutes()) }
    var initialWebhookUrls by remember { mutableStateOf(preferencesManager.getHealthWebhookUrls()) }
    var initialEnabledDataTypes by remember { mutableStateOf(preferencesManager.getHealthEnabledDataTypes()) }
    var initialWebhookHeaders by remember { mutableStateOf(preferencesManager.getHealthWebhookHeaders()) }

    var syncInterval by remember { mutableStateOf(initialSyncInterval.toString()) }
    var webhookUrls by remember { mutableStateOf(initialWebhookUrls) }
    var webhookHeaders by remember { mutableStateOf(initialWebhookHeaders) }
    var newHeaderKey by remember { mutableStateOf("") }
    var newHeaderValue by remember { mutableStateOf("") }
    var isHeadersExpanded by remember { mutableStateOf(false) }
    var newUrl by remember { mutableStateOf("") }
    var isSyncing by remember { mutableStateOf(false) }
    var syncMessage by remember { mutableStateOf<String?>(null) }
    var hasPermissions by remember { mutableStateOf<Boolean?>(null) }
    var enabledDataTypes by remember { mutableStateOf(initialEnabledDataTypes) }
    var grantedPermissionsSet by remember { mutableStateOf<Set<String>>(emptySet()) }
    var showPermissionModal by remember { mutableStateOf(false) }
    var selectedDataTypeForPermission by remember { mutableStateOf<HealthDataType?>(null) }
    var isDataTypesExpanded by remember { mutableStateOf(false) }
    var healthConnectUnavailableReason by remember { mutableStateOf<String?>(null) }

    val hasChanges = remember(syncInterval, webhookUrls, enabledDataTypes, webhookHeaders, initialSyncInterval, initialWebhookUrls, initialEnabledDataTypes, initialWebhookHeaders) {
        val currentInterval = syncInterval.toIntOrNull() ?: initialSyncInterval
        currentInterval != initialSyncInterval || webhookUrls != initialWebhookUrls || enabledDataTypes != initialEnabledDataTypes || webhookHeaders != initialWebhookHeaders
    }

    val scrollState = rememberScrollState()

    LaunchedEffect(Unit) {
        try {
            val availability = HealthConnectClient.getSdkStatus(context)
            if (availability != HealthConnectClient.SDK_AVAILABLE) {
                hasPermissions = false
                healthConnectUnavailableReason = when (availability) {
                    HealthConnectClient.SDK_UNAVAILABLE -> "not_installed"
                    HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> "needs_update"
                    else -> "unavailable"
                }
                return@LaunchedEffect
            }

            val healthConnectManager = HealthConnectManager(context)
            val grantedPermissions = healthConnectManager.getGrantedPermissions()
            hasPermissions = grantedPermissions.isNotEmpty()
            grantedPermissionsSet = grantedPermissions

            if (enabledDataTypes.isEmpty() && grantedPermissions.isNotEmpty()) {
                val grantedTypes = HealthDataType.entries.filter { type ->
                    HealthPermission.getReadPermission(type.recordClass) in grantedPermissions
                }.toSet()
                if (grantedTypes.isNotEmpty()) {
                    enabledDataTypes = grantedTypes
                    preferencesManager.setHealthEnabledDataTypes(grantedTypes)
                }
            }
        } catch (e: Exception) {
            hasPermissions = false
        }
    }

    val missingPermissionsForEnabled = remember(enabledDataTypes, grantedPermissionsSet) {
        enabledDataTypes.mapNotNull { dataType ->
            val permission = HealthPermission.getReadPermission(dataType.recordClass)
            if (permission !in grantedPermissionsSet) permission else null
        }.toSet()
    }

    val hasAtLeastOnePermission = grantedPermissionsSet.isNotEmpty()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(scrollState)
    ) {
        // Compact gradient status bar
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp)
                .background(
                    brush = Brush.horizontalGradient(
                        colors = listOf(
                            HealthPrimary,
                            HealthPrimary.copy(alpha = 0.8f)
                        )
                    )
                )
                .padding(horizontal = 16.dp),
            contentAlignment = Alignment.Center
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "${enabledDataTypes.size} data types selected",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White
                )
                Surface(
                    onClick = {
                        try {
                            val intent = context.packageManager.getLaunchIntentForPackage("com.google.android.apps.healthdata")
                            if (intent != null) {
                                context.startActivity(intent)
                            } else {
                                val settingsIntent = Intent("android.health.connect.action.HEALTH_HOME_SETTINGS")
                                try {
                                    context.startActivity(settingsIntent)
                                } catch (e: Exception) {
                                    val playStoreIntent = Intent(Intent.ACTION_VIEW).apply {
                                        data = android.net.Uri.parse("https://play.google.com/store/apps/details?id=com.google.android.apps.healthdata")
                                    }
                                    context.startActivity(playStoreIntent)
                                }
                            }
                        } catch (e: Exception) {
                            Toast.makeText(context, "Could not open Health Connect", Toast.LENGTH_SHORT).show()
                        }
                    },
                    shape = RoundedCornerShape(16.dp),
                    color = Color.White.copy(alpha = 0.2f)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Open App",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                            contentDescription = null,
                            modifier = Modifier.size(12.dp),
                            tint = Color.White
                        )
                    }
                }
            }
        }

        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Health Connect Status
            if (hasPermissions == false) {
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
                            imageVector = if (healthConnectUnavailableReason != null) Icons.Filled.ErrorOutline else Icons.Filled.Warning,
                            contentDescription = null,
                            tint = Error,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            when (healthConnectUnavailableReason) {
                                "not_installed" -> "Health Connect is not installed"
                                "needs_update" -> "Health Connect needs to be updated"
                                "unavailable" -> "Health Connect is not available"
                                else -> "Permissions required"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = OnErrorContainer,
                            modifier = Modifier.weight(1f)
                        )
                        if (healthConnectUnavailableReason != null) {
                            TextButton(
                                onClick = {
                                    val intent = Intent(Intent.ACTION_VIEW).apply {
                                        data = android.net.Uri.parse("https://play.google.com/store/apps/details?id=com.google.android.apps.healthdata")
                                    }
                                    context.startActivity(intent)
                                }
                            ) {
                                Text(
                                    if (healthConnectUnavailableReason == "needs_update") "Update" else "Install",
                                    color = Error,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        } else {
                            TextButton(
                                onClick = {
                                    try {
                                        permissionLauncher.launch(HealthConnectManager.ALL_PERMISSIONS)
                                    } catch (e: Exception) {
                                        Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                                    }
                                }
                            ) {
                                Text("Grant", color = Error, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
            }

            // Data Types - collapsible (same style as Day Boundary)
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 1.dp
            ) {
                val chevronRotation by animateFloatAsState(
                    targetValue = if (isDataTypesExpanded) 180f else 0f,
                    label = "chevron"
                )

                Column(modifier = Modifier.padding(14.dp)) {
                    // Header row - always visible
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { isDataTypesExpanded = !isDataTypesExpanded },
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "Data Types",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                "${enabledDataTypes.size} of ${HealthDataType.entries.size} selected",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (enabledDataTypes.isNotEmpty()) HealthPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Icon(
                            imageVector = Icons.Filled.ExpandMore,
                            contentDescription = if (isDataTypesExpanded) "Collapse" else "Expand",
                            modifier = Modifier
                                .size(24.dp)
                                .rotate(chevronRotation),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    // Expandable content
                    AnimatedVisibility(
                        visible = isDataTypesExpanded,
                        enter = expandVertically(),
                        exit = shrinkVertically()
                    ) {
                        Column(
                            modifier = Modifier.padding(top = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            HealthDataType.entries.forEach { dataType ->
                                val permission = HealthPermission.getReadPermission(dataType.recordClass)
                                val isPermissionGranted = permission in grantedPermissionsSet

                                DataTypeRow(
                                    name = dataType.displayName,
                                    isEnabled = dataType in enabledDataTypes,
                                    isPermissionGranted = isPermissionGranted,
                                    onToggle = { checked ->
                                        if (!isPermissionGranted && checked && !hasAtLeastOnePermission) {
                                            selectedDataTypeForPermission = dataType
                                            showPermissionModal = true
                                        } else {
                                            enabledDataTypes = if (checked) {
                                                enabledDataTypes + dataType
                                            } else {
                                                enabledDataTypes - dataType
                                            }
                                        }
                                    }
                                )
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
                        focusedBorderColor = HealthPrimary,
                        cursorColor = HealthPrimary
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
                                focusedBorderColor = HealthPrimary,
                                cursorColor = HealthPrimary
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
                            colors = IconButtonDefaults.filledIconButtonColors(containerColor = HealthPrimary)
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
                                color = if (webhookHeaders.isNotEmpty()) HealthPrimary else MaterialTheme.colorScheme.onSurfaceVariant
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
                                    focusedBorderColor = HealthPrimary,
                                    cursorColor = HealthPrimary
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
                                        focusedBorderColor = HealthPrimary,
                                        cursorColor = HealthPrimary
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
                                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = HealthPrimary)
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
                                val availability = HealthConnectClient.getSdkStatus(context)
                                if (availability != HealthConnectClient.SDK_AVAILABLE) {
                                    syncMessage = "Health Connect not available"
                                    isSyncing = false
                                    return@launch
                                }

                                val healthConnectManager = HealthConnectManager(context)
                                val grantedPerms = healthConnectManager.getGrantedPermissions()
                                if (grantedPerms.isEmpty()) {
                                    permissionLauncher.launch(HealthConnectManager.ALL_PERMISSIONS)
                                    isSyncing = false
                                    return@launch
                                }

                                // Save current settings before syncing to ensure SyncManager uses them
                                val currentInterval = syncInterval.toIntOrNull() ?: 60
                                preferencesManager.setHealthSyncIntervalMinutes(currentInterval)
                                preferencesManager.setHealthWebhookUrls(webhookUrls)
                                preferencesManager.setHealthEnabledDataTypes(enabledDataTypes)
                                preferencesManager.setHealthWebhookHeaders(webhookHeaders)

                                val syncManager = HealthSyncManager(context)
                                val result = syncManager.performSync()

                                syncMessage = when {
                                    result.isSuccess -> {
                                        when (val syncResult = result.getOrThrow()) {
                                            is HealthSyncResult.NoData -> "No new data"
                                            is HealthSyncResult.Success -> "Synced ${syncResult.syncCounts.values.sum()} records"
                                        }
                                    }
                                    else -> "Failed: ${result.exceptionOrNull()?.message}"
                                }

                                // Update initial values so hasChanges reflects saved state
                                initialSyncInterval = currentInterval
                                initialWebhookUrls = webhookUrls
                                initialEnabledDataTypes = enabledDataTypes
                                initialWebhookHeaders = webhookHeaders
                            } catch (e: Exception) {
                                syncMessage = "Failed: ${e.message}"
                            } finally {
                                isSyncing = false
                            }
                        }
                    },
                    enabled = !isSyncing && webhookUrls.isNotEmpty() && enabledDataTypes.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = HealthPrimary)
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

                AnimatedVisibility(visible = syncMessage != null) {
                    syncMessage?.let { message ->
                        Text(
                            message,
                            modifier = Modifier.padding(top = 8.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (message.startsWith("Failed")) Error else HealthPrimary
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

                            preferencesManager.setHealthSyncIntervalMinutes(interval)
                            preferencesManager.setHealthWebhookUrls(webhookUrls)
                            preferencesManager.setHealthEnabledDataTypes(enabledDataTypes)
                            preferencesManager.setHealthWebhookHeaders(webhookHeaders)
                            (context.applicationContext as? LifeDashboardApplication)?.scheduleHealthSyncWork()

                            initialSyncInterval = interval
                            initialWebhookUrls = webhookUrls
                            initialEnabledDataTypes = enabledDataTypes
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

        // Permission Modal
        if (showPermissionModal && selectedDataTypeForPermission != null) {
            AlertDialog(
                onDismissRequest = { showPermissionModal = false },
                title = { Text("Permission Required") },
                text = { Text("Grant permission to sync ${selectedDataTypeForPermission!!.displayName}.") },
                confirmButton = {
                    TextButton(
                        onClick = {
                            val permission = HealthPermission.getReadPermission(selectedDataTypeForPermission!!.recordClass)
                            permissionLauncher.launch(setOf(permission))
                            showPermissionModal = false
                        }
                    ) {
                        Text("Grant", color = HealthPrimary)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showPermissionModal = false }) {
                        Text("Cancel")
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

@Composable
private fun DataTypeRow(
    name: String,
    isEnabled: Boolean,
    isPermissionGranted: Boolean,
    onToggle: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (isPermissionGranted) 1f else 0.5f)
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (!isPermissionGranted) {
                Icon(
                    imageVector = Icons.Filled.Lock,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.width(6.dp))
            }
            Text(
                text = name,
                style = MaterialTheme.typography.bodyMedium
            )
        }
        Switch(
            checked = isEnabled,
            onCheckedChange = onToggle,
            modifier = Modifier.height(24.dp),
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = HealthPrimary
            )
        )
    }
}
