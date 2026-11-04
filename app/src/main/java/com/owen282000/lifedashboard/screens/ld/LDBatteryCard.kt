package com.owen282000.lifedashboard.screens.ld

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.owen282000.lifedashboard.R
import com.owen282000.lifedashboard.appPreferences
import com.owen282000.lifedashboard.screens.OemBattery
import com.owen282000.lifedashboard.screens.TROUBLESHOOTING_URL
import com.owen282000.lifedashboard.screens.fixNow
import com.owen282000.lifedashboard.screens.isOptimized
import com.owen282000.lifedashboard.screens.openVendorScreen
import com.owen282000.lifedashboard.screens.tryStart
import com.owen282000.lifedashboard.ui.ld.LDBanner
import com.owen282000.lifedashboard.ui.ld.LDBannerAction

/** Whether Android's battery optimization is on for the app, read again on every return. */
@Stable
class LDBatteryHint internal constructor(private val context: Context) {
    private val preferences = context.appPreferences()

    var optimized by mutableStateOf(isOptimized(context))
        private set
    var dismissed by mutableStateOf(preferences.batteryHintDismissed())
        private set

    val showsCard: Boolean get() = optimized && !dismissed

    internal fun refresh() {
        optimized = isOptimized(context)
    }

    fun dismiss() {
        preferences.dismissBatteryHint()
        dismissed = true
    }
}

@Composable
fun rememberLDBatteryHint(): LDBatteryHint {
    val context = LocalContext.current
    val hint = remember(context) { LDBatteryHint(context) }
    LifecycleResumeEffect(hint) {
        hint.refresh()
        onPauseOrDispose { }
    }
    return hint
}

/**
 * The most common reason data stops arriving, above all on Samsung and Xiaomi, said where people
 * look. Fix asks Android's own dialog or opens app info, see fixNow; on Samsung, Xiaomi and Huawei a
 * second action opens the vendor's own list, which can put the app to sleep regardless. [since] is
 * a first sentence when nothing has been delivered for a while.
 */
@Composable
fun LDBatteryCard(hint: LDBatteryHint, since: String? = null) {
    if (!hint.showsCard) return
    val context = LocalContext.current
    val oem = remember { OemBattery.forManufacturer(Build.MANUFACTURER) }
    val body = stringResource(R.string.battery_hint_body)
    LDBanner(stringResource(R.string.battery_hint_title), message = if (since != null) "$since $body" else body) {
        LDBannerAction(stringResource(R.string.battery_hint_fix)) {
            fixNow(context, context.resources.getBoolean(R.bool.ask_battery_exemption))
        }
        if (oem != null) {
            LDBannerAction(stringResource(oem.label)) { openVendorScreen(context, oem) }
        }
        LDBannerAction(stringResource(R.string.battery_hint_help)) {
            tryStart(context, Intent(Intent.ACTION_VIEW, Uri.parse(TROUBLESHOOTING_URL)))
        }
        LDBannerAction(stringResource(R.string.battery_hint_dismiss), onClick = hint::dismiss)
    }
}
