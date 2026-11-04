package com.owen282000.lifedashboard.screens.ld

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.owen282000.lifedashboard.R
import com.owen282000.lifedashboard.ui.ld.LDBanner
import com.owen282000.lifedashboard.ui.ld.LDBannerAction

/**
 * The state of the secure storage, where it can be seen. [unavailable] is the Keystore that cannot be
 * opened: the app does not fall back to plain storage, so a sync can fail for missing credentials
 * and a secret typed now will not stick, and that normally ends once the device was unlocked.
 * [needReentry] is saved secrets that are gone for good; saving any secret ends it, as does
 * [onDismissLost] for someone who has entered what they still need.
 */
@Composable
fun LDSecretsBanners(unavailable: Boolean, needReentry: Boolean, onDismissLost: () -> Unit) {
    if (unavailable) {
        LDBanner(stringResource(R.string.ld_secrets_unavailable_title), message = stringResource(R.string.ld_secrets_unavailable_body))
    }
    if (needReentry) {
        LDBanner(stringResource(R.string.ld_secrets_lost_title), message = stringResource(R.string.secrets_lost_banner)) {
            LDBannerAction(stringResource(R.string.common_close), onClick = onDismissLost)
        }
    }
}
