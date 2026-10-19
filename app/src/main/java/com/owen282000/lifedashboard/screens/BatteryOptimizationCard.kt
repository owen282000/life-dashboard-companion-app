package com.owen282000.lifedashboard.screens

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BatteryAlert
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.owen282000.lifedashboard.PreferencesManager
import com.owen282000.lifedashboard.R
import com.owen282000.lifedashboard.ui.theme.ink
import com.owen282000.lifedashboard.ui.theme.onAccent

private const val TROUBLESHOOTING_URL = "https://owen282000.com/life-dashboard/troubleshooting/"

/**
 * While Android's battery optimization is on for the app, a scheduled sync can be delayed for
 * hours or skipped, which is the most common reason data stops arriving, above all on Samsung
 * and Xiaomi. Said here, where people look, rather than only on the website.
 *
 * Fix asks Android's own dialog in the GitHub and F-Droid builds; the Google Play build leaves
 * REQUEST_IGNORE_BATTERY_OPTIMIZATIONS out, as Play allows it to few kinds of apps, and opens
 * app info instead, where Battery can be set to Unrestricted. On Samsung, Xiaomi and Huawei a
 * second button opens the vendor's own list, which can put the app to sleep regardless.
 * Checked again on every return, so the card goes away once the setting is changed.
 */
@Composable
fun BatteryOptimizationCard(accent: Color) {
    val context = LocalContext.current
    val preferences = remember(context) { PreferencesManager(context) }
    var optimized by remember { mutableStateOf(isOptimized(context)) }
    var dismissed by remember { mutableStateOf(preferences.batteryHintDismissed()) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) optimized = isOptimized(context)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    if (!optimized || dismissed) return

    val oem = remember { OemBattery.forManufacturer(Build.MANUFACTURER) }
    PremiumCard {
        Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconTile(Icons.Outlined.BatteryAlert, accent)
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        stringResource(R.string.battery_hint_title),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        stringResource(R.string.battery_hint_body),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            // One thing to do, as a button; the vendor's own screen next to it where there is one.
            val askExemption = context.resources.getBoolean(R.bool.ask_battery_exemption)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { fixNow(context, askExemption) },
                    colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = onAccent(accent)),
                    modifier = Modifier.weight(1f)
                ) {
                    Text(stringResource(R.string.battery_hint_fix), maxLines = 1)
                }
                if (oem != null) {
                    OutlinedButton(onClick = { openVendorScreen(context, oem) }, modifier = Modifier.weight(1f)) {
                        Text(stringResource(oem.label), color = accent.ink(), maxLines = 1)
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { tryStart(context, Intent(Intent.ACTION_VIEW, Uri.parse(TROUBLESHOOTING_URL))) }) {
                    Text(stringResource(R.string.battery_hint_help), color = accent.ink())
                }
                Spacer(modifier = Modifier.weight(1f))
                TextButton(onClick = {
                    preferences.dismissBatteryHint()
                    dismissed = true
                }) {
                    Text(stringResource(R.string.battery_hint_dismiss), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

private fun isOptimized(context: Context): Boolean {
    val power = context.getSystemService(PowerManager::class.java) ?: return false
    return !power.isIgnoringBatteryOptimizations(context.packageName)
}

/**
 * Android's own "let this app run in the background?" dialog where the build may ask for it
 * (GitHub and F-Droid), else app info, where Battery can be set to Unrestricted. Started
 * directly rather than checked with resolveActivity, which package visibility hides from
 * on Android 11 and later.
 */
@android.annotation.SuppressLint("BatteryLife")
private fun fixNow(context: Context, askExemption: Boolean) {
    if (askExemption) {
        val ask = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.fromParts("package", context.packageName, null))
        if (tryStart(context, ask)) return
    }
    openAppSettings(context)
}

/** App info, where Battery can be set to Unrestricted. */
private fun openAppSettings(context: Context) {
    tryStart(context, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
}

/** The vendor's own screen, tried screen by screen: the names move between versions. App info last. */
private fun openVendorScreen(context: Context, oem: OemBattery) {
    for (intent in oem.intents(context.packageName)) {
        if (tryStart(context, intent)) return
    }
    openAppSettings(context)
}

private fun tryStart(context: Context, intent: Intent): Boolean = try {
    context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    true
} catch (e: ActivityNotFoundException) {
    false
} catch (e: SecurityException) {
    false
}

/**
 * Vendors that keep a battery list of their own on top of Android's, where an app also has to
 * be let through. None of these screens is a public API; the names are the ones known to work
 * per vendor (see dontkillmyapp.com), and a phone where none opens gets app info. Other vendors
 * (Google, Motorola, Nokia, OnePlus on OxygenOS 12 and later) use Android's own setting, which
 * the main button covers.
 */
internal enum class OemBattery(val label: Int, private val screens: List<(String) -> Intent>) {
    SAMSUNG(
        R.string.battery_hint_device_care,
        listOf(
            { _ -> component("com.samsung.android.lool", "com.samsung.android.sm.battery.ui.BatteryActivity") },
            { _ -> component("com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity") }
        )
    ),
    XIAOMI(
        R.string.battery_hint_xiaomi,
        listOf(
            // The app's own page in MIUI and HyperOS battery saver, then the list it is on.
            { pkg ->
                component("com.miui.powerkeeper", "com.miui.powerkeeper.ui.HiddenAppsConfigActivity")
                    .putExtra("package_name", pkg)
                    .putExtra("package_label", "Life Dashboard")
            },
            { _ -> Intent("miui.intent.action.POWER_HIDE_MODE_APP_LIST").addCategory(Intent.CATEGORY_DEFAULT) }
        )
    ),
    HUAWEI(
        R.string.battery_hint_huawei,
        listOf(
            { _ -> component("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity") },
            { _ -> component("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity") }
        )
    );

    fun intents(packageName: String): List<Intent> = screens.map { it(packageName) }

    companion object {
        fun forManufacturer(manufacturer: String): OemBattery? = when (manufacturer.lowercase()) {
            "samsung" -> SAMSUNG
            "xiaomi", "redmi", "poco" -> XIAOMI
            "huawei", "honor" -> HUAWEI
            else -> null
        }

        private fun component(pkg: String, cls: String): Intent = Intent().setComponent(ComponentName(pkg, cls))
    }
}
