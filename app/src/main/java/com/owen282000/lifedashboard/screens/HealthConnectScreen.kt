package com.owen282000.lifedashboard.screens

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
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
    var initialWebhookSecret by remember { mutableStateOf(preferencesManager.getHealthWebhookSecret() ?: "") }
    var initialMqttSettings by remember { mutableStateOf(preferencesManager.getMqttSettings()) }

    var syncInterval by remember { mutableStateOf(initialSyncInterval.toString()) }
    var webhookUrls by remember { mutableStateOf(initialWebhookUrls) }
    var webhookHeaders by remember { mutableStateOf(initialWebhookHeaders) }
    var webhookSecret by remember { mutableStateOf(initialWebhookSecret) }
    var mqttSettings by remember { mutableStateOf(initialMqttSettings) }
    var mqttPortText by remember { mutableStateOf(initialMqttSettings.port.toString()) }
    var isMqttExpanded by remember { mutableStateOf(false) }
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
    var isPreviewing by remember { mutableStateOf(false) }
    var isPinging by remember { mutableStateOf(false) }
    var failureNotificationsEnabled by remember { mutableStateOf(SyncFailureNotifier.isEnabled(context)) }
    var failureThreshold by remember { mutableStateOf(SyncFailureNotifier.getThreshold(context)) }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { }
    var isExporting by remember { mutableStateOf(false) }
    var showExportFormatDialog by remember { mutableStateOf(false) }
    var exportJsonData by remember { mutableStateOf<String?>(null) }
    var previewData by remember { mutableStateOf<String?>(null) }

    val hasChanges = remember(syncInterval, webhookUrls, enabledDataTypes, webhookHeaders, webhookSecret, mqttSettings, mqttPortText, initialSyncInterval, initialWebhookUrls, initialEnabledDataTypes, initialWebhookHeaders, initialWebhookSecret, initialMqttSettings) {
        val currentInterval = syncInterval.toIntOrNull() ?: initialSyncInterval
        currentInterval != initialSyncInterval || webhookUrls != initialWebhookUrls || enabledDataTypes != initialEnabledDataTypes || webhookHeaders != initialWebhookHeaders || webhookSecret != initialWebhookSecret || mqttSettings.copy(port = mqttPortText.toIntOrNull() ?: mqttSettings.port) != initialMqttSettings
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
                                val isPermissionGranted = permission in grantedPermissionsSet || hasAtLeastOnePermission

                                DataTypeRow(
                                    name = dataType.displayName,
                                    isEnabled = dataType in enabledDataTypes,
                                    isPermissionGranted = isPermissionGranted,
                                    onToggle = { checked ->
                                        if (!hasAtLeastOnePermission && checked) {
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

                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                "HMAC signing secret (optional)",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                "When set, every POST gets an X-Signature header (sha256=<hex>) computed as HMAC-SHA256 over the body, so your server can verify the sender.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            OutlinedTextField(
                                value = webhookSecret,
                                onValueChange = { webhookSecret = it },
                                placeholder = { Text("Shared secret") },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(8.dp),
                                singleLine = true,
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = HealthPrimary,
                                    cursorColor = HealthPrimary
                                )
                            )
                        }
                    }
                }
            }

            // Failure notifications (shared across Health Connect and Screen Time)
            SectionCard(
                title = "Notifications",
                subtitle = "Alert when syncs keep failing"
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Notify after failed syncs",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Switch(
                        checked = failureNotificationsEnabled,
                        onCheckedChange = { enabled ->
                            failureNotificationsEnabled = enabled
                            SyncFailureNotifier.setEnabled(context, enabled)
                            if (enabled && android.os.Build.VERSION.SDK_INT >= 33) {
                                notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                            }
                        }
                    )
                }
                if (failureNotificationsEnabled) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "After consecutive failures",
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        listOf(3, 5, 10).forEach { value ->
                            FilterChip(
                                selected = failureThreshold == value,
                                onClick = {
                                    failureThreshold = value
                                    SyncFailureNotifier.setThreshold(context, value)
                                },
                                label = { Text("$value") },
                                modifier = Modifier.padding(start = 6.dp)
                            )
                        }
                    }
                }
            }

            // MQTT / Home Assistant Discovery - collapsible
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 1.dp
            ) {
                val mqttChevronRotation by animateFloatAsState(
                    targetValue = if (isMqttExpanded) 180f else 0f,
                    label = "mqttChevron"
                )

                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { isMqttExpanded = !isMqttExpanded },
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "MQTT / Home Assistant",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                if (mqttSettings.enabled) "Enabled: ${mqttSettings.host.ifBlank { "no broker set" }}" else "Disabled",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (mqttSettings.enabled) HealthPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Icon(
                            imageVector = Icons.Filled.ExpandMore,
                            contentDescription = if (isMqttExpanded) "Collapse" else "Expand",
                            modifier = Modifier
                                .size(24.dp)
                                .rotate(mqttChevronRotation),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    AnimatedVisibility(
                        visible = isMqttExpanded,
                        enter = expandVertically(),
                        exit = shrinkVertically()
                    ) {
                        Column(
                            modifier = Modifier.padding(top = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                "Publishes the latest value of each synced data type to your MQTT broker with Home Assistant Discovery: sensors appear in Home Assistant automatically, no server-side setup needed.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Enable MQTT publishing", style = MaterialTheme.typography.bodyMedium)
                                Switch(
                                    checked = mqttSettings.enabled,
                                    onCheckedChange = { mqttSettings = mqttSettings.copy(enabled = it) }
                                )
                            }
                            OutlinedTextField(
                                value = mqttSettings.host,
                                onValueChange = { mqttSettings = mqttSettings.copy(host = it) },
                                placeholder = { Text("Broker host, e.g. 192.168.1.10") },
                                label = { Text("Broker host") },
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
                                    value = mqttPortText,
                                    onValueChange = { mqttPortText = it.filter { c -> c.isDigit() }.take(5) },
                                    label = { Text("Port") },
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(8.dp),
                                    singleLine = true,
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = HealthPrimary,
                                        cursorColor = HealthPrimary
                                    )
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Text("TLS", style = MaterialTheme.typography.bodyMedium)
                                Spacer(modifier = Modifier.width(6.dp))
                                Switch(
                                    checked = mqttSettings.useTls,
                                    onCheckedChange = { mqttSettings = mqttSettings.copy(useTls = it) }
                                )
                            }
                            OutlinedTextField(
                                value = mqttSettings.username ?: "",
                                onValueChange = { mqttSettings = mqttSettings.copy(username = it.ifBlank { null }) },
                                label = { Text("Username (optional)") },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(8.dp),
                                singleLine = true,
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = HealthPrimary,
                                    cursorColor = HealthPrimary
                                )
                            )
                            OutlinedTextField(
                                value = mqttSettings.password ?: "",
                                onValueChange = { mqttSettings = mqttSettings.copy(password = it.ifBlank { null }) },
                                label = { Text("Password (optional)") },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(8.dp),
                                singleLine = true,
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = HealthPrimary,
                                    cursorColor = HealthPrimary
                                )
                            )
                            OutlinedTextField(
                                value = mqttSettings.baseTopic,
                                onValueChange = { mqttSettings = mqttSettings.copy(baseTopic = it) },
                                label = { Text("Base topic") },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(8.dp),
                                singleLine = true,
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = HealthPrimary,
                                    cursorColor = HealthPrimary
                                )
                            )
                            preferencesManager.getLastMqttStatus()?.let { status ->
                                Text(
                                    status,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (status.startsWith("OK")) HealthPrimary else MaterialTheme.colorScheme.error
                                )
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
                                preferencesManager.setHealthWebhookSecret(webhookSecret.trim())
                                mqttSettings = mqttSettings.copy(port = mqttPortText.toIntOrNull() ?: 1883)
                                preferencesManager.setMqttSettings(mqttSettings)

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
                                initialWebhookSecret = webhookSecret
                                initialMqttSettings = mqttSettings
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

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedButton(
                    onClick = {
                        if (isPreviewing) return@OutlinedButton
                        scope.launch {
                            isPreviewing = true
                            try {
                                preferencesManager.setHealthEnabledDataTypes(enabledDataTypes)
                                val syncManager = HealthSyncManager(context)
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
                    enabled = !isPreviewing && enabledDataTypes.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = HealthPrimary)
                ) {
                    if (isPreviewing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = HealthPrimary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Icon(Icons.Outlined.Visibility, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(if (isPreviewing) "Loading..." else "Preview Data")
                }

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedButton(
                    onClick = {
                        if (isPinging) return@OutlinedButton
                        scope.launch {
                            isPinging = true
                            try {
                                val payload = """{"test":true,"message":"Test ping from Life Dashboard Companion","timestamp":"${java.time.Instant.now()}","source":"health_connect"}"""
                                val result = WebhookManager(
                                    webhookUrls = webhookUrls,
                                    context = context,
                                    dataType = "test",
                                    recordCount = 0,
                                    logType = LogType.HEALTH_CONNECT,
                                    customHeaders = webhookHeaders,
                                    signingSecret = webhookSecret.trim().ifBlank { null }
                                ).postData(payload)
                                Toast.makeText(
                                    context,
                                    if (result.isSuccess) "Test ping delivered" else "Test ping failed, check the logs",
                                    Toast.LENGTH_SHORT
                                ).show()
                            } catch (e: Exception) {
                                Toast.makeText(context, "Test ping failed: ${e.message}", Toast.LENGTH_SHORT).show()
                            } finally {
                                isPinging = false
                            }
                        }
                    },
                    enabled = !isPinging && webhookUrls.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = HealthPrimary)
                ) {
                    Icon(Icons.Outlined.NetworkPing, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(if (isPinging) "Pinging..." else "Send Test Ping")
                }

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedButton(
                    onClick = {
                        if (isExporting) return@OutlinedButton
                        scope.launch {
                            isExporting = true
                            try {
                                preferencesManager.setHealthEnabledDataTypes(enabledDataTypes)
                                val syncManager = HealthSyncManager(context)
                                val result = syncManager.previewData()
                                if (result.isSuccess) {
                                    exportJsonData = result.getOrThrow()
                                    showExportFormatDialog = true
                                } else {
                                    Toast.makeText(context, result.exceptionOrNull()?.message ?: "Export failed", Toast.LENGTH_SHORT).show()
                                }
                            } catch (e: Exception) {
                                Toast.makeText(context, "Export failed: ${e.message}", Toast.LENGTH_SHORT).show()
                            } finally {
                                isExporting = false
                            }
                        }
                    },
                    enabled = !isExporting && enabledDataTypes.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = HealthPrimary)
                ) {
                    if (isExporting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = HealthPrimary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Icon(Icons.Outlined.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(if (isExporting) "Loading..." else "Export Data")
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
                            preferencesManager.setHealthWebhookSecret(webhookSecret.trim())
                            mqttSettings = mqttSettings.copy(port = mqttPortText.toIntOrNull() ?: 1883)
                            preferencesManager.setMqttSettings(mqttSettings)
                            (context.applicationContext as? LifeDashboardApplication)?.scheduleHealthSyncWork()

                            initialSyncInterval = interval
                            initialWebhookUrls = webhookUrls
                            initialEnabledDataTypes = enabledDataTypes
                            initialWebhookHeaders = webhookHeaders
                            initialWebhookSecret = webhookSecret
                            initialMqttSettings = mqttSettings
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
                        Text("Close", color = HealthPrimary)
                    }
                }
            )
        }

        // Export Format Dialog
        if (showExportFormatDialog && exportJsonData != null) {
            AlertDialog(
                onDismissRequest = { showExportFormatDialog = false },
                title = { Text("Export Data") },
                text = {
                    Text(
                        "Export current health data as JSON or CSV.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        showExportFormatDialog = false
                        val exportManager = ExportManager(context)
                        val timestamp = java.time.LocalDateTime.now()
                            .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
                        exportManager.shareFile(exportJsonData!!, "health_data_$timestamp.json", "application/json")
                    }) {
                        Text("JSON", color = HealthPrimary)
                    }
                },
                dismissButton = {
                    TextButton(onClick = {
                        showExportFormatDialog = false
                        val exportManager = ExportManager(context)
                        val timestamp = java.time.LocalDateTime.now()
                            .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
                        exportManager.shareFile(exportJsonData!!, "health_data_$timestamp.csv", "text/csv")
                    }) {
                        Text("CSV", color = HealthPrimary)
                    }
                }
            )
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
