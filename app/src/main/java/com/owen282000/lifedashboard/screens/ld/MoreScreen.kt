package com.owen282000.lifedashboard.screens.ld

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.owen282000.lifedashboard.R
import com.owen282000.lifedashboard.ThemeMode
import com.owen282000.lifedashboard.appPreferences
import com.owen282000.lifedashboard.ui.ld.LDCard
import com.owen282000.lifedashboard.ui.ld.LDGap
import com.owen282000.lifedashboard.ui.ld.LDHeading
import com.owen282000.lifedashboard.ui.ld.LDRoute
import com.owen282000.lifedashboard.ui.ld.LDRow
import com.owen282000.lifedashboard.ui.ld.LDScreen
import com.owen282000.lifedashboard.ui.ld.LDSectionLabel
import com.owen282000.lifedashboard.ui.ld.LDSegment
import com.owen282000.lifedashboard.ui.ld.LDSegmented
import com.owen282000.lifedashboard.ui.ld.LocalLDNavigator
import com.owen282000.lifedashboard.ui.ld.ldLeftToRight
import com.owen282000.lifedashboard.ui.ld.ldStaggerRise
import com.owen282000.lifedashboard.ui.theme.chosenThemeMode

@Composable
fun MoreScreen() {
    val viewModel = ldHealthViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val navigator = LocalLDNavigator.current
    val context = LocalContext.current
    val prefs = remember(context) { context.appPreferences() }

    val advancedSummary = listOfNotNull(
        stringResource(if (state.includeDailyTotals) R.string.health_daily_totals else R.string.health_no_daily_totals),
        if (state.clientCertAlias != null) stringResource(R.string.sync_advanced_client_cert) else null
    ).joinToString(", ")
    val notificationsSummary = if (state.failureNotificationsEnabled) {
        pluralStringResource(R.plurals.health_notifications_on, state.failureThreshold, state.failureThreshold)
    } else {
        stringResource(R.string.health_notifications_off)
    }

    LDScreen(title = stringResource(R.string.ld_title_more)) {
        LDHeading(stringResource(R.string.ld_title_more))
        LDGap(12.dp)
        LDCard(Modifier.ldStaggerRise(0)) {
            LDRow(stringResource(R.string.sync_advanced_title), value = advancedSummary, onClick = { navigator.push(LDRoute.Advanced()) })
            LDRow(stringResource(R.string.health_notifications_title), value = notificationsSummary, onClick = { navigator.push(LDRoute.NotificationSettings) })
        }
        LDGap(10.dp)
        LDCard(Modifier.ldStaggerRise(1)) {
            LDRow(
                stringResource(R.string.ld_title_export_data),
                value = listOf(stringResource(R.string.common_csv), stringResource(R.string.common_json)).joinToString(", "),
                onClick = { navigator.push(LDRoute.ExportData()) }
            )
            LDRow(stringResource(R.string.ld_title_backup_restore), value = stringResource(R.string.screentime_settings), onClick = { navigator.push(LDRoute.BackupRestore) })
        }
        LDSectionLabel(stringResource(R.string.about_appearance_title))
        LDSegmented(
            selected = chosenThemeMode(),
            segments = ThemeMode.entries.map { LDSegment(it, stringResource(it.label)) },
            onSelect = prefs::setThemeMode,
            modifier = Modifier.ldStaggerRise(2),
            label = stringResource(R.string.about_appearance_title)
        )
        LDGap(14.dp)
        LDCard(Modifier.ldStaggerRise(3)) {
            LDRow(stringResource(R.string.about_title), value = ldVersionName().ldLeftToRight, onClick = { navigator.push(LDRoute.AboutHome) })
        }
    }
}

@get:StringRes
private val ThemeMode.label: Int
    get() = when (this) {
        ThemeMode.SYSTEM -> R.string.about_theme_system
        ThemeMode.LIGHT -> R.string.about_theme_light
        ThemeMode.DARK -> R.string.about_theme_dark
    }
