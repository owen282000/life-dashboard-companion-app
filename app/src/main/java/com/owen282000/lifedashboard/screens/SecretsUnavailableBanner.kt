package com.owen282000.lifedashboard.screens

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
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
