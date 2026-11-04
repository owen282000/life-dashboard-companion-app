package com.owen282000.lifedashboard.screens.ld

import android.text.format.Formatter
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.owen282000.lifedashboard.LifetimeStats
import com.owen282000.lifedashboard.LogType
import com.owen282000.lifedashboard.R
import com.owen282000.lifedashboard.SyncOutcomeText
import com.owen282000.lifedashboard.SyncStatusStore
import com.owen282000.lifedashboard.ui.ld.LDHeading
import com.owen282000.lifedashboard.ui.ld.LDKeyValue
import com.owen282000.lifedashboard.ui.ld.LDKeyValueList
import com.owen282000.lifedashboard.ui.ld.LDNote
import com.owen282000.lifedashboard.ui.ld.LDPulseVariant
import com.owen282000.lifedashboard.ui.ld.LDScreen
import com.owen282000.lifedashboard.ui.ld.LDTheme
import com.owen282000.lifedashboard.ui.ld.PulseLine
import com.owen282000.lifedashboard.ui.ld.ldStaggerRise
import java.text.DateFormat
import java.text.NumberFormat
import java.util.Date

/**
 * The lifetime figures. The old Health card's Lifetime, Today and Last sync live here too, with the
 * figures per section: Home says where the data goes and Today what it is, not these counters.
 */
@Composable
fun AboutNerdScreen() {
    val context = LocalContext.current
    val healthState by ldHealthViewModel().state.collectAsStateWithLifecycle()
    val stats = remember(healthState.refreshKey) { LifetimeStats.read(context) }
    val health = remember(healthState.refreshKey) { LifetimeStats.read(context, LogType.HEALTH_CONNECT) }
    val screenTime = remember(healthState.refreshKey) { LifetimeStats.read(context, LogType.SCREEN_TIME) }
    val status = remember(healthState.refreshKey) { SyncStatusStore.read(context, LogType.HEALTH_CONNECT) }
    val colors = LDTheme.colors

    LDScreen(title = stringResource(R.string.nerd_title)) {
        LDHeading(stringResource(R.string.nerd_secret))
        PulseLine(variant = if (stats.deliveries == 0) LDPulseVariant.Flat else LDPulseVariant.Normal)

        if (stats.deliveries == 0) LDNote(stringResource(R.string.nerd_empty), top = 4.dp)
        val numbers = NumberFormat.getIntegerInstance()
        val lastSync = status.lastSyncMillis?.let { millis ->
            val time = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(millis))
            LDKeyValue(
                stringResource(R.string.dashboard_last_sync),
                SyncOutcomeText.dashboardRes(status.outcome)?.let { stringResource(R.string.ld_nerd_last_sync_outcome, time, stringResource(it)) } ?: time,
                color = if (status.lastSuccess) colors.text else colors.warning
            )
        }
        val figures = if (stats.deliveries == 0) {
            listOfNotNull(lastSync)
        } else {
            listOfNotNull(
                LDKeyValue(stringResource(R.string.nerd_records), numbers.format(stats.records)),
                LDKeyValue(stringResource(R.string.ld_nerd_records_health), numbers.format(health.records)),
                LDKeyValue(stringResource(R.string.ld_nerd_records_screen_time), numbers.format(screenTime.records)),
                LDKeyValue(stringResource(R.string.ld_nerd_records_today), numbers.format(status.recordsToday)),
                LDKeyValue(stringResource(R.string.nerd_deliveries), numbers.format(stats.deliveries)),
                if (stats.largestPayloadBytes > 0) {
                    LDKeyValue(stringResource(R.string.nerd_largest), Formatter.formatShortFileSize(context, stats.largestPayloadBytes.toLong()))
                } else {
                    null
                },
                stats.firstSyncMillis?.let { first ->
                    LDKeyValue(stringResource(R.string.nerd_since), DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(first)))
                },
                lastSync
            )
        }
        if (figures.isNotEmpty()) LDKeyValueList(figures, Modifier.ldStaggerRise())
        stats.firstSyncMillis?.takeIf { stats.deliveries > 0 }?.let { first ->
            val days = ((System.currentTimeMillis() - first) / 86_400_000L).coerceAtLeast(1).toInt()
            // Two keys that the old card showed as a label and its value, translated apart.
            LDNote(
                "${stringResource(R.string.nerd_that_is)} ${pluralStringResource(R.plurals.nerd_days, days, days)}",
                modifier = Modifier.ldStaggerRise(1),
                color = colors.accentTextSoft,
                top = 12.dp
            )
        }
    }
}
