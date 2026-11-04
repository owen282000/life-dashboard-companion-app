package com.owen282000.lifedashboard.screens.ld

import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.owen282000.lifedashboard.R
import com.owen282000.lifedashboard.screens.ReviewAfterSync
import com.owen282000.lifedashboard.screens.text
import com.owen282000.lifedashboard.ui.ld.LDNavigator
import com.owen282000.lifedashboard.ui.ld.LDShell
import com.owen282000.lifedashboard.ui.ld.LDTab
import kotlinx.coroutines.delay

/**
 * The shell with what no single screen owns: toasts from either section, the Health Connect
 * permission launcher, the usage access settings, and re-reading what can change while the app is
 * away or on another tab. A screen therefore only calls the view model.
 */
@Composable
fun LDAppShell(navigator: LDNavigator, permissionLauncher: ActivityResultLauncher<Set<String>>) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val health = ldHealthViewModel()
    val screenTime = ldScreenTimeViewModel()
    val healthState by health.state.collectAsStateWithLifecycle()
    val screenTimeState by screenTime.state.collectAsStateWithLifecycle()

    // Permissions are granted in Health Connect's own UI, so re-check whenever we come back.
    LifecycleResumeEffect(health) {
        health.refreshPermissions()
        onPauseOrDispose { }
    }
    // Plain HTTP, the client certificate and the shared broker can be changed from either tab.
    LaunchedEffect(navigator.tab) {
        health.refreshSharedSettings()
        screenTime.refreshSharedSettings()
    }
    // Usage access is granted in system settings with no callback, so poll while on that tab.
    if (navigator.tab == LDTab.ScreenTime) {
        LaunchedEffect(Unit) {
            while (true) {
                screenTime.refreshUsageAccess()
                delay(1000)
            }
        }
    }
    LaunchedEffect(Unit) {
        health.toasts.collect { Toast.makeText(context, it.text(resources), Toast.LENGTH_SHORT).show() }
    }
    LaunchedEffect(Unit) {
        screenTime.toasts.collect { Toast.makeText(context, it.text(resources), Toast.LENGTH_SHORT).show() }
    }
    LaunchedEffect(Unit) {
        health.permissionRequests.collect { permissions ->
            try {
                permissionLauncher.launch(permissions)
            } catch (e: Exception) {
                Toast.makeText(context, resources.getString(R.string.health_error_with_reason, e.message ?: ""), Toast.LENGTH_LONG).show()
            }
        }
    }
    LaunchedEffect(Unit) {
        screenTime.openUsageAccess.collect { context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
    }
    ReviewAfterSync(healthState.syncMessage)
    ReviewAfterSync(screenTimeState.syncMessage)

    LDShell(navigator) { route -> LDRouteContent(route) }
    LDHealthPrompts(healthState, health)
}
