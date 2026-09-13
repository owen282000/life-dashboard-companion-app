package com.owen282000.lifedashboard.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.owen282000.lifedashboard.*
import com.owen282000.lifedashboard.R
import com.owen282000.lifedashboard.ui.theme.HealthPrimary
import com.owen282000.lifedashboard.ui.theme.ScreenTimePrimary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

/**
 * Compact at-a-glance dashboard at the top of the Health screen: delivery stats from
 * LifetimeStats/SyncStatusStore and a 7-day steps sparkline from the deduplicated daily
 * totals. Everything is read-only and loads off the main thread.
 */
@Composable
fun DashboardCard() {
    val context = LocalContext.current
    var status by remember { mutableStateOf<SyncStatusStore.Status?>(null) }
    var stats by remember { mutableStateOf<LifetimeStats.Stats?>(null) }
    var stepsPerDay by remember { mutableStateOf<List<Long>>(emptyList()) }

    LaunchedEffect(Unit) {
        status = SyncStatusStore.read(context)
        stats = LifetimeStats.read(context)
        try {
            val enabled = PreferencesManager(context).getHealthEnabledDataTypes()
            stepsPerDay = HealthConnectManager(context)
                .readDailyTotals(days = 6, enabledTypes = enabled)
                .mapNotNull { it.steps }
        } catch (e: Exception) {
            // Health Connect unavailable or no permission; the sparkline simply hides.
        }
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                StatTile(stringResource(R.string.dashboard_today), "${status?.recordsToday ?: 0}", stringResource(R.string.dashboard_records))
                StatTile(stringResource(R.string.dashboard_lifetime), "${stats?.records ?: 0}", stringResource(R.string.dashboard_records))
                Column(horizontalAlignment = Alignment.End) {
                    Text(stringResource(R.string.dashboard_last_sync), style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(if (status?.lastSuccess != false) HealthPrimary else MaterialTheme.colorScheme.error)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            status?.lastSyncMillis?.let {
                                DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(it))
                            } ?: stringResource(R.string.common_never),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }

            if (stepsPerDay.size >= 2) {
                Column {
                    Text(
                        stringResource(R.string.dashboard_steps_last_days, stepsPerDay.size),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Sparkline(
                        values = stepsPerDay,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(36.dp)
                            .padding(top = 4.dp)
                    )
                }
            }
        }
    }
}

/**
 * Screen time counterpart of [DashboardCard]: today's minutes, today's most used app, the last
 * screen time sync, and a 7-day minutes sparkline. Reads the usage events off the main thread;
 * [refreshKey] reloads after a manual sync.
 */
@Composable
fun ScreenTimeDashboardCard(refreshKey: Any?) {
    val context = LocalContext.current
    val preferencesManager = remember { PreferencesManager(context) }
    var days by remember { mutableStateOf<List<ScreenTimeData>>(emptyList()) }
    var lastSyncMillis by remember { mutableStateOf<Long?>(null) }

    LaunchedEffect(refreshKey) {
        lastSyncMillis = preferencesManager.getScreenTimeLastSyncTimestamp()
        days = withContext(Dispatchers.IO) {
            ScreenTimeManager(context, preferencesManager).readScreenTimeData(lookbackDays = 7)
                .getOrDefault(emptyList())
        }
    }

    val today = days.maxByOrNull { it.date }
    val topApp = today?.apps?.maxByOrNull { it.totalTimeMs }
    val minutesPerDay = days.sortedBy { it.date }.map { it.totalScreenTimeMs / 60000 }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                StatTile(stringResource(R.string.dashboard_today), "${(today?.totalScreenTimeMs ?: 0L) / 60000}", stringResource(R.string.dashboard_min))
                StatTile(
                    stringResource(R.string.dashboard_top_app),
                    topApp?.appName ?: stringResource(R.string.dashboard_none),
                    topApp?.let { stringResource(R.string.dashboard_minutes_unit, (it.totalTimeMs / 60000).toInt()) } ?: ""
                )
                Column(horizontalAlignment = Alignment.End) {
                    Text(stringResource(R.string.dashboard_last_sync), style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(if (lastSyncMillis != null) ScreenTimePrimary else MaterialTheme.colorScheme.outline)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            lastSyncMillis?.let {
                                DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(it))
                            } ?: stringResource(R.string.common_never),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }

            if (minutesPerDay.size >= 2) {
                Column {
                    Text(
                        stringResource(R.string.dashboard_minutes_per_day_last_days, minutesPerDay.size),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Sparkline(
                        values = minutesPerDay,
                        color = ScreenTimePrimary,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(36.dp)
                            .padding(top = 4.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun StatTile(label: String, value: String, unit: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1)
            Spacer(modifier = Modifier.width(4.dp))
            Text(unit, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 2.dp))
        }
    }
}

@Composable
private fun Sparkline(values: List<Long>, modifier: Modifier = Modifier, color: androidx.compose.ui.graphics.Color = HealthPrimary) {
    Canvas(modifier = modifier) {
        val min = values.min()
        val max = values.max()
        val range = (max - min).coerceAtLeast(1)
        val stepX = size.width / (values.size - 1).coerceAtLeast(1)
        val path = Path()
        values.forEachIndexed { index, value ->
            val x = index * stepX
            val y = size.height - ((value - min).toFloat() / range) * size.height
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, color = color, style = Stroke(width = 4f))
    }
}
