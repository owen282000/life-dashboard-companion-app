package com.owen282000.lifedashboard

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.ui.res.stringResource
import com.owen282000.lifedashboard.screens.ld.PrivacyPolicyContent
import com.owen282000.lifedashboard.ui.ld.LDScreen
import com.owen282000.lifedashboard.ui.ld.LDTheme
import com.owen282000.lifedashboard.ui.theme.LifeDashboardTheme

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
            // LifeDashboardTheme for the system bars' icons, as in MainActivity.
            LifeDashboardTheme {
                LDTheme {
                    LDScreen(title = stringResource(R.string.privacy_title), onBack = { finish() }) {
                        PrivacyPolicyContent()
                    }
                }
            }
        }
    }
}
