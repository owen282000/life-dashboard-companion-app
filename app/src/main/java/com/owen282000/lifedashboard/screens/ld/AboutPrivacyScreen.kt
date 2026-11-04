package com.owen282000.lifedashboard.screens.ld

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.owen282000.lifedashboard.PRIVACY_POLICY_URL
import com.owen282000.lifedashboard.R
import com.owen282000.lifedashboard.ui.ld.LDButton
import com.owen282000.lifedashboard.ui.ld.LDButtonKind
import com.owen282000.lifedashboard.ui.ld.LDGap
import com.owen282000.lifedashboard.ui.ld.LDHeading
import com.owen282000.lifedashboard.ui.ld.LDNote
import com.owen282000.lifedashboard.ui.ld.LDScreen
import com.owen282000.lifedashboard.ui.ld.LDSectionLabel

@Composable
fun AboutPrivacyScreen() {
    LDScreen(title = stringResource(R.string.about_title)) {
        PrivacyPolicyContent()
    }
}

/** The policy in short, here and in PrivacyPolicyActivity, which is what Health Connect opens. */
@Composable
fun ColumnScope.PrivacyPolicyContent() {
    val context = LocalContext.current
    LDHeading(stringResource(R.string.ld_title_privacy))
    LDNote(stringResource(R.string.privacy_intro), top = 6.dp)

    PolicySection(R.string.privacy_reads_title, R.string.privacy_reads_body)
    PolicySection(R.string.privacy_writes_title, R.string.privacy_writes_body)
    PolicySection(R.string.privacy_where_title, R.string.privacy_where_body)
    PolicySection(R.string.privacy_device_title, R.string.privacy_device_body)
    PolicySection(R.string.privacy_control_title, R.string.privacy_control_body)
    PolicySection(R.string.privacy_contact_title, R.string.privacy_contact_body)

    LDGap(16.dp)
    LDButton(stringResource(R.string.privacy_full), kind = LDButtonKind.Ghost) { context.openLink(PRIVACY_POLICY_URL) }
}

@Composable
private fun PolicySection(@StringRes title: Int, @StringRes body: Int) {
    LDSectionLabel(stringResource(title))
    LDNote(stringResource(body), top = 0.dp)
}
