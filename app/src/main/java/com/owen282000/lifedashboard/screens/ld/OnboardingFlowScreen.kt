package com.owen282000.lifedashboard.screens.ld

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.owen282000.lifedashboard.Destination
import com.owen282000.lifedashboard.Destinations
import com.owen282000.lifedashboard.FailureReason
import com.owen282000.lifedashboard.HealthPermissionRequests
import com.owen282000.lifedashboard.LogType
import com.owen282000.lifedashboard.MqttBroker
import com.owen282000.lifedashboard.MqttSection
import com.owen282000.lifedashboard.MqttSupport
import com.owen282000.lifedashboard.OnboardingSupport
import com.owen282000.lifedashboard.OnboardingSupport.Step
import com.owen282000.lifedashboard.OnboardingSupport.Task
import com.owen282000.lifedashboard.OnboardingSupport.TypePreset
import com.owen282000.lifedashboard.PairingSource
import com.owen282000.lifedashboard.PreferencesManager
import com.owen282000.lifedashboard.R
import com.owen282000.lifedashboard.TestPing
import com.owen282000.lifedashboard.screens.text
import com.owen282000.lifedashboard.ui.ld.LDButton
import com.owen282000.lifedashboard.ui.ld.LDButtonKind
import com.owen282000.lifedashboard.ui.ld.LDButtonSize
import com.owen282000.lifedashboard.ui.ld.LDCard
import com.owen282000.lifedashboard.ui.ld.LDFieldRow
import com.owen282000.lifedashboard.ui.ld.LDFooterBar
import com.owen282000.lifedashboard.ui.ld.LDGap
import com.owen282000.lifedashboard.ui.ld.LDHeading
import com.owen282000.lifedashboard.ui.ld.LDIcons
import com.owen282000.lifedashboard.ui.ld.LDKeyValue
import com.owen282000.lifedashboard.ui.ld.LDKeyValueList
import com.owen282000.lifedashboard.ui.ld.LDMotion
import com.owen282000.lifedashboard.ui.ld.LDNote
import com.owen282000.lifedashboard.ui.ld.LDNoteSize
import com.owen282000.lifedashboard.ui.ld.LDOption
import com.owen282000.lifedashboard.ui.ld.LDOptionMark
import com.owen282000.lifedashboard.ui.ld.LDRefusedBanner
import com.owen282000.lifedashboard.ui.ld.LDRow
import com.owen282000.lifedashboard.ui.ld.LDScreen
import com.owen282000.lifedashboard.ui.ld.LDSectionLabel
import com.owen282000.lifedashboard.ui.ld.LDTextButton
import com.owen282000.lifedashboard.ui.ld.LDTheme
import com.owen282000.lifedashboard.ui.ld.LDToggleRow
import com.owen282000.lifedashboard.ui.ld.LDType
import com.owen282000.lifedashboard.ui.ld.PulseLine
import com.owen282000.lifedashboard.ui.ld.PulseProgress
import com.owen282000.lifedashboard.ui.ld.ldStaggerRise
import com.owen282000.lifedashboard.viewmodel.HcAvailability
import com.owen282000.lifedashboard.viewmodel.OnboardingForm
import com.owen282000.lifedashboard.viewmodel.RealHealthOps
import com.owen282000.lifedashboard.viewmodel.RealScreenTimeOps
import com.owen282000.lifedashboard.viewmodel.SettingsRules
import com.owen282000.lifedashboard.viewmodel.UiMessage
import kotlinx.coroutines.launch

private const val HEALTH_CONNECT_STORE_URL = "https://play.google.com/store/apps/details?id=com.google.android.apps.healthdata"
private const val STEP_EXTRA = "ld.step"
private const val URL_PLACEHOLDER = "https://..."
private const val HOST_PLACEHOLDER = "192.168.1.10"

/**
 * The first-run flow, boards On1 to On5, in front of the shell until it is finished. Every step can
 * be skipped and what it asks can be changed later. The answers stay in [OnboardingForm] and are
 * written at the end, apart from what a scanned code and the plain HTTP switch write at once. No
 * view model here: creating the tabs' ones now would load the settings before these writes, see
 * MainActivity. The scanner and the pairing dialog are MainActivity's, over this screen, so they
 * come back to the same step with the fields untouched.
 *
 * A setup left halfway comes back at the first step on the next launch, as on iOS, with what a code
 * already stored in sight. The other answers are in memory on purpose, see [OnboardingForm].
 */
@Composable
fun OnboardingFlowScreen(
    onFinished: () -> Unit,
    onScanRequested: () -> Unit,
    paired: OnboardingSupport.WizardPairing?
) {
    val context = LocalContext.current
    val activity = LocalActivity.current
    val focusManager = LocalFocusManager.current
    val prefs = remember { PreferencesManager(context) }
    val form: OnboardingForm = viewModel()
    form.loadOnce {
        debugStep(activity)?.let { form.stepIndex = it }
        prefs.allowHttpWebhooks()
    }

    // Pairing writes the settings itself, the same as from the tabs; this only takes over what it
    // wrote. The form keeps the code: after a rotation `paired` is gone, the address is not.
    val stored = remember(paired) {
        (prefs.getDestinations(LogType.HEALTH_CONNECT) + prefs.getDestinations(LogType.SCREEN_TIME)).distinctBy { it.url }
    }
    LaunchedEffect(paired) {
        if (paired == null) return@LaunchedEffect
        form.paired = paired
        form.allowHttp = prefs.allowHttpWebhooks()
    }

    val steps = OnboardingSupport.stepsFor(form.healthConnect)
    val step = steps[form.stepIndex.coerceIn(0, steps.lastIndex)]
    val index = steps.indexOf(step)

    var webhookProblem by remember { mutableStateOf<UiMessage?>(null) }
    var mqttProblem by remember { mutableStateOf<UiMessage?>(null) }
    var mqttOpen by rememberSaveable { mutableStateOf(false) }
    // What the refused finish was asked to write, so Try again is the same finish.
    var refusedFinish by remember { mutableStateOf<Boolean?>(null) }

    fun finish(applyTypes: Boolean) {
        if (!form.writeTo(prefs, applyTypes)) {
            refusedFinish = applyTypes
            return
        }
        refusedFinish = null
        prefs.setOnboardingCompleted()
        onFinished()
    }

    fun move(by: Int) {
        focusManager.clearFocus()
        form.stepIndex = index + by
    }

    fun addWebhook() {
        webhookProblem = SettingsRules.urlProblem(form.webhookUrl)
        if (webhookProblem == null) form.useWebhook = true
    }

    fun useMqttHost() {
        mqttProblem = SettingsRules.brokerHostProblem(form.mqttHost, required = true)
        if (mqttProblem == null) {
            form.useMqtt = true
            mqttOpen = false
        }
    }

    // Next never asks for a destination, the step can be left empty. Only text still in a field
    // counts as meant: it is taken, or Next stays here to show what is wrong with it.
    fun nextFromDestination() {
        val useWebhook = form.useWebhook || form.webhookUrl.isNotBlank()
        val useMqtt = form.useMqtt || form.mqttHost.isNotBlank()
        if (OnboardingSupport.destinationReady(useWebhook, form.webhookUrl, useMqtt, form.mqttHost, form.mqttPort)) {
            form.useWebhook = useWebhook
            form.useMqtt = useMqtt
            mqttOpen = false
            move(1)
        } else {
            webhookProblem = if (useWebhook) SettingsRules.urlProblem(form.webhookUrl) else null
            mqttProblem = if (useMqtt) SettingsRules.brokerHostProblem(form.mqttHost, required = true) else null
            if (mqttProblem != null) mqttOpen = true
        }
    }

    BackHandler(enabled = index > 0) { move(-1) }

    val scroll = rememberScrollState()
    LaunchedEffect(step) { scroll.scrollTo(0) }
    val rightToLeft = LocalLayoutDirection.current == LayoutDirection.Rtl
    val slide = with(LocalDensity.current) { LDMotion.TabSlide.roundToPx() }

    LDScreen(
        scrollState = scroll,
        footer = {
            LDFooterBar {
                if (index > 0) {
                    LDButton(stringResource(R.string.common_back), kind = LDButtonKind.Ghost) { move(-1) }
                }
                when (step) {
                    Step.WELCOME -> LDButton(stringResource(R.string.onboarding_get_started)) { move(1) }
                    Step.DONE -> LDButton(stringResource(R.string.onboarding_open_app)) { finish(applyTypes = true) }
                    Step.DESTINATION -> LDButton(stringResource(R.string.onboarding_next), onClick = ::nextFromDestination)
                    else -> LDButton(
                        stringResource(R.string.onboarding_next),
                        enabled = form.healthConnect || form.screenTime
                    ) { move(1) }
                }
            }
        }
    ) {
        StepTop(
            number = index + 1,
            total = steps.size,
            onSkip = (if (step == Step.DONE) null else { { finish(applyTypes = step == Step.TYPES) } })
        )
        StepLine(index, steps.size)
        LDRefusedBanner(
            visible = refusedFinish != null,
            onRetry = { refusedFinish?.let(::finish) },
            onDismiss = { refusedFinish = null }
        )
        AnimatedContent(
            targetState = step,
            contentAlignment = Alignment.TopStart,
            transitionSpec = {
                val forward = (targetState > initialState) != rightToLeft
                val from = if (forward) slide else -slide
                val spec = tween<Float>(LDMotion.TAB_MILLIS, easing = LDMotion.Enter)
                val offset = tween<IntOffset>(LDMotion.TAB_MILLIS, easing = LDMotion.Enter)
                (slideInHorizontally(offset) { from } + fadeIn(spec))
                    .togetherWith(slideOutHorizontally(offset) { -from } + fadeOut(spec))
                    .using(null)
            }
        ) { shown ->
            Column(Modifier.fillMaxWidth()) {
                when (shown) {
                    Step.WELCOME -> WelcomeStep()
                    Step.FEATURES -> FeaturesStep(form)
                    Step.DESTINATION -> DestinationStep(
                        form = form,
                        stored = stored,
                        prefs = prefs,
                        webhookProblem = webhookProblem,
                        mqttProblem = mqttProblem,
                        mqttOpen = mqttOpen,
                        onScan = onScanRequested,
                        onWebhookChange = {
                            form.webhookUrl = it
                            webhookProblem = null
                        },
                        onAddWebhook = ::addWebhook,
                        onRemoveWebhook = {
                            form.useWebhook = false
                            form.webhookUrl = ""
                        },
                        onMqttToggle = { mqttOpen = !mqttOpen },
                        onMqttChange = {
                            form.mqttHost = it
                            // An edit undoes Use, so clearing the field is how a broker goes again.
                            form.useMqtt = false
                            mqttProblem = null
                        },
                        onUseMqtt = ::useMqttHost,
                        onLater = {
                            form.useWebhook = false
                            form.useMqtt = false
                            webhookProblem = null
                            mqttProblem = null
                            mqttOpen = false
                            move(1)
                        }
                    )
                    Step.TYPES -> TypesStep(form)
                    Step.DONE -> DoneStep(form, stored, prefs)
                }
            }
        }
    }
}

/**
 * Writes what the setup was told. False when the secure storage refused an address: nothing of it
 * is stored then, and the flow stays open so finishing can be tried again.
 */
private fun OnboardingForm.writeTo(prefs: PreferencesManager, applyTypes: Boolean): Boolean {
    val url = webhookUrl.trim()
    val secret = webhookSecret.trim()
    if (OnboardingSupport.writesWebhook(useWebhook, url, secret, paired?.link)) {
        val sections = listOfNotNull(
            LogType.HEALTH_CONNECT.takeIf { healthConnect },
            LogType.SCREEN_TIME.takeIf { screenTime }
        )
        val lists = sections.associateWith { OnboardingSupport.withWebhook(prefs.getDestinations(it), url, secret) }
        if (lists.any { (section, list) -> !prefs.canSetDestinations(section, list) }) return false
        lists.forEach { (section, list) -> prefs.setDestinations(section, list) }
    }
    if (useMqtt && MqttSupport.isValidHost(mqttHost)) {
        prefs.setSharedMqttBroker(
            MqttBroker(
                host = mqttHost.trim(),
                port = MqttSupport.portOrNull(mqttPort) ?: 1883,
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
            prefs.setMqttSection(section, prefs.getMqttSection(section).copy(enabled = true, useSharedBroker = true))
        }
    }
    if (applyTypes && healthConnect) prefs.setHealthEnabledDataTypes(OnboardingSupport.typesFor(preset))
    return true
}

/** `--ei ld.step 2` opens the third step, for screenshots, as on iOS. Only in a debuggable build: MainActivity is exported. */
private fun debugStep(activity: Activity?): Int? {
    val intent = activity?.intent ?: return null
    if (activity.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0 || !intent.hasExtra(STEP_EXTRA)) return null
    return intent.getIntExtra(STEP_EXTRA, 0)
}

@Composable
private fun StepTop(number: Int, total: Int, onSkip: (() -> Unit)?) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .padding(horizontal = LDTheme.textInset),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Says the new step when TalkBack stays on the button that was tapped.
        Text(
            stringResource(R.string.onboarding_step_of, number, total),
            style = LDType.mono(12.5.sp),
            color = LDTheme.colors.muted,
            modifier = Modifier
                .weight(1f)
                .semantics { liveRegion = LiveRegionMode.Polite }
        )
        if (onSkip != null) LDTextButton(stringResource(R.string.onboarding_skip), onClick = onSkip)
    }
}

/**
 * The line is the progress, up to the last step, where the full mark comes as Home shows it. One
 * PulseProgress stays alive throughout, so Back draws the line back as well.
 */
@Composable
private fun StepLine(index: Int, total: Int) {
    val done = index == total - 1
    Box {
        PulseProgress(step = minOf(index + 1, total - 1), of = total, modifier = Modifier.alpha(if (done) 0f else 1f))
        if (done) PulseLine()
    }
}

@Composable
private fun WelcomeStep() {
    LDHeading(stringResource(R.string.onboarding_welcome_title))
    LDNote(
        stringResource(R.string.ld_onboarding_welcome_body),
        color = LDTheme.colors.inactive,
        top = 10.dp,
        size = LDNoteSize.Large
    )
}

@Composable
private fun FeaturesStep(form: OnboardingForm) {
    LDHeading(stringResource(R.string.onboarding_features_title))
    LDNote(stringResource(R.string.ld_onboarding_pick_one_or_both), top = 6.dp)
    LDGap(16.dp)
    LDOption(
        stringResource(R.string.onboarding_feature_health),
        selected = form.healthConnect,
        onClick = { form.healthConnect = !form.healthConnect },
        subtitle = stringResource(R.string.ld_onboarding_health_desc),
        mark = LDOptionMark.Check,
        modifier = Modifier.ldStaggerRise(0)
    )
    LDOption(
        stringResource(R.string.onboarding_feature_screen),
        selected = form.screenTime,
        onClick = { form.screenTime = !form.screenTime },
        subtitle = stringResource(R.string.ld_onboarding_screen_desc),
        mark = LDOptionMark.Check,
        modifier = Modifier.ldStaggerRise(1)
    )
}

@Composable
private fun DestinationStep(
    form: OnboardingForm,
    stored: List<Destination>,
    prefs: PreferencesManager,
    webhookProblem: UiMessage?,
    mqttProblem: UiMessage?,
    mqttOpen: Boolean,
    onScan: () -> Unit,
    onWebhookChange: (String) -> Unit,
    onAddWebhook: () -> Unit,
    onRemoveWebhook: () -> Unit,
    onMqttToggle: () -> Unit,
    onMqttChange: (String) -> Unit,
    onUseMqtt: () -> Unit,
    onLater: () -> Unit
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val colors = LDTheme.colors
    val scope = rememberCoroutineScope()
    var pinging by remember { mutableStateOf(false) }
    var pingResult by remember { mutableStateOf<UiMessage?>(null) }

    val url = form.webhookUrl.trim()
    val sections = OnboardingSupport.webhookSections(form.healthConnect, form.screenTime, url, form.webhookSecret.trim(), form.paired)
    val typedIsUsable = url.isNotBlank() && SettingsRules.urlProblem(url) == null
    // What a ping reaches: the code's address with its secret, and the typed one. Not while that
    // one cannot work, or the answer would be about the wrong address.
    val targets = when {
        url.isBlank() -> stored
        typedIsUsable -> OnboardingSupport.withWebhook(stored, url, form.webhookSecret.trim())
        else -> emptyList()
    }
    val listed = if (form.useWebhook) stored.filter { it.url != url } else stored
    val plainHttp = (stored.map { it.url } + url).any { it.startsWith("http://", ignoreCase = true) }

    LDHeading(stringResource(R.string.onboarding_destination_title))
    LDGap(14.dp)
    LDButton(stringResource(R.string.onboarding_scan_option), onClick = onScan)
    LDNote(stringResource(R.string.ld_onboarding_scan_note), size = LDNoteSize.Small)
    if (form.paired != null) {
        LDNote(stringResource(R.string.onboarding_scan_paired), color = colors.accentTextSoft, top = 4.dp, size = LDNoteSize.Small)
    }

    LDSectionLabel(stringResource(R.string.ld_onboarding_own_server))
    LDCard {
        listed.forEach { destination ->
            LDFieldRow(stringResource(R.string.ld_title_destination), destination.url)
        }
        if (form.useWebhook) {
            LDFieldRow(stringResource(R.string.ld_onboarding_webhook_url), url) {
                IconButton(onClick = onRemoveWebhook) {
                    Icon(
                        LDIcons.close,
                        contentDescription = stringResource(
                            R.string.ld_remove_named,
                            Destinations.label(Destination(id = "", url = url), emptyList())
                        ),
                        tint = colors.muted,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        } else {
            LDFieldRow(
                stringResource(R.string.ld_onboarding_webhook_url),
                form.webhookUrl,
                onValueChange = {
                    onWebhookChange(it)
                    pingResult = null
                },
                placeholder = URL_PLACEHOLDER,
                error = webhookProblem?.text(resources),
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    autoCorrectEnabled = false,
                    keyboardType = KeyboardType.Uri
                ),
                onDone = { if (form.webhookUrl.isNotBlank()) onAddWebhook() },
                trailing = {
                    LDButton(
                        stringResource(R.string.common_add),
                        kind = LDButtonKind.Ghost,
                        size = LDButtonSize.Small,
                        fullWidth = false,
                        enabled = form.webhookUrl.isNotBlank(),
                        onClick = onAddWebhook
                    )
                }
            )
        }
        // Plain HTTP is opt-in, as in the tabs. Here as soon as an address needs it, so the test
        // ping does not fail with a hint to go read the logs on the very first screen.
        if (plainHttp) {
            LDToggleRow(
                stringResource(R.string.webhook_allow_plain_http),
                checked = form.allowHttp,
                onCheckedChange = {
                    form.allowHttp = it
                    prefs.setAllowHttpWebhooks(it)
                    pingResult = null
                },
                subtitle = stringResource(R.string.webhook_allow_plain_http_description)
            )
        }
        LDRow(
            stringResource(if (pinging) R.string.health_test_pinging else R.string.health_test_ping),
            showChevron = false,
            enabled = targets.isNotEmpty() && !pinging,
            onClick = {
                pinging = true
                pingResult = null
                val logType = if (PairingSource.HEALTH in sections || sections.isEmpty()) LogType.HEALTH_CONNECT else LogType.SCREEN_TIME
                scope.launch {
                    pingResult = TestPing.send(context, targets, logType).fold(
                        onSuccess = { UiMessage.PingDelivered },
                        onFailure = { UiMessage.PingFailedWith(FailureReason.of(it).orEmpty()) }
                    )
                    pinging = false
                }
            },
            trailing = if (pinging) {
                { CircularProgressIndicator(Modifier.size(16.dp), color = colors.muted, strokeWidth = 2.dp) }
            } else {
                null
            }
        )
    }
    if (url.isNotBlank() && sections.isNotEmpty()) {
        LDNote(
            stringResource(
                when {
                    sections.size == 2 -> R.string.onboarding_webhook_applied
                    PairingSource.HEALTH in sections -> R.string.onboarding_webhook_applied_health
                    else -> R.string.onboarding_webhook_applied_screen
                }
            ),
            size = LDNoteSize.Small
        )
    }
    // Always there, so TalkBack reads the result when it appears.
    Box(Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }) {
        pingResult?.let { result ->
            LDNote(
                result.text(resources),
                color = if (result == UiMessage.PingDelivered) colors.accentTextSoft else colors.warning,
                size = LDNoteSize.Small
            )
        }
    }

    LDGap(8.dp)
    LDCard {
        LDRow(
            stringResource(R.string.onboarding_mqtt_option),
            value = if (form.useMqtt) form.mqttHost.trim() else stringResource(R.string.ld_onboarding_set_up),
            valueColor = if (form.useMqtt) colors.accentTextSoft else colors.muted,
            valueMono = form.useMqtt,
            showChevron = !mqttOpen,
            onClick = onMqttToggle
        )
        if (mqttOpen) {
            LDFieldRow(
                stringResource(R.string.mqtt_broker_host_placeholder),
                form.mqttHost,
                onValueChange = onMqttChange,
                placeholder = HOST_PLACEHOLDER,
                error = mqttProblem?.text(resources),
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    autoCorrectEnabled = false,
                    keyboardType = KeyboardType.Uri
                ),
                onDone = { if (form.mqttHost.isNotBlank()) onUseMqtt() },
                trailing = {
                    LDButton(
                        stringResource(R.string.ld_onboarding_use),
                        kind = LDButtonKind.Ghost,
                        size = LDButtonSize.Small,
                        fullWidth = false,
                        enabled = form.mqttHost.isNotBlank(),
                        onClick = onUseMqtt
                    )
                }
            )
        }
        LDRow(stringResource(R.string.onboarding_skip_destination), showChevron = false, onClick = onLater)
    }
    if (mqttOpen || form.useMqtt) {
        LDNote(stringResource(R.string.ld_onboarding_mqtt_note), size = LDNoteSize.Small)
    }
    // The same hint as on the MQTT page: the setup starts on 1883 without TLS.
    if (!form.mqttTls && form.mqttHost.isNotBlank() && !MqttSupport.isPrivateHost(form.mqttHost)) {
        LDNote(stringResource(R.string.mqtt_plaintext_public_host), color = colors.warning, size = LDNoteSize.Small)
    }
}

@Composable
private fun TypesStep(form: OnboardingForm) {
    LDHeading(stringResource(R.string.onboarding_types_title))
    LDGap(16.dp)
    Column(Modifier.selectableGroup()) {
        LDOption(
            stringResource(R.string.onboarding_types_essentials),
            selected = form.preset == TypePreset.ESSENTIALS,
            onClick = { form.preset = TypePreset.ESSENTIALS },
            subtitle = stringResource(R.string.onboarding_types_essentials_desc),
            modifier = Modifier.ldStaggerRise(0)
        )
        LDOption(
            stringResource(R.string.onboarding_types_all),
            selected = form.preset == TypePreset.ALL,
            onClick = { form.preset = TypePreset.ALL },
            subtitle = stringResource(R.string.onboarding_types_all_desc),
            modifier = Modifier.ldStaggerRise(1)
        )
        LDOption(
            stringResource(R.string.onboarding_types_later),
            selected = form.preset == TypePreset.LATER,
            onClick = { form.preset = TypePreset.LATER },
            subtitle = stringResource(R.string.ld_onboarding_later_desc),
            modifier = Modifier.ldStaggerRise(2)
        )
    }
}

/** What the phone says about the permissions, for the tasks on the last step. */
private data class AccessFacts(val availability: HcAvailability, val granted: Set<String>, val usageAccess: Boolean)

@Composable
private fun DoneStep(form: OnboardingForm, stored: List<Destination>, prefs: PreferencesManager) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val colors = LDTheme.colors

    // Both are answered outside the app, without a callback worth waiting for: read on every return.
    var facts by remember { mutableStateOf<AccessFacts?>(null) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            val health = RealHealthOps(context)
            val availability = health.availability()
            facts = AccessFacts(
                availability,
                if (availability == HcAvailability.AVAILABLE) health.grantedPermissions() else emptySet(),
                RealScreenTimeOps(context, prefs).hasUsageAccess()
            )
        }
    }
    val grantLauncher = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) { }

    fun openOutside(failure: Int, open: () -> Unit) {
        try {
            open()
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(context, resources.getString(failure), Toast.LENGTH_SHORT).show()
        }
    }

    val types = OnboardingSupport.typesFor(form.preset)
    val own = form.webhookUrl.trim().takeIf { form.useWebhook }
    val destinations = if (own != null) OnboardingSupport.withWebhook(stored, own, form.webhookSecret.trim()) else stored
    val names = destinations.map { Destinations.label(it, destinations) } + listOfNotNull(form.mqttHost.trim().takeIf { form.useMqtt })
    val syncing = listOfNotNull(
        stringResource(R.string.onboarding_feature_health).takeIf { form.healthConnect },
        stringResource(R.string.onboarding_feature_screen).takeIf { form.screenTime }
    ).joinToString(", ")

    LDHeading(stringResource(R.string.onboarding_done_title))
    LDGap(12.dp)
    LDKeyValueList(
        buildList {
            add(LDKeyValue(stringResource(R.string.onboarding_summary_sync), syncing))
            add(
                if (names.isEmpty()) {
                    LDKeyValue(stringResource(R.string.ld_onboarding_sending_to), stringResource(R.string.onboarding_summary_none), colors.muted)
                } else {
                    LDKeyValue(stringResource(R.string.ld_onboarding_sending_to), names.joinToString("\n"), isCode = true)
                }
            )
            if (form.healthConnect) {
                add(
                    LDKeyValue(
                        stringResource(R.string.onboarding_summary_types),
                        stringResource(
                            when (form.preset) {
                                TypePreset.ESSENTIALS -> R.string.onboarding_summary_essentials
                                TypePreset.ALL -> R.string.onboarding_summary_all
                                TypePreset.LATER -> R.string.onboarding_summary_later
                            }
                        ),
                        colors.muted.takeIf { form.preset == TypePreset.LATER }
                    )
                )
            }
        },
        Modifier.ldStaggerRise(0)
    )

    val tasks = facts?.let {
        OnboardingSupport.tasksLeft(form.healthConnect, form.screenTime, form.preset, it.availability, it.granted, it.usageAccess)
    }.orEmpty()
    if (tasks.isNotEmpty()) {
        Column(Modifier.ldStaggerRise(1)) {
            LDSectionLabel(stringResource(if (tasks.size == 1) R.string.ld_onboarding_one_thing_left else R.string.ld_onboarding_two_things_left))
            LDCard {
                tasks.forEach { task ->
                    when (task) {
                        Task.HEALTH_ACCESS -> {
                            val availability = facts?.availability
                            LDRow(
                                stringResource(R.string.onboarding_task_health),
                                value = stringResource(
                                    when (availability) {
                                        HcAvailability.NOT_INSTALLED -> R.string.health_install
                                        HcAvailability.NEEDS_UPDATE -> R.string.health_update
                                        else -> R.string.common_grant
                                    }
                                ),
                                valueColor = colors.accentTextSoft,
                                onClick = {
                                    openOutside(R.string.health_could_not_open) {
                                        if (availability == HcAvailability.AVAILABLE) {
                                            grantLauncher.launch(HealthPermissionRequests.forGrant(types))
                                        } else {
                                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(HEALTH_CONNECT_STORE_URL)))
                                        }
                                    }
                                }
                            )
                        }
                        Task.USAGE_ACCESS -> LDRow(
                            stringResource(R.string.onboarding_task_screen),
                            value = stringResource(R.string.screentime_allow),
                            valueColor = colors.accentTextSoft,
                            onClick = {
                                openOutside(R.string.screentime_access_needed) {
                                    context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}
