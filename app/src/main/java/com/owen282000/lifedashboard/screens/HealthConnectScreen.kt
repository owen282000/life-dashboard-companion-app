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
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import kotlinx.coroutines.launch
import com.owen282000.lifedashboard.*
import com.owen282000.lifedashboard.R
import com.owen282000.lifedashboard.ui.theme.*

@Composable
fun HealthConnectScreen(
    permissionLauncher: androidx.activity.result.ActivityResultLauncher<Set<String>>,
    onPermissionResult: ((Boolean) -> Unit)? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val resources = LocalResources.current
    val preferencesManager = remember { PreferencesManager(context) }

    var initialSyncInterval by remember { mutableStateOf(preferencesManager.getHealthSyncIntervalMinutes()) }
    var initialWebhookUrls by remember { mutableStateOf(preferencesManager.getHealthWebhookUrls()) }
    var initialEnabledDataTypes by remember { mutableStateOf(preferencesManager.getHealthEnabledDataTypes()) }
    var initialWebhookHeaders by remember { mutableStateOf(preferencesManager.getHealthWebhookHeaders()) }
    var initialWebhookSecret by remember { mutableStateOf(preferencesManager.getHealthWebhookSecret() ?: "") }
    var initialMqttSection by remember { mutableStateOf(preferencesManager.getMqttSection(MqttSection.HEALTH)) }
    var initialSharedBroker by remember { mutableStateOf(preferencesManager.getSharedMqttBroker()) }

    var syncInterval by remember { mutableStateOf(initialSyncInterval.toString()) }
    var webhookUrls by remember { mutableStateOf(initialWebhookUrls) }
    var webhookHeaders by remember { mutableStateOf(initialWebhookHeaders) }
    var webhookSecret by remember { mutableStateOf(initialWebhookSecret) }
    var mqttSection by remember { mutableStateOf(initialMqttSection) }
    var sharedBroker by remember { mutableStateOf(initialSharedBroker) }
    var mqttPortText by remember { mutableStateOf((if (initialMqttSection.useSharedBroker) initialSharedBroker.port else initialMqttSection.ownBroker.port).toString()) }
    var isMqttExpanded by remember { mutableStateOf(false) }
    var isNotificationsExpanded by remember { mutableStateOf(false) }

    // Port lives in its own text field; fold it into whichever broker is active before comparing or saving.
    fun mqttWithPort(): Pair<MqttSectionSettings, MqttBroker> {
        val port = mqttPortText.toIntOrNull()
        return if (mqttSection.useSharedBroker) mqttSection to sharedBroker.copy(port = port ?: sharedBroker.port)
        else mqttSection.copy(ownBroker = mqttSection.ownBroker.copy(port = port ?: mqttSection.ownBroker.port)) to sharedBroker
    }
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
    var includeDailyTotals by remember { mutableStateOf(preferencesManager.includeDailyTotals()) }
    var allowHttpWebhooks by remember { mutableStateOf(preferencesManager.allowHttpWebhooks()) }
    var isAdvancedExpanded by remember { mutableStateOf(false) }
    var failureThreshold by remember { mutableStateOf(SyncFailureNotifier.getThreshold(context)) }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { }
    var isExporting by remember { mutableStateOf(false) }
    var showBackfillDialog by remember { mutableStateOf(false) }
    var backfillProgress by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var showExportFormatDialog by remember { mutableStateOf(false) }
    var exportJsonData by remember { mutableStateOf<String?>(null) }
    var previewData by remember { mutableStateOf<String?>(null) }

    val hasChanges = remember(syncInterval, webhookUrls, enabledDataTypes, webhookHeaders, webhookSecret, mqttSection, sharedBroker, mqttPortText, initialSyncInterval, initialWebhookUrls, initialEnabledDataTypes, initialWebhookHeaders, initialWebhookSecret, initialMqttSection, initialSharedBroker) {
        val currentInterval = syncInterval.toIntOrNull() ?: initialSyncInterval
        currentInterval != initialSyncInterval || webhookUrls != initialWebhookUrls || enabledDataTypes != initialEnabledDataTypes || webhookHeaders != initialWebhookHeaders || webhookSecret != initialWebhookSecret || mqttWithPort() != (initialMqttSection to initialSharedBroker)
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
                    pluralStringResource(R.plurals.health_data_types_selected, enabledDataTypes.size, enabledDataTypes.size),
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
                            Toast.makeText(context, resources.getString(R.string.health_could_not_open), Toast.LENGTH_SHORT).show()
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
                            stringResource(R.string.health_open_app),
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
            DashboardCard()

            // Keystore outage: secrets cannot be read or saved, so say so rather than let
            // syncs fail with unexplained auth errors.
            if (preferencesManager.secretsUnavailable) {
                SecretsUnavailableBanner()
            }

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
                                "not_installed" -> stringResource(R.string.health_connect_not_installed)
                                "needs_update" -> stringResource(R.string.health_connect_needs_update)
                                "unavailable" -> stringResource(R.string.health_connect_not_available)
                                else -> stringResource(R.string.common_permissions_required)
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
                                    if (healthConnectUnavailableReason == "needs_update") stringResource(R.string.health_update) else stringResource(R.string.health_install),
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
                                        Toast.makeText(context, resources.getString(R.string.health_error_with_reason, e.message ?: ""), Toast.LENGTH_LONG).show()
                                    }
                                }
                            ) {
                                Text(stringResource(R.string.common_grant), color = Error, fontWeight = FontWeight.SemiBold)
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
                                stringResource(R.string.health_data_types_title),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                stringResource(R.string.health_data_types_selected_of, enabledDataTypes.size, HealthDataType.entries.size),
                                style = MaterialTheme.typography.bodySmall,
                                color = if (enabledDataTypes.isNotEmpty()) HealthPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Icon(
                            imageVector = Icons.Filled.ExpandMore,
                            contentDescription = if (isDataTypesExpanded) stringResource(R.string.common_collapse) else stringResource(R.string.common_expand),
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
                title = stringResource(R.string.sync_interval_title),
                subtitle = stringResource(R.string.sync_interval_subtitle)
            ) {
                OutlinedTextField(
                    value = syncInterval,
                    onValueChange = { syncInterval = it },
                    placeholder = { Text(stringResource(R.string.sync_interval_placeholder)) },
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
                title = stringResource(R.string.webhook_urls_title),
                subtitle = stringResource(R.string.webhook_urls_configured, webhookUrls.size)
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
                                    contentDescription = stringResource(R.string.common_remove),
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
                            placeholder = { Text(stringResource(R.string.webhook_url_placeholder)) },
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
                                    Toast.makeText(context, resources.getString(R.string.webhook_enter_valid_url), Toast.LENGTH_SHORT).show()
                                }
                            },
                            colors = IconButtonDefaults.filledIconButtonColors(containerColor = HealthPrimary)
                        ) {
                            Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.common_add), tint = Color.White)
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
                                stringResource(R.string.webhook_headers_title),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                if (webhookHeaders.isEmpty()) stringResource(R.string.common_none_configured)
                                else pluralStringResource(R.plurals.webhook_headers_count, webhookHeaders.size, webhookHeaders.size),
                                style = MaterialTheme.typography.bodySmall,
                                color = if (webhookHeaders.isNotEmpty()) HealthPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Icon(
                            imageVector = Icons.Filled.ExpandMore,
                            contentDescription = if (isHeadersExpanded) stringResource(R.string.common_collapse) else stringResource(R.string.common_expand),
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
                                            contentDescription = stringResource(R.string.common_remove),
                                            tint = MaterialTheme.colorScheme.error,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                            }

                            OutlinedTextField(
                                value = newHeaderKey,
                                onValueChange = { newHeaderKey = it },
                                placeholder = { Text(stringResource(R.string.webhook_header_name)) },
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
                                    placeholder = { Text(stringResource(R.string.webhook_header_value)) },
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
                                            Toast.makeText(context, resources.getString(R.string.webhook_enter_header_name_and_value), Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = HealthPrimary)
                                ) {
                                    Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.common_add), tint = Color.White)
                                }
                            }

                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                stringResource(R.string.webhook_hmac_secret_title),
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                stringResource(R.string.webhook_hmac_secret_description),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            OutlinedTextField(
                                value = webhookSecret,
                                onValueChange = { webhookSecret = it },
                                placeholder = { Text(stringResource(R.string.webhook_shared_secret)) },
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

            // Advanced - collapsible
            CollapsibleCard(
                title = stringResource(R.string.sync_advanced_title),
                subtitle = (if (includeDailyTotals) stringResource(R.string.health_daily_totals) else stringResource(R.string.health_no_daily_totals)) + ", " +
                    (if (allowHttpWebhooks) stringResource(R.string.sync_advanced_plain_http_allowed) else stringResource(R.string.sync_advanced_https_only)),
                expanded = isAdvancedExpanded,
                onToggle = { isAdvancedExpanded = !isAdvancedExpanded }
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.health_daily_totals_title), style = MaterialTheme.typography.bodyMedium)
                        Text(
                            stringResource(R.string.health_daily_totals_description),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = includeDailyTotals,
                        onCheckedChange = {
                            includeDailyTotals = it
                            preferencesManager.setIncludeDailyTotals(it)
                        }
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.webhook_allow_plain_http), style = MaterialTheme.typography.bodyMedium)
                        Text(
                            stringResource(R.string.webhook_allow_plain_http_description),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = allowHttpWebhooks,
                        onCheckedChange = {
                            allowHttpWebhooks = it
                            preferencesManager.setAllowHttpWebhooks(it)
                        }
                    )
                }
            }

            // Failure notifications (shared across Health Connect and Screen Time)
            CollapsibleCard(
                title = stringResource(R.string.health_notifications_title),
                subtitle = if (failureNotificationsEnabled) pluralStringResource(R.plurals.health_notifications_on, failureThreshold, failureThreshold)
                    else stringResource(R.string.health_notifications_off),
                subtitleColor = if (failureNotificationsEnabled) HealthPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                expanded = isNotificationsExpanded,
                onToggle = { isNotificationsExpanded = !isNotificationsExpanded }
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        stringResource(R.string.health_notifications_notify_after_failed),
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
                            stringResource(R.string.health_notifications_after_failures),
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

            // MQTT / Home Assistant Discovery (shared card with Screen Time)
            MqttSectionCard(
                description = stringResource(R.string.health_mqtt_description),
                otherSection = stringResource(R.string.main_title_screen_time),
                accent = HealthPrimary,
                section = mqttSection,
                onSectionChange = { mqttSection = it },
                sharedBroker = sharedBroker,
                onSharedBrokerChange = { sharedBroker = it },
                portText = mqttPortText,
                onPortTextChange = { mqttPortText = it },
                lastStatus = preferencesManager.getLastMqttStatus(MqttSection.HEALTH),
                expanded = isMqttExpanded,
                onToggle = { isMqttExpanded = !isMqttExpanded }
            )

            // Manual Sync
            SectionCard(
                title = stringResource(R.string.sync_manual_title),
                subtitle = stringResource(R.string.sync_manual_subtitle)
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
                                    syncMessage = resources.getString(R.string.health_connect_not_available_short)
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
                                val (savedMqttSection, savedSharedBroker) = mqttWithPort()
                                mqttSection = savedMqttSection
                                sharedBroker = savedSharedBroker
                                preferencesManager.setMqttSection(MqttSection.HEALTH, savedMqttSection)
                                preferencesManager.setSharedMqttBroker(savedSharedBroker)

                                val syncManager = HealthSyncManager(context)
                                val result = syncManager.performSync()

                                syncMessage = when {
                                    result.isSuccess -> {
                                        when (val syncResult = result.getOrThrow()) {
                                            is HealthSyncResult.NoData -> resources.getString(R.string.sync_no_new_data)
                                            is HealthSyncResult.Success -> {
                                                val count = syncResult.syncCounts.values.sum()
                                                resources.getQuantityString(R.plurals.health_synced_records, count, count)
                                            }
                                            is HealthSyncResult.Queued -> context.resources.getQuantityString(
                                                R.plurals.health_queued_records, syncResult.recordCount, syncResult.recordCount
                                            )
                                        }
                                    }
                                    else -> resources.getString(R.string.sync_failed_with_reason, result.exceptionOrNull()?.message ?: "")
                                }

                                // Update initial values so hasChanges reflects saved state
                                initialSyncInterval = currentInterval
                                initialWebhookUrls = webhookUrls
                                initialEnabledDataTypes = enabledDataTypes
                                initialWebhookHeaders = webhookHeaders
                                initialWebhookSecret = webhookSecret
                                initialMqttSection = mqttSection
                                initialSharedBroker = sharedBroker
                            } catch (e: Exception) {
                                syncMessage = resources.getString(R.string.sync_failed_with_reason, e.message ?: "")
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
                    Text(if (isSyncing) stringResource(R.string.sync_syncing) else stringResource(R.string.sync_now))
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
                                    Toast.makeText(context, result.exceptionOrNull()?.message ?: resources.getString(R.string.sync_preview_failed), Toast.LENGTH_SHORT).show()
                                }
                            } catch (e: Exception) {
                                Toast.makeText(context, resources.getString(R.string.sync_preview_failed_with_reason, e.message ?: ""), Toast.LENGTH_SHORT).show()
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
                    Text(if (isPreviewing) stringResource(R.string.sync_loading) else stringResource(R.string.sync_preview_data))
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
                                    if (result.isSuccess) resources.getString(R.string.health_test_ping_delivered)
                                    else resources.getString(R.string.health_test_ping_failed),
                                    Toast.LENGTH_SHORT
                                ).show()
                            } catch (e: Exception) {
                                Toast.makeText(context, resources.getString(R.string.health_test_ping_failed_with_reason, e.message ?: ""), Toast.LENGTH_SHORT).show()
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
                    Text(if (isPinging) stringResource(R.string.health_test_pinging) else stringResource(R.string.health_test_ping))
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
                                    Toast.makeText(context, result.exceptionOrNull()?.message ?: resources.getString(R.string.sync_export_failed), Toast.LENGTH_SHORT).show()
                                }
                            } catch (e: Exception) {
                                Toast.makeText(context, resources.getString(R.string.sync_export_failed_with_reason, e.message ?: ""), Toast.LENGTH_SHORT).show()
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
                    Text(if (isExporting) stringResource(R.string.sync_loading) else stringResource(R.string.sync_export_data))
                }

                OutlinedButton(
                    onClick = { if (backfillProgress == null) showBackfillDialog = true },
                    enabled = backfillProgress == null && enabledDataTypes.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = HealthPrimary)
                ) {
                    if (backfillProgress != null) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = HealthPrimary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Icon(Icons.Outlined.History, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(backfillProgress?.let { (done, total) -> stringResource(R.string.health_backfill_progress, done, total) }
                        ?: stringResource(R.string.health_backfill_history))
                }

                AnimatedVisibility(visible = syncMessage != null) {
                    syncMessage?.let { message ->
                        Text(
                            message,
                            modifier = Modifier.padding(top = 8.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (message.startsWith(stringResource(R.string.sync_failed_prefix))) Error else HealthPrimary
                        )
                    }
                }
            }

            if (showBackfillDialog) {
                // Without READ_HEALTH_DATA_HISTORY Health Connect only exposes the 30 days
                // before the first permission grant, so a 90/365 day backfill would silently
                // return recent data only (#39). Checked on every open; granting happens via
                // the normal permission flow.
                var hasHistoryPermission by remember { mutableStateOf(true) }
                LaunchedEffect(showBackfillDialog) {
                    hasHistoryPermission = HealthConnectManager.HISTORY_PERMISSION in
                        HealthConnectManager(context).getGrantedPermissions()
                }
                AlertDialog(
                    onDismissRequest = { showBackfillDialog = false },
                    title = { Text(stringResource(R.string.health_backfill_title)) },
                    text = {
                        Column {
                            Text(stringResource(R.string.health_backfill_dialog_description))
                            if (!hasHistoryPermission) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    stringResource(R.string.health_backfill_history_permission_warning),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Error
                                )
                                TextButton(onClick = {
                                    permissionLauncher.launch(HealthConnectManager.ALL_PERMISSIONS)
                                }) { Text(stringResource(R.string.health_backfill_grant_history)) }
                            }
                        }
                    },
                    confirmButton = {
                        Row {
                            listOf(30, 90, 365).forEach { days ->
                                TextButton(onClick = {
                                    showBackfillDialog = false
                                    scope.launch {
                                        backfillProgress = 0 to 1
                                        val result = HealthSyncManager(context).performBackfill(days) { done, total ->
                                            backfillProgress = done to total
                                        }
                                        backfillProgress = null
                                        syncMessage = result.fold(
                                            onSuccess = { resources.getQuantityString(R.plurals.health_backfill_complete, it, it) },
                                            onFailure = { resources.getString(R.string.sync_failed_with_reason, it.message ?: "") }
                                        )
                                    }
                                }) { Text(stringResource(R.string.health_backfill_days, days)) }
                            }
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showBackfillDialog = false }) { Text(stringResource(R.string.common_cancel)) }
                    }
                )
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
                                Toast.makeText(context, resources.getString(R.string.webhook_min_interval), Toast.LENGTH_SHORT).show()
                                return@launch
                            }
                            if (webhookUrls.isEmpty()) {
                                Toast.makeText(context, resources.getString(R.string.webhook_add_a_url), Toast.LENGTH_SHORT).show()
                                return@launch
                            }

                            preferencesManager.setHealthSyncIntervalMinutes(interval)
                            preferencesManager.setHealthWebhookUrls(webhookUrls)
                            preferencesManager.setHealthEnabledDataTypes(enabledDataTypes)
                            preferencesManager.setHealthWebhookHeaders(webhookHeaders)
                            preferencesManager.setHealthWebhookSecret(webhookSecret.trim())
                            val (savedMqttSection, savedSharedBroker) = mqttWithPort()
                            mqttSection = savedMqttSection
                            sharedBroker = savedSharedBroker
                            preferencesManager.setMqttSection(MqttSection.HEALTH, savedMqttSection)
                            preferencesManager.setSharedMqttBroker(savedSharedBroker)
                            (context.applicationContext as? LifeDashboardApplication)?.scheduleHealthSyncWork()

                            initialSyncInterval = interval
                            initialWebhookUrls = webhookUrls
                            initialEnabledDataTypes = enabledDataTypes
                            initialWebhookHeaders = webhookHeaders
                            initialWebhookSecret = webhookSecret
                            initialMqttSection = mqttSection
                            initialSharedBroker = sharedBroker
                            Toast.makeText(context, resources.getString(R.string.common_saved), Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Success)
                ) {
                    Icon(Icons.Filled.Check, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.sync_save_changes), fontWeight = FontWeight.SemiBold)
                }
            }

            // Status
            Text(
                pluralStringResource(R.plurals.webhook_status_syncing, webhookUrls.size, syncInterval, webhookUrls.size),
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
                title = { Text(stringResource(R.string.sync_data_preview_title)) },
                text = {
                    Column {
                        Text(
                            stringResource(R.string.sync_preview_payload_description),
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
                        Text(stringResource(R.string.common_close), color = HealthPrimary)
                    }
                }
            )
        }

        // Export Format Dialog
        if (showExportFormatDialog && exportJsonData != null) {
            AlertDialog(
                onDismissRequest = { showExportFormatDialog = false },
                title = { Text(stringResource(R.string.sync_export_data)) },
                text = {
                    Text(
                        stringResource(R.string.health_export_dialog_description),
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
                        Text(stringResource(R.string.common_json), color = HealthPrimary)
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
                        Text(stringResource(R.string.common_csv), color = HealthPrimary)
                    }
                }
            )
        }

        // Permission Modal
        if (showPermissionModal && selectedDataTypeForPermission != null) {
            AlertDialog(
                onDismissRequest = { showPermissionModal = false },
                title = { Text(stringResource(R.string.health_permission_required_title)) },
                text = { Text(stringResource(R.string.health_permission_needed, selectedDataTypeForPermission!!.displayName)) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            val permission = HealthPermission.getReadPermission(selectedDataTypeForPermission!!.recordClass)
                            permissionLauncher.launch(setOf(permission))
                            showPermissionModal = false
                        }
                    ) {
                        Text(stringResource(R.string.common_grant), color = HealthPrimary)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showPermissionModal = false }) {
                        Text(stringResource(R.string.common_cancel))
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
