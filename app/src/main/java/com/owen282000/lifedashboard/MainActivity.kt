package com.owen282000.lifedashboard

import android.content.Intent
import android.net.Uri
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import com.owen282000.lifedashboard.screens.HealthConnectScreen
import com.owen282000.lifedashboard.screens.LogsScreen
import com.owen282000.lifedashboard.screens.ScreenTimeScreen
import com.owen282000.lifedashboard.ui.theme.*

enum class AppTab {
    HealthConnect,
    ScreenTime,
    Logs
}

class MainActivity : ComponentActivity() {

    private lateinit var preferencesManager: PreferencesManager
    private var permissionStatusCallback: ((Boolean) -> Unit)? = null
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

        setContent {
            LifeDashboardTheme {
                MainScreen(
                    activity = this@MainActivity,
                    permissionLauncher = permissionLauncher
                )
            }
        }
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
                                AppTab.HealthConnect -> "Health Connect"
                                AppTab.ScreenTime -> "Screen Time"
                                AppTab.Logs -> "Webhook Logs"
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
                                contentDescription = "About",
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
                            label = "Health",
                            selectedColor = HealthPrimary,
                            modifier = Modifier.weight(1f)
                        )

                        // Screen Time Tab
                        NavBarItem(
                            selected = selectedTab == AppTab.ScreenTime,
                            onClick = { selectedTab = AppTab.ScreenTime },
                            icon = if (selectedTab == AppTab.ScreenTime)
                                Icons.Filled.PhoneAndroid else Icons.Outlined.PhoneAndroid,
                            label = "Screen Time",
                            selectedColor = ScreenTimePrimary,
                            modifier = Modifier.weight(1f)
                        )

                        // Logs Tab
                        NavBarItem(
                            selected = selectedTab == AppTab.Logs,
                            onClick = { selectedTab = AppTab.Logs },
                            icon = if (selectedTab == AppTab.Logs)
                                Icons.Filled.History else Icons.Outlined.History,
                            label = "Logs",
                            selectedColor = MaterialTheme.colorScheme.primary,
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
                            }
                        )
                        AppTab.ScreenTime -> ScreenTimeScreen()
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

