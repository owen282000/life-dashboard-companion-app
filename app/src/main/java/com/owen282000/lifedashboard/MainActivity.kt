package com.owen282000.lifedashboard

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.owen282000.lifedashboard.screens.HealthConnectScreen
import com.owen282000.lifedashboard.screens.LogsScreen
import com.owen282000.lifedashboard.screens.OnboardingScreen
import com.owen282000.lifedashboard.screens.PairingDialog
import com.owen282000.lifedashboard.screens.ScanScreen
import com.owen282000.lifedashboard.screens.ScreenTimeScreen
import com.owen282000.lifedashboard.ui.theme.*
import com.owen282000.lifedashboard.viewmodel.HealthConnectViewModel
import com.owen282000.lifedashboard.viewmodel.ScreenTimeViewModel
import kotlinx.coroutines.launch

enum class AppTab {
    HealthConnect,
    ScreenTime,
    Logs
}

class MainActivity : ComponentActivity() {

    private lateinit var preferencesManager: PreferencesManager
    private var permissionStatusCallback: ((Boolean) -> Unit)? = null

    /** A scanned or opened pairing link, waiting for the user to confirm or dismiss it. */
    private val pendingPairing = mutableStateOf<PairingLink?>(null)

    /** Whether the QR scanner is open. The Activity owns it: pairing spans both tabs. */
    private val scanning = mutableStateOf(false)
    private lateinit var permissionLauncher: androidx.activity.result.ActivityResultLauncher<Set<String>>

    private fun initializePermissionLauncher() {
        val requestPermissionActivityContract = androidx.health.connect.client.PermissionController.createRequestPermissionResultContract()

        permissionLauncher = registerForActivityResult(requestPermissionActivityContract) { granted: Set<String> ->
            lifecycleScope.launch {
                val healthConnectManager = HealthConnectManager(this@MainActivity)
                val grantedPermissions = healthConnectManager.getGrantedPermissions()
                val hasAnyPerms = grantedPermissions.isNotEmpty()

                permissionStatusCallback?.invoke(hasAnyPerms)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        installSplashScreen()
        enableEdgeToEdge()
        preferencesManager = PreferencesManager(this)
        initializePermissionLauncher()
        // Only on a fresh start. Android recreates the Activity on rotation with the same
        // intent, and a link the user already answered must not come back as a new one.
        if (savedInstanceState == null) handlePairingIntent(intent)

        setContent {
            LifeDashboardTheme {
                // First run: walk through the onboarding wizard before showing the main UI.
                var showOnboarding by remember {
                    mutableStateOf(!preferencesManager.onboardingCompleted())
                }
                if (showOnboarding) {
                    OnboardingScreen(
                        onFinished = { showOnboarding = false },
                        onScanRequested = { scanning.value = true }
                    )
                } else {
                    MainScreen(
                        activity = this@MainActivity,
                        permissionLauncher = permissionLauncher
                    )
                }

                // Over the tabs and over the wizard: a scan from either lands in the same
                // confirmation dialog as a link from the camera.
                if (scanning.value) {
                    ScanScreen(
                        onScanned = { link ->
                            scanning.value = false
                            pendingPairing.value = link
                        },
                        onClose = { scanning.value = false }
                    )
                }

                // Above both, so a link scanned during onboarding is not lost.
                pendingPairing.value?.let { link ->
                    PairingDialog(
                        link = link,
                        accent = HealthPrimary,
                        currentHealth = preferencesManager.healthSectionWebhook(),
                        currentScreenTime = preferencesManager.screenTimeSectionWebhook(),
                        onDismiss = { pendingPairing.value = null },
                        onConfirm = { choice ->
                            pendingPairing.value = null
                            applyPairing(link, choice)
                            // During onboarding the wizard stays open: its data-types step
                            // is still worth answering, and finishing it never clears what
                            // pairing just wrote.
                        }
                    )
                }
            }
        }
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
            is PairingParse.Ok -> pendingPairing.value = parsed.link
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
     * The write goes through PreferencesManager because it spans both sections, while each
     * ViewModel owns only its own unsaved draft. Those ViewModels live in this Activity's
     * store (the screens create them with viewModel(factory = ...) and there is no
     * NavHost), so these are the instances the tabs are showing.
     */
    private fun applyPairing(link: PairingLink, choice: PairingChoice) {
        val written = PairingApply.apply(link, choice, preferencesManager.asPairingStore())
        if (written.isEmpty()) return

        ViewModelProvider(this, HealthConnectViewModel.factory(this))[HealthConnectViewModel::class.java]
            .reloadFromSettings()
        ViewModelProvider(this, ScreenTimeViewModel.factory(this))[ScreenTimeViewModel::class.java]
            .reloadFromSettings()

        Toast.makeText(this, R.string.pairing_done, Toast.LENGTH_LONG).show()
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun MainScreen(
        activity: MainActivity,
        permissionLauncher: androidx.activity.result.ActivityResultLauncher<Set<String>>
    ) {
        var selectedTab by remember { mutableStateOf(AppTab.HealthConnect) }
        val context = LocalContext.current

        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            topBar = {
                CenterAlignedTopAppBar(
                    title = {
                        Text(
                            when (selectedTab) {
                                AppTab.HealthConnect -> stringResource(R.string.main_title_health_connect)
                                AppTab.ScreenTime -> stringResource(R.string.main_title_screen_time)
                                AppTab.Logs -> stringResource(R.string.main_title_webhook_logs)
                            },
                            fontWeight = FontWeight.SemiBold
                        )
                    },
                    actions = {
                        IconButton(
                            onClick = {
                                val intent = Intent(context, AboutActivity::class.java)
                                context.startActivity(intent)
                            }
                        ) {
                            Icon(
                                Icons.Outlined.Info,
                                contentDescription = stringResource(R.string.main_about),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                        containerColor = Color.Transparent
                    )
                )
            },
            bottomBar = {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 3.dp,
                    shadowElevation = 8.dp
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp)
                            .navigationBarsPadding(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        // Health Connect Tab
                        NavBarItem(
                            selected = selectedTab == AppTab.HealthConnect,
                            onClick = { selectedTab = AppTab.HealthConnect },
                            icon = if (selectedTab == AppTab.HealthConnect)
                                Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                            label = stringResource(R.string.main_tab_health),
                            selectedColor = HealthPrimary,
                            modifier = Modifier.weight(1f)
                        )

                        // Screen Time Tab
                        NavBarItem(
                            selected = selectedTab == AppTab.ScreenTime,
                            onClick = { selectedTab = AppTab.ScreenTime },
                            icon = if (selectedTab == AppTab.ScreenTime)
                                Icons.Filled.PhoneAndroid else Icons.Outlined.PhoneAndroid,
                            label = stringResource(R.string.main_title_screen_time),
                            selectedColor = ScreenTimePrimary,
                            modifier = Modifier.weight(1f)
                        )

                        // Logs Tab
                        NavBarItem(
                            selected = selectedTab == AppTab.Logs,
                            onClick = { selectedTab = AppTab.Logs },
                            icon = if (selectedTab == AppTab.Logs)
                                Icons.Filled.History else Icons.Outlined.History,
                            label = stringResource(R.string.main_tab_logs),
                            selectedColor = LogsPrimary,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        ) { padding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding())
            ) {
                AnimatedContent(
                    targetState = selectedTab,
                    transitionSpec = {
                        fadeIn(animationSpec = androidx.compose.animation.core.tween(300)) togetherWith
                        fadeOut(animationSpec = androidx.compose.animation.core.tween(300))
                    },
                    label = "tab_transition"
                ) { tab ->
                    when (tab) {
                        AppTab.HealthConnect -> HealthConnectScreen(
                            permissionLauncher = permissionLauncher,
                            onPermissionResult = { granted ->
                                activity.permissionStatusCallback?.invoke(granted)
                            },
                            onScanRequested = { activity.scanning.value = true }
                        )
                        AppTab.ScreenTime -> ScreenTimeScreen(
                            onScanRequested = { activity.scanning.value = true }
                        )
                        AppTab.Logs -> LogsScreen()
                    }
                }
            }
        }
    }
}

@Composable
private fun NavBarItem(
    selected: Boolean,
    onClick: () -> Unit,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    selectedColor: Color,
    modifier: Modifier = Modifier
) {
    val backgroundColor = if (selected) selectedColor.copy(alpha = 0.12f) else Color.Transparent
    val contentColor = if (selected) selectedColor else MaterialTheme.colorScheme.onSurfaceVariant

    Surface(
        onClick = onClick,
        modifier = modifier
            .padding(horizontal = 4.dp),
        shape = RoundedCornerShape(16.dp),
        color = backgroundColor
    ) {
        Column(
            modifier = Modifier
                .padding(vertical = 8.dp, horizontal = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = contentColor,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = contentColor
            )
        }
    }
}
