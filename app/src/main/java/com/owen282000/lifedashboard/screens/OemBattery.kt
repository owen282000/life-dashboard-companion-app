package com.owen282000.lifedashboard.screens

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import com.owen282000.lifedashboard.R

internal const val TROUBLESHOOTING_URL = "https://owen282000.com/life-dashboard/troubleshooting/"

internal fun isOptimized(context: Context): Boolean {
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
internal fun fixNow(context: Context, askExemption: Boolean) {
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
internal fun openVendorScreen(context: Context, oem: OemBattery) {
    for (intent in oem.intents(context.packageName)) {
        if (tryStart(context, intent)) return
    }
    openAppSettings(context)
}

internal fun tryStart(context: Context, intent: Intent): Boolean = try {
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
