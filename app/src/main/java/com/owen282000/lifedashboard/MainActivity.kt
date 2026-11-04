package com.owen282000.lifedashboard

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.ViewModelProvider
import com.owen282000.lifedashboard.screens.PairingDialog
import com.owen282000.lifedashboard.screens.ScanScreen
import com.owen282000.lifedashboard.screens.ld.LDAppShell
import com.owen282000.lifedashboard.screens.ld.OnboardingFlowScreen
import com.owen282000.lifedashboard.ui.ld.LDDebugDestination
import com.owen282000.lifedashboard.ui.ld.LDDebugRoute
import com.owen282000.lifedashboard.ui.ld.LDNavigator
import com.owen282000.lifedashboard.ui.ld.LDPairedPing
import com.owen282000.lifedashboard.ui.ld.LDPairedResult
import com.owen282000.lifedashboard.ui.ld.LDRoute
import com.owen282000.lifedashboard.ui.ld.LDTheme
import com.owen282000.lifedashboard.ui.ld.LocalLDPairing
import com.owen282000.lifedashboard.ui.ld.homeTab
import com.owen282000.lifedashboard.ui.ld.rememberLDNavigator
import com.owen282000.lifedashboard.ui.theme.LifeDashboardTheme
import com.owen282000.lifedashboard.viewmodel.HealthConnectViewModel
import com.owen282000.lifedashboard.viewmodel.PairingViewModel
import com.owen282000.lifedashboard.viewmodel.ScreenTimeViewModel

class MainActivity : ComponentActivity() {

    private lateinit var preferencesManager: PreferencesManager

    /** The scanner, the code waiting for an answer and the ping after Pair. They span both tabs and the wizard, and outlive a rotation. */
    private val pairing: PairingViewModel by viewModels()

    /** The navigator of the shell on screen; a new one comes with each recreation of the Activity. */
    private var navigator: LDNavigator? = null

    /** A debug build's `ld.route` waiting for the shell, see [LDDebugRoute]. */
    private val debugDestination = mutableStateOf<LDDebugDestination?>(null)
    private lateinit var permissionLauncher: androidx.activity.result.ActivityResultLauncher<Set<String>>

    /** What comes back is read in the shell, which re-checks the permissions on resume. */
    private fun initializePermissionLauncher() {
        val requestPermissionActivityContract = androidx.health.connect.client.PermissionController.createRequestPermissionResultContract()
        permissionLauncher = registerForActivityResult(requestPermissionActivityContract) { }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        installSplashScreen()
        enableEdgeToEdge()
        preferencesManager = PreferencesManager(this)
        initializePermissionLauncher()
        // Only on a fresh start. Android recreates the Activity on rotation with the same
        // intent, and a link the user already answered must not come back as a new one.
        if (savedInstanceState == null) {
            handlePairingIntent(intent)
            handleDebugIntent(intent)
        }

        setContent {
            LifeDashboardTheme {
                LDTheme {
                    CompositionLocalProvider(LocalLDPairing provides pairing.pairing) {
                        // First run: walk through the onboarding flow before showing the main UI.
                        var showOnboarding by rememberSaveable {
                            mutableStateOf(
                                when (debugDestination.value) {
                                    null -> !preferencesManager.onboardingCompleted()
                                    LDDebugDestination.Onboarding -> true
                                    else -> false
                                }
                            )
                        }
                        if (showOnboarding) {
                            OnboardingFlowScreen(
                                onFinished = { showOnboarding = false },
                                onScanRequested = { pairing.scanning = true },
                                paired = pairing.pairedInWizard
                            )
                        } else {
                            val shellNavigator = rememberLDNavigator()
                            SideEffect { navigator = shellNavigator }
                            LDAppShell(shellNavigator, permissionLauncher)
                            LaunchedEffect(debugDestination.value) { applyDebugDestination(shellNavigator) }
                        }

                        // Over the tabs and over the wizard: a scan from either lands in the same
                        // confirmation as a link from the camera.
                        if (pairing.scanning) {
                            ScanScreen(
                                onScanned = { link ->
                                    pairing.scanning = false
                                    pairing.pending = link
                                },
                                onClose = { pairing.scanning = false }
                            )
                        }

                        // Above both, so a link scanned during onboarding is not lost.
                        pairing.pending?.let { link ->
                            PairingDialog(
                                link = link,
                                currentHealth = preferencesManager.getDestinations(LogType.HEALTH_CONNECT),
                                currentScreenTime = preferencesManager.getDestinations(LogType.SCREEN_TIME),
                                onDismiss = { pairing.pending = null },
                                onConfirm = { choice ->
                                    pairing.pending = null
                                    applyPairing(link, choice, inWizard = showOnboarding)
                                    // During onboarding the wizard stays open: its data-types step
                                    // is still worth answering, and finishing it never clears what
                                    // pairing just wrote.
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    /** Coming to the front is a moment to ask for a rating, in the Play build and after the setup only (P1-28). */
    override fun onResume() {
        super.onResume()
        if (preferencesManager.onboardingCompleted()) ReviewMoment.consider(this)
    }

    /**
     * A pairing link arriving while the app is already running.
     *
     * The Activity is singleTop, so a tapped link does not start a second copy; it comes
     * through here instead.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handlePairingIntent(intent)
        handleDebugIntent(intent)
    }

    /** See [LDDebugRoute]: nothing happens outside a debug build. */
    private fun handleDebugIntent(intent: Intent?) {
        val name = LDDebugRoute.nameIn(this, intent) ?: return
        val logs = preferencesManager.getWebhookLogs()
        debugDestination.value = LDDebugRoute.named(
            name,
            newestLog = logs.firstOrNull()?.id,
            newestFailedLog = logs.firstOrNull { !it.success }?.id,
            newestOkLog = logs.firstOrNull { it.success }?.id,
            firstHealthDestination = preferencesManager.getDestinations(LogType.HEALTH_CONNECT).firstOrNull()?.id,
            firstScreenTimeDestination = preferencesManager.getDestinations(LogType.SCREEN_TIME).firstOrNull()?.id
        )
    }

    private fun applyDebugDestination(navigator: LDNavigator) {
        val destination = debugDestination.value ?: return
        debugDestination.value = null
        val example = (PairingLinks.parse(LDDebugRoute.EXAMPLE_PAIRING_LINK) as? PairingParse.Ok)?.link
        when (destination) {
            is LDDebugDestination.Page -> {
                if (destination.route == LDRoute.PairedResult && example != null) {
                    pairing.pairing.result = LDPairedResult(example, setOf(PairingSource.HEALTH), LDPairedPing.Delivered)
                }
                val tab = destination.route.homeTab
                if (destination.route == tab.root) {
                    navigator.selectTab(tab)
                    navigator.popToRoot()
                } else {
                    navigator.open(destination.route)
                }
            }
            LDDebugDestination.Scanner -> pairing.scanning = true
            LDDebugDestination.Pairing -> pairing.pending = example
            LDDebugDestination.Onboarding -> Unit
        }
    }

    /**
     * Turn a VIEW intent into a pending pairing, or say why it cannot be used.
     *
     * Anything that is not a pairing link is left alone without a word: the app is
     * launched by other intents too, and a complaint would be noise.
     */
    private fun handlePairingIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        when (val parsed = PairingLinks.parse(intent.dataString)) {
            is PairingParse.Ok -> pairing.pending = parsed.link
            is PairingParse.Invalid -> Toast.makeText(this, pairingProblemText(parsed.reason), Toast.LENGTH_LONG).show()
            PairingParse.NotAPairingLink -> Unit
        }
    }

    private fun pairingProblemText(problem: PairingProblem): String = getString(
        when (problem) {
            PairingProblem.UnsupportedVersion -> R.string.pairing_error_version
            PairingProblem.Incomplete -> R.string.pairing_error_incomplete
            PairingProblem.NoUsableSource -> R.string.pairing_error_no_source
        }
    )

    /**
     * Write the pairing, then tell the screens to re-read it.
     *
     * The write goes through PreferencesManager because it spans both sections. The ViewModels
     * live in this Activity's store (the screens create them with viewModel(factory = ...) and
     * there is no NavHost), so these are the instances the tabs are showing.
     */
    private fun applyPairing(link: PairingLink, choice: PairingChoice, inWizard: Boolean) {
        val result = PairingApply.apply(link, choice, preferencesManager.asPairingStore())
        if (result.refused.isNotEmpty()) Toast.makeText(this, getString(R.string.secrets_not_ready), Toast.LENGTH_LONG).show()
        val written = result.written
        if (written.isEmpty()) return

        // The wizard re-reads it instead. No settings view model here: creating the tabs' ones now
        // would load the settings before the wizard writes its data types and MQTT broker, and the
        // tabs would show those stale.
        //
        // The pairing checks itself: one test ping to the paired address, signed with the paired
        // secret. The pairing is written before this and stays written whatever the ping says: a
        // receiver that is down right now is still the one the user chose, and the next sync tries
        // it again.
        if (inWizard) {
            pairing.pairedInWizard(link, written)
            pairing.confirmInWizard(link, written)
        } else {
            ViewModelProvider(this, HealthConnectViewModel.factory(this))[HealthConnectViewModel::class.java]
                .reloadFromSettings()
            ViewModelProvider(this, ScreenTimeViewModel.factory(this))[ScreenTimeViewModel::class.java]
                .reloadFromSettings()
            pairing.confirmOnPage(link, written)
            navigator?.open(LDRoute.PairedResult)
        }
    }
}
