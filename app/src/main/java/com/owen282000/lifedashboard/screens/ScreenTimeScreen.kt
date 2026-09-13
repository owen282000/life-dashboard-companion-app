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
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import com.owen282000.lifedashboard.*
import com.owen282000.lifedashboard.R
import com.owen282000.lifedashboard.ui.theme.*

@Composable
fun ScreenTimeScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val resources = LocalResources.current
    val preferencesManager = remember { PreferencesManager(context) }
    val screenTimeManager = remember { ScreenTimeManager(context, preferencesManager) }

    var initialSyncInterval by remember { mutableStateOf(preferencesManager.getScreenTimeSyncIntervalMinutes()) }
    var initialWebhookUrls by remember { mutableStateOf(preferencesManager.getScreenTimeWebhookUrls()) }
    var initialDayBoundaryHour by remember { mutableStateOf(preferencesManager.getScreenTimeDayBoundaryHour()) }
    var initialUseDayBoundary by remember { mutableStateOf(preferencesManager.useScreenTimeDayBoundary()) }
    var initialWebhookHeaders by remember { mutableStateOf(preferencesManager.getScreenTimeWebhookHeaders()) }
    var initialWebhookSecret by remember { mutableStateOf(preferencesManager.getScreenTimeWebhookSecret() ?: "") }

    var syncInterval by remember { mutableStateOf(initialSyncInterval.toString()) }
    var webhookUrls by remember { mutableStateOf(initialWebhookUrls) }
    var dayBoundaryHour by remember { mutableStateOf(initialDayBoundaryHour.toString()) }
    var useDayBoundary by remember { mutableStateOf(initialUseDayBoundary) }
    var webhookHeaders by remember { mutableStateOf(initialWebhookHeaders) }
    var webhookSecret by remember { mutableStateOf(initialWebhookSecret) }
    var newHeaderKey by remember { mutableStateOf("") }
    var newHeaderValue by remember { mutableStateOf("") }
    var isHeadersExpanded by remember { mutableStateOf(false) }
    var newUrl by remember { mutableStateOf("") }
    var allowHttpWebhooks by remember { mutableStateOf(preferencesManager.allowHttpWebhooks()) }
    var initialMqttSection by remember { mutableStateOf(preferencesManager.getMqttSection(MqttSection.SCREEN_TIME)) }
    var initialSharedBroker by remember { mutableStateOf(preferencesManager.getSharedMqttBroker()) }
    var mqttSection by remember { mutableStateOf(initialMqttSection) }
    var sharedBroker by remember { mutableStateOf(initialSharedBroker) }
    var mqttPortText by remember { mutableStateOf((if (initialMqttSection.useSharedBroker) initialSharedBroker.port else initialMqttSection.ownBroker.port).toString()) }
    var isMqttExpanded by remember { mutableStateOf(false) }

    // Port lives in its own text field; fold it into whichever broker is active before comparing or saving.
    fun mqttWithPort(): Pair<MqttSectionSettings, MqttBroker> {
        val port = mqttPortText.toIntOrNull()
        return if (mqttSection.useSharedBroker) mqttSection to sharedBroker.copy(port = port ?: sharedBroker.port)
        else mqttSection.copy(ownBroker = mqttSection.ownBroker.copy(port = port ?: mqttSection.ownBroker.port)) to sharedBroker
    }
    var isAdvancedExpanded by remember { mutableStateOf(false) }
    var isSyncing by remember { mutableStateOf(false) }
    var isPreviewing by remember { mutableStateOf(false) }
    var isExporting by remember { mutableStateOf(false) }
    var showExportFormatDialog by remember { mutableStateOf(false) }
    var exportJsonData by remember { mutableStateOf<String?>(null) }
    var previewData by remember { mutableStateOf<String?>(null) }
    var dayBoundaryExpanded by remember { mutableStateOf(false) }
    var syncMessage by remember { mutableStateOf<String?>(null) }
    var hasPermission by remember { mutableStateOf(screenTimeManager.hasPermission()) }

    LaunchedEffect(Unit) {
        hasPermission = screenTimeManager.hasPermission()
    }

    val hasChanges = remember(syncInterval, webhookUrls, dayBoundaryHour, useDayBoundary, webhookHeaders, webhookSecret, mqttSection, sharedBroker, mqttPortText, initialSyncInterval, initialWebhookUrls, initialDayBoundaryHour, initialUseDayBoundary, initialWebhookHeaders, initialWebhookSecret, initialMqttSection, initialSharedBroker) {
        val currentInterval = syncInterval.toIntOrNull() ?: initialSyncInterval
        val currentBoundaryHour = dayBoundaryHour.toIntOrNull() ?: initialDayBoundaryHour
        currentInterval != initialSyncInterval || webhookUrls != initialWebhookUrls || currentBoundaryHour != initialDayBoundaryHour || useDayBoundary != initialUseDayBoundary || webhookHeaders != initialWebhookHeaders || webhookSecret != initialWebhookSecret || mqttWithPort() != (initialMqttSection to initialSharedBroker)
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
                stringResource(R.string.screentime_header),
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
                            stringResource(R.string.common_permissions_required),
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
                            Text(stringResource(R.string.common_grant), color = Error, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }

            // At-a-glance stats, consistent with the Health Connect dashboard card
            if (hasPermission) {
                ScreenTimeDashboardCard(refreshKey = syncMessage)

                // Keystore outage: secrets cannot be read or saved, so say so rather than let
                // syncs fail with unexplained auth errors.
                if (preferencesManager.secretsUnavailable) {
                    SecretsUnavailableBanner()
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
                                stringResource(R.string.screentime_day_boundary_title),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                if (useDayBoundary) stringResource(R.string.screentime_day_boundary_enabled, dayBoundaryHour)
                                else stringResource(R.string.screentime_day_boundary_disabled),
                                style = MaterialTheme.typography.bodySmall,
                                color = if (useDayBoundary) ScreenTimePrimary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Icon(
                            imageVector = Icons.Filled.ExpandMore,
                            contentDescription = if (dayBoundaryExpanded) stringResource(R.string.common_collapse) else stringResource(R.string.common_expand),
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
                                    stringResource(R.string.screentime_day_boundary_enable),
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
                                        stringResource(R.string.screentime_day_boundary_description),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    OutlinedTextField(
                                        value = dayBoundaryHour,
                                        onValueChange = { dayBoundaryHour = it },
                                        placeholder = { Text(stringResource(R.string.screentime_day_boundary_hour_placeholder)) },
                                        label = { Text(stringResource(R.string.screentime_day_boundary_hour_label)) },
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
                        focusedBorderColor = ScreenTimePrimary,
                        cursorColor = ScreenTimePrimary
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
                                    Toast.makeText(context, resources.getString(R.string.webhook_enter_valid_url), Toast.LENGTH_SHORT).show()
                                }
                            },
                            colors = IconButtonDefaults.filledIconButtonColors(containerColor = ScreenTimePrimary)
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
                                color = if (webhookHeaders.isNotEmpty()) ScreenTimePrimary else MaterialTheme.colorScheme.onSurfaceVariant
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
                                    focusedBorderColor = ScreenTimePrimary,
                                    cursorColor = ScreenTimePrimary
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
                                            Toast.makeText(context, resources.getString(R.string.webhook_enter_header_name_and_value), Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = ScreenTimePrimary)
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
                                    focusedBorderColor = ScreenTimePrimary,
                                    cursorColor = ScreenTimePrimary
                                )
                            )
                        }
                    }
                }
            }

            // Manual Sync
            // MQTT / Home Assistant Discovery (shared card with Health Connect)
            MqttSectionCard(
                description = stringResource(R.string.screentime_mqtt_description),
                otherSection = stringResource(R.string.main_title_health_connect),
                accent = ScreenTimePrimary,
                section = mqttSection,
                onSectionChange = { mqttSection = it },
                sharedBroker = sharedBroker,
                onSharedBrokerChange = { sharedBroker = it },
                portText = mqttPortText,
                onPortTextChange = { mqttPortText = it },
                lastStatus = preferencesManager.getLastMqttStatus(MqttSection.SCREEN_TIME),
                expanded = isMqttExpanded,
                onToggle = { isMqttExpanded = !isMqttExpanded }
            )

            // Advanced - collapsible
            CollapsibleCard(
                title = stringResource(R.string.sync_advanced_title),
                subtitle = if (allowHttpWebhooks) stringResource(R.string.sync_advanced_plain_http_allowed) else stringResource(R.string.sync_advanced_https_only),
                expanded = isAdvancedExpanded,
                onToggle = { isAdvancedExpanded = !isAdvancedExpanded }
            ) {
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
                                if (!screenTimeManager.hasPermission()) {
                                    syncMessage = resources.getString(R.string.screentime_permission_not_granted)
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
                                            is ScreenTimeSyncResult.NoData -> resources.getString(R.string.sync_no_new_data)
                                            is ScreenTimeSyncResult.Success -> context.resources.getQuantityString(
                                                R.plurals.screentime_apps_synced, syncResult.appCount, syncResult.appCount
                                            )
                                            is ScreenTimeSyncResult.Queued -> context.resources.getQuantityString(
                                                R.plurals.screentime_apps_queued, syncResult.appCount, syncResult.appCount
                                            )
                                        }
                                    }
                                    else -> resources.getString(R.string.sync_failed_with_reason, result.exceptionOrNull()?.message ?: "")
                                }
                            } catch (e: Exception) {
                                syncMessage = resources.getString(R.string.sync_failed_with_reason, e.message ?: "")
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
                    Text(if (isSyncing) stringResource(R.string.sync_syncing) else stringResource(R.string.sync_now))
                }

                Spacer(modifier = Modifier.height(8.dp))

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
                                    Toast.makeText(context, result.exceptionOrNull()?.message ?: resources.getString(R.string.sync_preview_failed), Toast.LENGTH_SHORT).show()
                                }
                            } catch (e: Exception) {
                                Toast.makeText(context, resources.getString(R.string.sync_preview_failed_with_reason, e.message ?: ""), Toast.LENGTH_SHORT).show()
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
                    Text(if (isPreviewing) stringResource(R.string.sync_loading) else stringResource(R.string.sync_preview_data))
                }

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedButton(
                    onClick = {
                        if (isExporting) return@OutlinedButton
                        scope.launch {
                            isExporting = true
                            try {
                                val syncManager = ScreenTimeSyncManager(context)
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
                    enabled = !isExporting && hasPermission,
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = ScreenTimePrimary)
                ) {
                    if (isExporting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = ScreenTimePrimary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(if (isExporting) stringResource(R.string.sync_loading) else stringResource(R.string.sync_export_data))
                }

                AnimatedVisibility(visible = syncMessage != null) {
                    syncMessage?.let { message ->
                        Text(
                            message,
                            modifier = Modifier.padding(top = 8.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (message.startsWith(stringResource(R.string.sync_failed_prefix))) Error else ScreenTimePrimary
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
                                Toast.makeText(context, resources.getString(R.string.webhook_min_interval), Toast.LENGTH_SHORT).show()
                                return@launch
                            }
                            if (webhookUrls.isEmpty()) {
                                Toast.makeText(context, resources.getString(R.string.webhook_add_a_url), Toast.LENGTH_SHORT).show()
                                return@launch
                            }
                            val boundaryHour = dayBoundaryHour.toIntOrNull()
                            if (boundaryHour == null || boundaryHour < 0 || boundaryHour > 23) {
                                Toast.makeText(context, resources.getString(R.string.screentime_day_boundary_hour_invalid), Toast.LENGTH_SHORT).show()
                                return@launch
                            }

                            preferencesManager.setScreenTimeSyncIntervalMinutes(interval)
                            preferencesManager.setScreenTimeWebhookUrls(webhookUrls)
                            preferencesManager.setScreenTimeDayBoundaryHour(boundaryHour)
                            preferencesManager.setUseScreenTimeDayBoundary(useDayBoundary)
                            preferencesManager.setScreenTimeWebhookHeaders(webhookHeaders)
                            preferencesManager.setScreenTimeWebhookSecret(webhookSecret.trim())
                            val (savedMqttSection, savedSharedBroker) = mqttWithPort()
                            mqttSection = savedMqttSection
                            sharedBroker = savedSharedBroker
                            preferencesManager.setMqttSection(MqttSection.SCREEN_TIME, savedMqttSection)
                            preferencesManager.setSharedMqttBroker(savedSharedBroker)
                            (context.applicationContext as? LifeDashboardApplication)?.scheduleScreenTimeSyncWork()

                            initialSyncInterval = interval
                            initialWebhookUrls = webhookUrls
                            initialDayBoundaryHour = boundaryHour
                            initialUseDayBoundary = useDayBoundary
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
                        Text(stringResource(R.string.common_close), color = ScreenTimePrimary)
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
                        stringResource(R.string.screentime_export_dialog_description),
                        style = MaterialTheme.typography.bodyMedium
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        showExportFormatDialog = false
                        val exportManager = ExportManager(context)
                        val timestamp = java.time.LocalDateTime.now()
                            .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
                        exportManager.shareFile(exportJsonData!!, "screen_time_$timestamp.json", "application/json")
                    }) {
                        Text(stringResource(R.string.common_json), color = ScreenTimePrimary)
                    }
                },
                dismissButton = {
                    TextButton(onClick = {
                        showExportFormatDialog = false
                        val exportManager = ExportManager(context)
                        val timestamp = java.time.LocalDateTime.now()
                            .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
                        exportManager.shareFile(exportJsonData!!, "screen_time_$timestamp.csv", "text/csv")
                    }) {
                        Text(stringResource(R.string.common_csv), color = ScreenTimePrimary)
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
                // Weighted so a long translation wraps instead of colliding with the
                // subtitle: "Sync Interval" is 13 characters in English but 26 in German.
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f, fill = false)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.End,
                    modifier = Modifier.weight(1f, fill = false)
                )
            }
            Spacer(modifier = Modifier.height(10.dp))
            content()
        }
    }
}
