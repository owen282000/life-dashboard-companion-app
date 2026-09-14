package com.owen282000.lifedashboard.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import com.owen282000.lifedashboard.HealthConnectManager
import com.owen282000.lifedashboard.LifetimeStats
import com.owen282000.lifedashboard.R
import com.owen282000.lifedashboard.ScreenTimeData
import com.owen282000.lifedashboard.ScreenTimeManager
import com.owen282000.lifedashboard.SyncStatusStore
import com.owen282000.lifedashboard.appPreferences
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
            val enabled = context.appPreferences().getHealthEnabledDataTypes()
            stepsPerDay = HealthConnectManager(context)
                .readDailyTotals(days = 6, enabledTypes = enabled)
                .mapNotNull { it.steps }
        } catch (e: Exception) {
            // Health Connect unavailable or no permission; the sparkline simply hides.
        }
    }

    PremiumCard {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                StatTile(stringResource(R.string.dashboard_today), "${status?.recordsToday ?: 0}", stringResource(R.string.dashboard_records))
                StatTile(stringResource(R.string.dashboard_lifetime), "${stats?.records ?: 0}", stringResource(R.string.dashboard_records))
                LastSyncTile(
                    millis = status?.lastSyncMillis,
                    dotColor = if (status?.lastSuccess != false) HealthPrimary else MaterialTheme.colorScheme.error
                )
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
 * Screen time counterpart of [DashboardCard]: today's minutes, today's most used app (as its
 * icon), the last screen time sync, and a 7-day minutes sparkline. Reads the usage events off
 * the main thread; [refreshKey] reloads after a manual sync.
 */
@Composable
fun ScreenTimeDashboardCard(refreshKey: Any?) {
    val context = LocalContext.current
    val preferencesManager = remember { context.appPreferences() }
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

    PremiumCard {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                StatTile(stringResource(R.string.dashboard_today), "${(today?.totalScreenTimeMs ?: 0L) / 60000}", stringResource(R.string.dashboard_min))
                Column {
                    Text(stringResource(R.string.dashboard_top_app), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (topApp != null) {
                            AppIcon(packageName = topApp.packageName, appName = topApp.appName)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("${topApp.totalTimeMs / 60000}", fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(stringResource(R.string.dashboard_min), style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
                        } else {
                            Text(stringResource(R.string.dashboard_none), fontSize = 22.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
                LastSyncTile(
                    millis = lastSyncMillis,
                    dotColor = if (lastSyncMillis != null) ScreenTimePrimary else MaterialTheme.colorScheme.outline
                )
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

/**
 * The launcher icon of a package, or a generic tile when the package is not visible to the
 * app. Same visibility rule as the app label: only what UsageStatsManager already reported,
 * never widened with QUERY_ALL_PACKAGES (see ScreenTimeManager).
 */
@Composable
fun AppIcon(packageName: String, appName: String, size: Dp = 24.dp) {
    val context = LocalContext.current
    val bitmap = remember(packageName) {
        runCatching {
            context.packageManager.getApplicationIcon(packageName).toBitmap(96, 96).asImageBitmap()
        }.getOrNull()
    }
    if (bitmap != null) {
        Image(
            bitmap = bitmap,
            contentDescription = appName,
            modifier = Modifier
                .size(size)
                .clip(RoundedCornerShape(6.dp))
        )
    } else {
        Box(
            modifier = Modifier
                .size(size)
                .clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Outlined.Apps, contentDescription = appName, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(size * 0.6f))
        }
    }
}

@Composable
private fun StatTile(label: String, value: String, unit: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            Spacer(modifier = Modifier.width(4.dp))
            Text(unit, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 4.dp))
        }
    }
}

@Composable
private fun LastSyncTile(millis: Long?, dotColor: Color) {
    Column(horizontalAlignment = Alignment.End) {
        Text(stringResource(R.string.dashboard_last_sync), style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(dotColor)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                millis?.let { DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(it)) }
                    ?: stringResource(R.string.common_never),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
private fun Sparkline(values: List<Long>, modifier: Modifier = Modifier, color: Color = HealthPrimary) {
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
