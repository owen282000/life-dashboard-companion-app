package com.owen282000.lifedashboard

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.MailOutline
import androidx.compose.material.icons.outlined.PrivacyTip
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.owen282000.lifedashboard.screens.IconTile
import com.owen282000.lifedashboard.screens.PremiumCard
import com.owen282000.lifedashboard.ui.theme.HealthPrimary
import com.owen282000.lifedashboard.ui.theme.LifeDashboardTheme
import com.owen282000.lifedashboard.ui.theme.Success

const val PRIVACY_POLICY_URL = "https://github.com/owen282000/life-dashboard-companion-app/blob/main/PRIVACY.md"

/**
 * The privacy policy in short, with a link to the full PRIVACY.md.
 *
 * Health Connect opens this from the privacy policy link on its permission screen, through
 * ACTION_SHOW_PERMISSIONS_RATIONALE up to Android 13 and the ViewPermissionUsageActivity alias
 * from Android 14 (see the manifest). Play expects a readable policy here, not the app's home
 * screen. Keep the text in step with PRIVACY.md.
 */
class PrivacyPolicyActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            LifeDashboardTheme {
                PrivacyPolicyScreen(onBack = { finish() })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PrivacyPolicyScreen(onBack: () -> Unit) {
    val context = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.privacy_title), fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
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
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                stringResource(R.string.privacy_intro),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 4.dp)
            )

            PolicySection(Icons.Outlined.Visibility, stringResource(R.string.privacy_reads_title), stringResource(R.string.privacy_reads_body))
            PolicySection(Icons.Outlined.EditNote, stringResource(R.string.privacy_writes_title), stringResource(R.string.privacy_writes_body))
            PolicySection(Icons.Outlined.CloudUpload, stringResource(R.string.privacy_where_title), stringResource(R.string.privacy_where_body))
            PolicySection(Icons.Outlined.Storage, stringResource(R.string.privacy_device_title), stringResource(R.string.privacy_device_body))
            PolicySection(Icons.Outlined.Tune, stringResource(R.string.privacy_control_title), stringResource(R.string.privacy_control_body))
            PolicySection(Icons.Outlined.MailOutline, stringResource(R.string.privacy_contact_title), stringResource(R.string.privacy_contact_body))

            PremiumCard(shape = RoundedCornerShape(16.dp)) {
                Row(
                    modifier = Modifier
                        .clickable { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(PRIVACY_POLICY_URL))) }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconTile(Icons.Outlined.PrivacyTip, HealthPrimary, size = 40)
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.privacy_full), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Text(stringResource(R.string.privacy_full_sub), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(
                        Icons.AutoMirrored.Filled.OpenInNew,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun PolicySection(icon: ImageVector, title: String, body: String) {
    PremiumCard {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconTile(icon, Success, size = 40)
                Spacer(modifier = Modifier.width(14.dp))
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            Spacer(modifier = Modifier.height(10.dp))
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
