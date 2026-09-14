package com.owen282000.lifedashboard

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Article
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.DirectionsWalk
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Gavel
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.LocalCafe
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.MonitorHeart
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.PrivacyTip
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.SelfImprovement
import androidx.compose.material.icons.outlined.SettingsBackupRestore
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.owen282000.lifedashboard.screens.ConfigBackupSection
import com.owen282000.lifedashboard.screens.IconTile
import com.owen282000.lifedashboard.screens.PremiumCard
import com.owen282000.lifedashboard.ui.theme.BrandGreen
import com.owen282000.lifedashboard.ui.theme.BrandGround
import com.owen282000.lifedashboard.ui.theme.BrandGroundDeep
import com.owen282000.lifedashboard.ui.theme.BrandGroundLight
import com.owen282000.lifedashboard.ui.theme.HealthPrimary
import com.owen282000.lifedashboard.ui.theme.LifeDashboardTheme
import com.owen282000.lifedashboard.ui.theme.ScreenTimePrimary
import com.owen282000.lifedashboard.ui.theme.Success
import kotlinx.coroutines.delay

private const val REPO_URL = "https://github.com/owen282000/life-dashboard-companion-app"

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

        // Easter eggs: tap the mark 7 times for a heart beating at your real heart rate,
        // long-press the version pill for Nerd Stats
        var heartTapCount by remember { mutableStateOf(0) }
        var isBeating by remember { mutableStateOf(false) }
        var bpm by remember { mutableStateOf(72L) }
        var showNerdStats by remember { mutableStateOf(false) }
        val heartScale = remember { Animatable(1f) }
        val haptics = LocalHapticFeedback.current

        LaunchedEffect(isBeating) {
            if (!isBeating) {
                heartScale.snapTo(1f)
                return@LaunchedEffect
            }
            HealthConnectManager(context).latestHeartRateBpm()?.let { bpm = it.coerceIn(30, 200) }
            while (isBeating) {
                val cycleMs = 60_000L / bpm
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                heartScale.animateTo(1.18f, tween(120))
                heartScale.animateTo(1f, tween(110))
                heartScale.animateTo(1.10f, tween(100))
                heartScale.animateTo(1f, tween(100))
                delay((cycleMs - 430).coerceAtLeast(50))
            }
        }

        val versionName = try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "1.0"
        } catch (e: PackageManager.NameNotFoundException) {
            "1.0"
        }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(R.string.about_title), fontWeight = FontWeight.SemiBold) },
                    navigationIcon = {
                        IconButton(onClick = { finish() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.about_back))
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
                )
            },
            containerColor = MaterialTheme.colorScheme.background
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
            ) {
                // Hero on the brand ground from the banner and the icon, with the mark itself.
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(bottomStart = 28.dp, bottomEnd = 28.dp))
                        .background(
                            Brush.radialGradient(
                                colors = listOf(BrandGroundLight, BrandGround, BrandGroundDeep),
                                radius = 900f
                            )
                        )
                        .padding(top = 20.dp, bottom = 30.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(110.dp)
                                .clickable {
                                    heartTapCount++
                                    if (heartTapCount >= 7) {
                                        heartTapCount = 0
                                        isBeating = !isBeating
                                    }
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(120.dp)
                                    .drawBehind {
                                        drawCircle(Brush.radialGradient(listOf(BrandGreen.copy(alpha = 0.28f), Color.Transparent)))
                                    }
                            )
                            if (isBeating) {
                                Icon(
                                    Icons.Filled.Favorite,
                                    contentDescription = null,
                                    tint = BrandGreen,
                                    modifier = Modifier
                                        .size(56.dp)
                                        .scale(heartScale.value)
                                )
                            } else {
                                Image(
                                    painter = painterResource(R.drawable.ic_launcher_foreground),
                                    contentDescription = null,
                                    modifier = Modifier.requiredSize(200.dp)
                                )
                            }
                        }
                        Text(
                            "Life Dashboard",
                            fontSize = 30.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Text(
                            "Companion",
                            style = MaterialTheme.typography.titleLarge,
                            color = Color(0xFFB9C2C6)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Surface(
                            shape = CircleShape,
                            color = BrandGreen.copy(alpha = 0.18f),
                            modifier = Modifier.pointerInput(Unit) {
                                detectTapGestures(onLongPress = { showNerdStats = !showNerdStats })
                            }
                        ) {
                            Text(
                                if (isBeating) stringResource(R.string.about_bpm, bpm) else stringResource(R.string.about_version, versionName),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = BrandGreen,
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                            )
                        }
                    }
                }

                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (showNerdStats) NerdStatsCard()

                    Text(
                        stringResource(R.string.about_description),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 4.dp)
                    )

                    FeatureCard(Icons.Outlined.FavoriteBorder, HealthPrimary, stringResource(R.string.main_title_health_connect)) {
                        FeatureItem(Icons.Outlined.DirectionsWalk, stringResource(R.string.about_f_steps))
                        FeatureItem(Icons.Outlined.Bedtime, stringResource(R.string.about_f_sleep))
                        FeatureItem(Icons.Outlined.SelfImprovement, stringResource(R.string.about_f_meditation))
                        FeatureItem(Icons.Outlined.MonitorHeart, stringResource(R.string.about_f_heart))
                    }
                    FeatureCard(Icons.Outlined.PhoneAndroid, ScreenTimePrimary, stringResource(R.string.main_title_screen_time)) {
                        FeatureItem(Icons.Outlined.Apps, stringResource(R.string.about_f_apps))
                        FeatureItem(Icons.Outlined.Schedule, stringResource(R.string.about_f_boundary))
                        FeatureItem(Icons.Outlined.History, stringResource(R.string.about_f_lookback))
                    }
                    FeatureCard(Icons.Outlined.Shield, Success, stringResource(R.string.about_privacy_title)) {
                        FeatureItem(Icons.Outlined.Lock, stringResource(R.string.about_f_no_sharing))
                        FeatureItem(Icons.Outlined.Storage, stringResource(R.string.about_f_on_device))
                        FeatureItem(Icons.Outlined.Tune, stringResource(R.string.about_f_control))
                    }
                    FeatureCard(Icons.Outlined.SettingsBackupRestore, HealthPrimary, stringResource(R.string.about_backup_title)) {
                        ConfigBackupSection()
                    }

                    LinkCard(Icons.Outlined.Article, stringResource(R.string.about_docs), stringResource(R.string.about_docs_sub), "$REPO_URL/blob/main/docs/usage.md")
                    LinkCard(Icons.Outlined.AutoAwesome, stringResource(R.string.about_whats_new), stringResource(R.string.about_whats_new_sub, versionName), "$REPO_URL/releases/tag/$versionName")
                    LinkCard(Icons.Outlined.BugReport, stringResource(R.string.about_report), stringResource(R.string.about_report_sub), "$REPO_URL/issues/new")
                    LinkCard(Icons.Outlined.Code, stringResource(R.string.about_github), "owen282000/life-dashboard-companion-app", REPO_URL)
                    LinkCard(Icons.Outlined.LocalCafe, stringResource(R.string.about_coffee), stringResource(R.string.about_coffee_sub), "https://ko-fi.com/owen282000")
                    LinkCard(Icons.Outlined.PrivacyTip, stringResource(R.string.about_privacy_policy), stringResource(R.string.about_privacy_policy_sub), "$REPO_URL/blob/main/PRIVACY.md")
                    LinkCard(Icons.Outlined.Gavel, stringResource(R.string.about_licence), stringResource(R.string.about_licence_sub), "$REPO_URL/blob/main/LICENSE")

                    Spacer(modifier = Modifier.height(16.dp))
                }
            }
        }
    }
}

/** A tappable card that opens [url] in the browser. */
@Composable
private fun LinkCard(icon: ImageVector, title: String, subtitle: String, url: String) {
    val context = LocalContext.current
    PremiumCard(shape = RoundedCornerShape(16.dp)) {
        Row(
            modifier = Modifier
                .clickable { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconTile(icon, HealthPrimary, size = 40)
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(
                Icons.AutoMirrored.Filled.OpenInNew,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

@Composable
private fun FeatureCard(
    icon: ImageVector,
    iconTint: Color,
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    PremiumCard {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconTile(icon, iconTint, size = 40)
                Spacer(modifier = Modifier.width(14.dp))
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
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
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
        Spacer(modifier = Modifier.width(12.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun NerdStatsCard() {
    val context = LocalContext.current
    val stats = remember { LifetimeStats.read(context) }

    PremiumCard {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.AutoAwesome, contentDescription = null, tint = Color(0xFFF9A825), modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.nerd_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(modifier = Modifier.weight(1f))
                Text(stringResource(R.string.nerd_secret), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(modifier = Modifier.height(12.dp))

            if (stats.deliveries == 0) {
                Text(stringResource(R.string.nerd_empty), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                NerdStatRow(stringResource(R.string.nerd_records), "%,d".format(stats.records))
                NerdStatRow(stringResource(R.string.nerd_deliveries), "%,d".format(stats.deliveries))
                if (stats.largestPayloadBytes > 0) {
                    NerdStatRow(stringResource(R.string.nerd_largest), android.text.format.Formatter.formatShortFileSize(context, stats.largestPayloadBytes.toLong()))
                }
                stats.firstSyncMillis?.let { first ->
                    val days = ((System.currentTimeMillis() - first) / 86_400_000L).coerceAtLeast(1).toInt()
                    NerdStatRow(stringResource(R.string.nerd_since), java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(java.util.Date(first)))
                    NerdStatRow(stringResource(R.string.nerd_that_is), pluralStringResource(R.plurals.nerd_days, days, days))
                }
            }
        }
    }
}

@Composable
private fun NerdStatRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
    }
}
