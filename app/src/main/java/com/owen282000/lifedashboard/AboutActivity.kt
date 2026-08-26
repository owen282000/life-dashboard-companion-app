package com.owen282000.lifedashboard

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.owen282000.lifedashboard.ui.theme.*

class AboutActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            LifeDashboardTheme {
                AboutScreen()
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun AboutScreen() {
        val context = LocalContext.current

        // Easter eggs: tap the icon 7 times for a heart beating at your real heart rate,
        // long-press the version pill for Nerd Stats
        var heartTapCount by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(0) }
        var isBeating by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
        var bpm by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(72L) }
        var showNerdStats by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
        val heartScale = androidx.compose.runtime.remember { androidx.compose.animation.core.Animatable(1f) }
        val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current

        androidx.compose.runtime.LaunchedEffect(isBeating) {
            if (!isBeating) {
                heartScale.snapTo(1f)
                return@LaunchedEffect
            }
            HealthConnectManager(context).latestHeartRateBpm()?.let { bpm = it.coerceIn(30, 200) }
            while (isBeating) {
                val cycleMs = 60_000L / bpm
                haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                heartScale.animateTo(1.18f, androidx.compose.animation.core.tween(120))
                heartScale.animateTo(1f, androidx.compose.animation.core.tween(110))
                heartScale.animateTo(1.10f, androidx.compose.animation.core.tween(100))
                heartScale.animateTo(1f, androidx.compose.animation.core.tween(100))
                kotlinx.coroutines.delay((cycleMs - 430).coerceAtLeast(50))
            }
        }

        val versionName = try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "1.0"
        } catch (e: PackageManager.NameNotFoundException) {
            "1.0"
        }
        val versionCode = try {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0).versionCode.toLong()
        } catch (e: PackageManager.NameNotFoundException) {
            1L
        }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("About") },
                    navigationIcon = {
                        IconButton(onClick = { finish() }) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back"
                            )
                        }
                    }
                )
            }
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
            ) {
                // Header with gradient
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            brush = Brush.verticalGradient(
                                colors = listOf(
                                    Primary,
                                    PrimaryDark
                                )
                            )
                        )
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Box(
                            modifier = Modifier
                                .size(80.dp)
                                .clip(CircleShape)
                                .background(Color.White.copy(alpha = 0.2f))
                                .clickable {
                                    heartTapCount++
                                    if (heartTapCount >= 7) {
                                        heartTapCount = 0
                                        isBeating = !isBeating
                                    }
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = if (isBeating) Icons.Filled.Favorite else Icons.Filled.Dashboard,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier
                                    .size(44.dp)
                                    .scale(heartScale.value)
                            )
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            "Life Dashboard",
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Text(
                            "Companion",
                            style = MaterialTheme.typography.titleLarge,
                            color = Color.White.copy(alpha = 0.8f)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = Color.White.copy(alpha = 0.2f),
                            modifier = Modifier.pointerInput(Unit) {
                                detectTapGestures(onLongPress = { showNerdStats = !showNerdStats })
                            }
                        ) {
                            Text(
                                if (isBeating) "$bpm BPM" else "Version $versionName",
                                style = MaterialTheme.typography.labelMedium,
                                color = Color.White,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                            )
                        }
                    }
                }

                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    if (showNerdStats) {
                        NerdStatsCard(context)
                    }

                    // Description
                    Text(
                        "Syncs Health Connect and Screen Time data to your custom webhook endpoints for automated life tracking.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    // Features Section
                    AboutSectionCard(
                        icon = Icons.Outlined.Favorite,
                        iconTint = HealthPrimary,
                        title = "Health Connect"
                    ) {
                        FeatureItem(Icons.Outlined.DirectionsWalk, "Steps, distance, calories")
                        FeatureItem(Icons.Outlined.Bedtime, "Sleep tracking with phases")
                        FeatureItem(Icons.Outlined.SelfImprovement, "Meditation sessions")
                        FeatureItem(Icons.Outlined.MonitorHeart, "Heart rate & more")
                    }

                    AboutSectionCard(
                        icon = Icons.Outlined.PhoneAndroid,
                        iconTint = ScreenTimePrimary,
                        title = "Screen Time"
                    ) {
                        FeatureItem(Icons.Outlined.Apps, "App usage statistics")
                        FeatureItem(Icons.Outlined.Schedule, "Configurable day boundary")
                        FeatureItem(Icons.Outlined.History, "7-day lookback window")
                    }

                    AboutSectionCard(
                        icon = Icons.Outlined.Shield,
                        iconTint = Success,
                        title = "Privacy & Security"
                    ) {
                        FeatureItem(Icons.Outlined.Lock, "No third-party data sharing")
                        FeatureItem(Icons.Outlined.Storage, "Data stays on your device")
                        FeatureItem(Icons.Outlined.Tune, "Full control over sync settings")
                    }

                    // GitHub Link
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surface,
                        tonalElevation = 1.dp
                    ) {
                        Row(
                            modifier = Modifier
                                .clickable {
                                    val intent = Intent(
                                        Intent.ACTION_VIEW,
                                        Uri.parse("https://github.com/owen282000/life-dashboard-companion-app")
                                    )
                                    context.startActivity(intent)
                                }
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Code,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    "View on GitHub",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    "owen282000/life-dashboard-companion-app",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                }
            }
        }
    }
}

@Composable
private fun AboutSectionCard(
    icon: ImageVector,
    iconTint: Color,
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(iconTint.copy(alpha = 0.1f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = iconTint,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            content()
        }
    }
}

@Composable
private fun FeatureItem(icon: ImageVector, text: String) {
    Row(
        modifier = Modifier.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun NerdStatsCard(context: android.content.Context) {
    val stats = remember { LifetimeStats.read(context) }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.AutoAwesome,
                    contentDescription = null,
                    tint = Color(0xFFF9A825),
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    "Nerd Stats",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    "You found the secret",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.height(12.dp))

            if (stats.deliveries == 0) {
                Text(
                    "No syncs yet. Come back when your data has started flowing.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                NerdStatRow("Records delivered", "%,d".format(stats.records))
                NerdStatRow("Successful deliveries", "%,d".format(stats.deliveries))
                if (stats.largestPayloadBytes > 0) {
                    NerdStatRow("Largest payload", android.text.format.Formatter.formatShortFileSize(context, stats.largestPayloadBytes.toLong()))
                }
                stats.firstSyncMillis?.let { first ->
                    val days = ((System.currentTimeMillis() - first) / 86_400_000L).coerceAtLeast(1)
                    NerdStatRow("Syncing since", java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(java.util.Date(first)))
                    NerdStatRow("That is", "$days day${if (days == 1L) "" else "s"} of quantified you")
                }
            }
        }
    }
}

@Composable
private fun NerdStatRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold
        )
    }
}
