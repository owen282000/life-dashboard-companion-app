package com.owen282000.lifedashboard.screens.ld

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri
import com.owen282000.lifedashboard.R
import com.owen282000.lifedashboard.ui.ld.LDRow

/** Every address the About pages open. */
internal object AboutLinks {
    const val REPOSITORY = "https://github.com/owen282000/life-dashboard-companion-app"
    const val WEBSITE = "https://owen282000.com/life-dashboard/"
    const val DOCUMENTATION = "$REPOSITORY/blob/main/docs/usage.md"
    const val NEW_ISSUE = "$REPOSITORY/issues/new"
    const val LICENCE = "$REPOSITORY/blob/main/LICENSE"
    const val PLAY_LISTING = "https://play.google.com/store/apps/details?id=com.owen282000.lifedashboard&referrer=utm_source%3Dapp-about%26utm_medium%3Dapp"
    const val RATE = "https://play.google.com/store/apps/details?id=com.owen282000.lifedashboard"
    const val COFFEE = "https://ko-fi.com/owen282000"
    const val QUESTIONS = "mailto:owen282000@hotmail.com"
    const val STACK = "https://owen282000.com/life-dashboard/grafana/"

    /** How anyone adds a pairing code to their own receiver. */
    const val PAIRING_GUIDE = "https://owen282000.com/life-dashboard/health-data-qr-pairing/"

    fun release(version: String) = "$REPOSITORY/releases/tag/$version"
}

/** A row on the About pages that leaves the app, for a web page or the mail app. */
@Composable
internal fun AboutLinkRow(title: String, url: String, subtitle: String? = null, value: String? = null) {
    val context = LocalContext.current
    LDRow(title, subtitle = subtitle, value = value, onClick = { context.openLink(url) })
}

/** A phone without a browser has nothing to open the link with. */
internal fun Context.openLink(url: String) {
    try {
        startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(this, getString(R.string.ld_about_no_app_for_link), Toast.LENGTH_SHORT).show()
    }
}

@Composable
internal fun ldVersionName(): String {
    val context = LocalContext.current
    return remember(context) {
        try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
        } catch (e: PackageManager.NameNotFoundException) {
            ""
        }
    }
}
