package com.owen282000.lifedashboard.screens

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.owen282000.lifedashboard.R
import com.owen282000.lifedashboard.ui.theme.Error
import com.owen282000.lifedashboard.ui.theme.ErrorContainer
import com.owen282000.lifedashboard.ui.theme.OnErrorContainer

/**
 * Shown when the Android keystore could not be opened, so webhook auth headers, HMAC signing
 * secrets and MQTT credentials can neither be read nor saved.
 *
 * The app deliberately does not fall back to plain storage here (see [InMemoryPrefs]), so the
 * user needs to know why a sync may be failing with missing credentials, and why a secret they
 * type now will not stick. The state is normally transient: it resolves after the device has
 * been unlocked once.
 */
@Composable
fun SecretsUnavailableBanner(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = ErrorContainer
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Filled.Warning,
                contentDescription = null,
                tint = Error,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                "Secure storage is unavailable, so saved auth headers, signing secrets and " +
                    "MQTT passwords cannot be read or changed right now. Unlock the device and " +
                    "reopen the app. Nothing is stored unencrypted.",
                style = MaterialTheme.typography.bodyMedium,
                color = OnErrorContainer,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

/**
 * Shown when saved secrets were lost and have to be entered again: the Keystore key that
 * encrypted them is gone (a restore, or a Keystore that stayed broken for a day), or the store
 * of an earlier version could not be read. Unlike [SecretsUnavailableBanner], saving works here,
 * and saving any secret makes it go away; so does [onDismiss], for a user who has entered what
 * they still need.
 */
@Composable
fun SecretsLostBanner(onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = ErrorContainer
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Filled.Warning,
                contentDescription = null,
                tint = Error,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                stringResource(R.string.secrets_lost_banner),
                style = MaterialTheme.typography.bodyMedium,
                color = OnErrorContainer,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onDismiss) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.common_close), tint = OnErrorContainer)
            }
        }
    }
}
