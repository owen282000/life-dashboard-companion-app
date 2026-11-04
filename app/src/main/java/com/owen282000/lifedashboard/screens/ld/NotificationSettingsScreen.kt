package com.owen282000.lifedashboard.screens.ld

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.owen282000.lifedashboard.R
import com.owen282000.lifedashboard.ui.ld.LDBanner
import com.owen282000.lifedashboard.ui.ld.LDBannerAction
import com.owen282000.lifedashboard.ui.ld.LDCard
import com.owen282000.lifedashboard.ui.ld.LDGap
import com.owen282000.lifedashboard.ui.ld.LDHeading
import com.owen282000.lifedashboard.ui.ld.LDNote
import com.owen282000.lifedashboard.ui.ld.LDNoteSize
import com.owen282000.lifedashboard.ui.ld.LDScreen
import com.owen282000.lifedashboard.ui.ld.LDSectionLabel
import com.owen282000.lifedashboard.ui.ld.LDSegment
import com.owen282000.lifedashboard.ui.ld.LDSegmented
import com.owen282000.lifedashboard.ui.ld.LDToggleRow
import com.owen282000.lifedashboard.ui.ld.ldStaggerRise

private val FAILURE_THRESHOLDS = listOf(3, 5, 10)

@Composable
fun NotificationSettingsScreen() {
    val state by ldHealthViewModel().state.collectAsStateWithLifecycle()
    val shared = ldSharedSettings()
    val context = LocalContext.current
    // Android refuses silently after a second no, and the user can switch them off in its settings.
    var blocked by remember { mutableStateOf(!NotificationManagerCompat.from(context).areNotificationsEnabled()) }
    LifecycleResumeEffect(context) {
        blocked = !NotificationManagerCompat.from(context).areNotificationsEnabled()
        onPauseOrDispose { }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        blocked = !NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    LDScreen(title = stringResource(R.string.health_notifications_title)) {
        LDHeading(stringResource(R.string.health_notifications_title))
        LDGap(12.dp)
        LDCard(Modifier.ldStaggerRise(0)) {
            LDToggleRow(
                stringResource(R.string.health_notifications_notify_after_failed),
                state.failureNotificationsEnabled,
                { enabled ->
                    shared.setFailureNotifications(enabled)
                    if (enabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }
            )
        }
        if (state.failureNotificationsEnabled) {
            if (blocked) {
                LDBanner(stringResource(R.string.ld_notifications_blocked_title), message = stringResource(R.string.ld_notifications_blocked_body)) {
                    LDBannerAction(stringResource(R.string.scan_permission_open_settings)) {
                        context.startActivity(
                            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                        )
                    }
                }
            }
            LDSectionLabel(stringResource(R.string.health_notifications_after_failures))
            LDSegmented(
                selected = state.failureThreshold,
                segments = FAILURE_THRESHOLDS.map { LDSegment(it, it.toString()) },
                onSelect = shared::setFailureThreshold,
                modifier = Modifier.ldStaggerRise(1),
                label = stringResource(R.string.health_notifications_after_failures)
            )
            LDNote(stringResource(R.string.ld_notifications_note), top = 10.dp, size = LDNoteSize.Small)
        }
    }
}
