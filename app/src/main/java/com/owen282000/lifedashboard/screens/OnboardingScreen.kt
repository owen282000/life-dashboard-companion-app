package com.owen282000.lifedashboard.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.MonitorHeart
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.owen282000.lifedashboard.LogType
import com.owen282000.lifedashboard.MqttBroker
import com.owen282000.lifedashboard.MqttSection
import com.owen282000.lifedashboard.OnboardingSupport
import com.owen282000.lifedashboard.OnboardingSupport.Step
import com.owen282000.lifedashboard.PreferencesManager
import com.owen282000.lifedashboard.R
import com.owen282000.lifedashboard.WebhookManager
import com.owen282000.lifedashboard.ui.theme.HealthPrimary
import kotlinx.coroutines.launch

// The tabs colour their accents with HealthPrimary directly rather than through the theme,
// so the wizard does the same to look like the rest of the app.
private val Accent = HealthPrimary
private val ChoiceShape = RoundedCornerShape(20.dp)

/**
 * First-run wizard. A fresh install used to drop the user onto a screen with 33 toggles and
 * an empty configuration; this asks three questions (what to sync, where it should go, and
 * which health types to start with) and leaves everything else to the regular screens.
 * Every choice here is changeable later, and the whole thing can be skipped.
 */
@Composable
fun OnboardingScreen(onFinished: () -> Unit) {
    val context = LocalContext.current
    val preferencesManager = remember { PreferencesManager(context) }
    val scope = rememberCoroutineScope()

    var healthConnect by remember { mutableStateOf(true) }
    var screenTime by remember { mutableStateOf(true) }

    var useWebhook by remember { mutableStateOf(false) }
    var webhookUrl by remember { mutableStateOf("") }
    var pingResult by remember { mutableStateOf<Boolean?>(null) }
    var pinging by remember { mutableStateOf(false) }
    var allowHttp by remember { mutableStateOf(preferencesManager.allowHttpWebhooks()) }

    var useMqtt by remember { mutableStateOf(false) }
    var mqttHost by remember { mutableStateOf("") }
    var mqttPort by remember { mutableStateOf("1883") }
    var mqttTls by remember { mutableStateOf(false) }
    var mqttUser by remember { mutableStateOf("") }
    var mqttPass by remember { mutableStateOf("") }

    var preset by remember { mutableStateOf(OnboardingSupport.TypePreset.ESSENTIALS) }

    var stepIndex by remember { mutableStateOf(0) }
    val steps = OnboardingSupport.stepsFor(healthConnect)
    val step = steps[stepIndex.coerceIn(0, steps.lastIndex)]

    fun finish(applyChoices: Boolean) {
        if (applyChoices) {
            val url = webhookUrl.trim()
            if (useWebhook && url.isNotBlank()) {
                if (healthConnect) preferencesManager.setHealthWebhookUrls(listOf(url))
                if (screenTime) preferencesManager.setScreenTimeWebhookUrls(listOf(url))
            }
            if (useMqtt && mqttHost.isNotBlank()) {
                preferencesManager.setSharedMqttBroker(
                    MqttBroker(
                        host = mqttHost.trim(),
                        port = mqttPort.toIntOrNull() ?: 1883,
                        useTls = mqttTls,
                        username = mqttUser.trim().ifBlank { null },
                        password = mqttPass.ifBlank { null }
                    )
                )
                val sections = buildList {
                    if (healthConnect) add(MqttSection.HEALTH)
                    if (screenTime) add(MqttSection.SCREEN_TIME)
                }
                for (section in sections) {
                    preferencesManager.setMqttSection(
                        section,
                        preferencesManager.getMqttSection(section).copy(
                            enabled = true,
                            useSharedBroker = true
                        )
                    )
                }
            }
            if (healthConnect) {
                preferencesManager.setHealthEnabledDataTypes(OnboardingSupport.typesFor(preset))
            }
        }
        preferencesManager.setOnboardingCompleted()
        onFinished()
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(modifier = Modifier.fillMaxSize()) {
            // A soft accent wash at the top of every step, like a light behind the header.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(320.dp)
                    .drawBehind {
                        drawRect(
                            Brush.radialGradient(
                                colors = listOf(Accent.copy(alpha = 0.16f), Color.Transparent),
                                center = Offset(size.width / 2f, 0f),
                                radius = size.width * 0.75f
                            )
                        )
                    }
            )

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 12.dp)
            ) {
                StepIndicator(current = steps.indexOf(step), total = steps.size)

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    when (step) {
                        Step.WELCOME -> WelcomeStep()

                        Step.FEATURES -> {
                            Headline(
                                stringResource(R.string.onboarding_features_title),
                                stringResource(R.string.onboarding_features_body)
                            )
                            ChoiceCard(
                                icon = Icons.Outlined.FavoriteBorder,
                                title = stringResource(R.string.onboarding_feature_health),
                                description = stringResource(R.string.onboarding_feature_health_desc),
                                selected = healthConnect,
                                onClick = { healthConnect = !healthConnect }
                            )
                            ChoiceCard(
                                icon = Icons.Outlined.PhoneAndroid,
                                title = stringResource(R.string.onboarding_feature_screen),
                                description = stringResource(R.string.onboarding_feature_screen_desc),
                                selected = screenTime,
                                onClick = { screenTime = !screenTime }
                            )
                        }

                        Step.DESTINATION -> {
                            Headline(
                                stringResource(R.string.onboarding_destination_title),
                                stringResource(R.string.onboarding_destination_body)
                            )
                            ChoiceCard(
                                icon = Icons.Outlined.Link,
                                title = stringResource(R.string.onboarding_webhook_option),
                                description = stringResource(R.string.onboarding_webhook_desc),
                                selected = useWebhook,
                                onClick = { useWebhook = !useWebhook }
                            ) {
                                FilledField(
                                    accent = Accent,
                                    value = webhookUrl,
                                    onValueChange = { webhookUrl = it; pingResult = null },
                                    label = stringResource(R.string.webhook_add_a_url)
                                )
                                Text(
                                    stringResource(
                                        when {
                                            healthConnect && screenTime -> R.string.onboarding_webhook_applied
                                            healthConnect -> R.string.onboarding_webhook_applied_health
                                            else -> R.string.onboarding_webhook_applied_screen
                                        }
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                // Plain HTTP is opt-in (the tabs have the same switch). Surface it
                                // here as soon as the URL needs it, so the test ping does not fail
                                // with a hint to go read the logs on the very first screen.
                                if (webhookUrl.trim().startsWith("http://", ignoreCase = true)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                stringResource(R.string.webhook_allow_plain_http),
                                                style = MaterialTheme.typography.bodyMedium
                                            )
                                            Text(
                                                stringResource(R.string.webhook_allow_plain_http_description),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Switch(
                                            colors = SwitchDefaults.colors(checkedTrackColor = Accent),
                                            checked = allowHttp,
                                            onCheckedChange = {
                                                allowHttp = it
                                                preferencesManager.setAllowHttpWebhooks(it)
                                                pingResult = null
                                            }
                                        )
                                    }
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    OutlinedButton(
                                        shape = CircleShape,
                                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Accent),
                                        border = BorderStroke(1.dp, Accent.copy(alpha = 0.4f)),
                                        enabled = webhookUrl.isNotBlank() && !pinging,
                                        onClick = {
                                            scope.launch {
                                                pinging = true
                                                pingResult = try {
                                                    val payload = """{"test":true,"message":"Test ping from Life Dashboard Companion","timestamp":"${java.time.Instant.now()}","source":"onboarding"}"""
                                                    WebhookManager(
                                                        webhookUrls = listOf(webhookUrl.trim()),
                                                        context = context,
                                                        dataType = "test",
                                                        recordCount = 0,
                                                        logType = LogType.HEALTH_CONNECT
                                                    ).postData(payload).isSuccess
                                                } catch (e: Exception) {
                                                    false
                                                }
                                                pinging = false
                                            }
                                        }
                                    ) {
                                        Text(
                                            stringResource(
                                                if (pinging) R.string.health_test_pinging
                                                else R.string.health_test_ping
                                            )
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(12.dp))
                                    pingResult?.let { ok ->
                                        Text(
                                            stringResource(
                                                if (ok) R.string.health_test_ping_delivered
                                                else R.string.health_test_ping_failed
                                            ),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = if (ok) Accent else MaterialTheme.colorScheme.error
                                        )
                                    }
                                }
                            }

                            ChoiceCard(
                                icon = Icons.Outlined.Home,
                                title = stringResource(R.string.onboarding_mqtt_option),
                                description = stringResource(R.string.onboarding_mqtt_desc),
                                selected = useMqtt,
                                onClick = { useMqtt = !useMqtt }
                            ) {
                                FilledField(
                                    accent = Accent,
                                    value = mqttHost,
                                    onValueChange = { mqttHost = it },
                                    label = stringResource(R.string.mqtt_broker_host)
                                )
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    FilledField(
                                        accent = Accent,
                                        value = mqttPort,
                                        onValueChange = { mqttPort = it },
                                        label = stringResource(R.string.mqtt_port),
                                        keyboardType = KeyboardType.Number,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Spacer(modifier = Modifier.width(16.dp))
                                    Text(stringResource(R.string.mqtt_tls), style = MaterialTheme.typography.bodyMedium)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Switch(
                                        checked = mqttTls,
                                        onCheckedChange = { mqttTls = it },
                                        colors = SwitchDefaults.colors(checkedTrackColor = Accent)
                                    )
                                }
                                FilledField(
                                    accent = Accent,
                                    value = mqttUser,
                                    onValueChange = { mqttUser = it },
                                    label = stringResource(R.string.mqtt_username)
                                )
                                FilledField(
                                    accent = Accent,
                                    value = mqttPass,
                                    onValueChange = { mqttPass = it },
                                    label = stringResource(R.string.mqtt_password),
                                    password = true
                                )
                            }

                            TextButton(
                                colors = ButtonDefaults.textButtonColors(contentColor = Accent),
                                onClick = {
                                    useWebhook = false
                                    useMqtt = false
                                    stepIndex += 1
                                }
                            ) {
                                Text(stringResource(R.string.onboarding_skip_destination), fontWeight = FontWeight.SemiBold)
                            }
                        }

                        Step.TYPES -> {
                            Headline(
                                stringResource(R.string.onboarding_types_title),
                                stringResource(R.string.onboarding_types_body)
                            )
                            ChoiceCard(
                                icon = Icons.Outlined.MonitorHeart,
                                title = stringResource(R.string.onboarding_types_essentials),
                                description = stringResource(R.string.onboarding_types_essentials_desc),
                                selected = preset == OnboardingSupport.TypePreset.ESSENTIALS,
                                onClick = { preset = OnboardingSupport.TypePreset.ESSENTIALS }
                            )
                            ChoiceCard(
                                icon = Icons.Outlined.GridView,
                                title = stringResource(R.string.onboarding_types_all),
                                description = stringResource(R.string.onboarding_types_all_desc),
                                selected = preset == OnboardingSupport.TypePreset.ALL,
                                onClick = { preset = OnboardingSupport.TypePreset.ALL }
                            )
                            ChoiceCard(
                                icon = Icons.Outlined.Schedule,
                                title = stringResource(R.string.onboarding_types_later),
                                description = stringResource(R.string.onboarding_types_later_desc),
                                selected = preset == OnboardingSupport.TypePreset.LATER,
                                onClick = { preset = OnboardingSupport.TypePreset.LATER }
                            )
                        }

                        Step.DONE -> DoneStep(
                            healthConnect = healthConnect,
                            screenTime = screenTime,
                            destinations = listOfNotNull(
                                if (useWebhook) webhookUrl.trim().ifBlank { stringResource(R.string.onboarding_webhook_option) } else null,
                                if (useMqtt) mqttHost.trim().ifBlank { stringResource(R.string.onboarding_mqtt_option) } else null
                            ),
                            preset = preset
                        )
                    }
                }

                // Bottom controls
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 20.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    when (step) {
                        Step.WELCOME -> {
                            AccentTextButton(stringResource(R.string.onboarding_skip)) { finish(applyChoices = false) }
                            PrimaryButton(stringResource(R.string.onboarding_get_started)) { stepIndex = 1 }
                        }

                        Step.DONE -> {
                            AccentTextButton(stringResource(R.string.common_back)) { stepIndex -= 1 }
                            PrimaryButton(stringResource(R.string.onboarding_open_app)) { finish(applyChoices = true) }
                        }

                        else -> {
                            AccentTextButton(stringResource(R.string.common_back)) { stepIndex -= 1 }
                            PrimaryButton(
                                stringResource(R.string.onboarding_next),
                                enabled = step != Step.FEATURES || healthConnect || screenTime
                            ) { stepIndex += 1 }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StepIndicator(current: Int, total: Int) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 26.dp, bottom = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            repeat(total) { i ->
                Box(
                    modifier = Modifier
                        .size(width = 44.dp, height = 5.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(
                            if (i <= current) Accent
                            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)
                        )
                )
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            stringResource(R.string.onboarding_step_of, current + 1, total).uppercase(),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.12.em,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun WelcomeStep() {
    // The brand mark with a soft glow behind it: the launcher foreground is the same vector,
    // drawn oversized so the pulse fills the hero rather than the icon's safe zone.
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(150.dp),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(150.dp)
                .drawBehind {
                    drawCircle(
                        Brush.radialGradient(
                            colors = listOf(Accent.copy(alpha = 0.22f), Color.Transparent)
                        )
                    )
                }
        )
        Image(
            painter = painterResource(R.drawable.ic_launcher_foreground),
            contentDescription = null,
            modifier = Modifier.requiredSize(250.dp)
        )
    }
    Text(
        stringResource(R.string.onboarding_welcome_title),
        fontSize = 31.sp,
        lineHeight = 36.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = (-0.6).sp,
        textAlign = TextAlign.Center
    )
    Text(
        stringResource(R.string.onboarding_welcome_body),
        style = MaterialTheme.typography.bodyLarge,
        lineHeight = 24.sp,
        textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun Headline(title: String, body: String) {
    Text(
        title,
        fontSize = 25.sp,
        lineHeight = 30.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = (-0.4).sp,
        textAlign = TextAlign.Center
    )
    Text(
        body,
        style = MaterialTheme.typography.bodyMedium,
        lineHeight = 21.sp,
        textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 6.dp)
    )
}

@Composable
private fun DoneStep(
    healthConnect: Boolean,
    screenTime: Boolean,
    destinations: List<String>,
    preset: OnboardingSupport.TypePreset
) {
    Box(
        modifier = Modifier
            .padding(top = 8.dp, bottom = 6.dp)
            .size(72.dp)
            .clip(CircleShape)
            .background(Accent.copy(alpha = 0.14f)),
        contentAlignment = Alignment.Center
    ) {
        Icon(Icons.Rounded.Check, contentDescription = null, tint = Accent, modifier = Modifier.size(36.dp))
    }
    Text(
        stringResource(R.string.onboarding_done_title),
        fontSize = 25.sp,
        lineHeight = 30.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = (-0.4).sp,
        textAlign = TextAlign.Center
    )

    val syncing = listOfNotNull(
        if (healthConnect) stringResource(R.string.onboarding_feature_health) else null,
        if (screenTime) stringResource(R.string.onboarding_feature_screen) else null
    ).joinToString(" + ")
    val destination = destinations.joinToString(" + ").ifBlank { stringResource(R.string.onboarding_summary_none) }
    val types = stringResource(
        when (preset) {
            OnboardingSupport.TypePreset.ESSENTIALS -> R.string.onboarding_summary_essentials
            OnboardingSupport.TypePreset.ALL -> R.string.onboarding_summary_all
            OnboardingSupport.TypePreset.LATER -> R.string.onboarding_summary_later
        }
    )
    val summary = buildString {
        append(stringResource(R.string.onboarding_summary_sync)).append(": ").append(syncing)
        append(" · ").append(stringResource(R.string.onboarding_summary_dest)).append(": ").append(destination)
        if (healthConnect) {
            append(" · ").append(stringResource(R.string.onboarding_summary_types)).append(": ").append(types)
        }
    }
    Text(
        summary,
        style = MaterialTheme.typography.bodyMedium,
        textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Text(
        stringResource(R.string.onboarding_done_short),
        style = MaterialTheme.typography.bodyMedium,
        textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 6.dp)
    )

    var number = 1
    if (healthConnect) {
        TaskCard(number++, stringResource(R.string.onboarding_task_health), stringResource(R.string.onboarding_task_health_sub))
    }
    if (screenTime) {
        TaskCard(number, stringResource(R.string.onboarding_task_screen), stringResource(R.string.onboarding_task_screen_sub))
    }
}

@Composable
private fun TaskCard(number: Int, title: String, subtitle: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
        shadowElevation = 2.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(Accent.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Text(number.toString(), color = Accent, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }
            Spacer(modifier = Modifier.width(14.dp))
            Column {
                Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/**
 * A selectable card: icon tile, title and description in a row, with optional content that
 * unfolds underneath while selected. Selection shows as an accent border plus a filled tile,
 * so no radio button is needed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChoiceCard(
    icon: ImageVector,
    title: String,
    description: String,
    selected: Boolean,
    onClick: () -> Unit,
    content: (@Composable () -> Unit)? = null
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = ChoiceShape,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            if (selected) 2.dp else 1.dp,
            if (selected) Accent else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
        ),
        shadowElevation = 2.dp
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(13.dp))
                        .background(if (selected) Accent else Accent.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        icon,
                        contentDescription = null,
                        tint = if (selected) Color.White else Accent,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(modifier = Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (selected && content != null) {
                content()
            }
        }
    }
}

@Composable
private fun PrimaryButton(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = CircleShape,
        colors = ButtonDefaults.buttonColors(containerColor = Accent),
        elevation = ButtonDefaults.buttonElevation(defaultElevation = 4.dp),
        contentPadding = PaddingValues(horizontal = 26.dp, vertical = 14.dp)
    ) {
        Text(label, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun AccentTextButton(label: String, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        colors = ButtonDefaults.textButtonColors(contentColor = Accent),
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 14.dp)
    ) {
        Text(label, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    }
}
