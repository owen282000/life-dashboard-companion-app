package com.owen282000.lifedashboard.screens

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.security.KeyChain
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.owen282000.lifedashboard.R

/**
 * Picks the client certificate (mTLS) presented to webhooks, from the Android credential store.
 *
 * The system picker only runs from here. The choice is remembered, and Android keeps this app's
 * access to the certificate, so background syncs use it without asking again.
 */
@Composable
fun ClientCertLine(
    alias: String?,
    /** A webhook URL, so the picker can suggest the certificate installed for that server. */
    webhookUrl: String?,
    accent: Color,
    onAliasChange: (String?) -> Unit
) {
    val context = LocalContext.current
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.client_cert_title), style = MaterialTheme.typography.bodyMedium)
            Text(
                alias ?: stringResource(R.string.client_cert_none),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                stringResource(R.string.client_cert_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (alias != null) {
            TextButton(onClick = { onAliasChange(null) }) {
                Text(stringResource(R.string.client_cert_clear), color = accent)
            }
        }
        TextButton(onClick = {
            val activity = context.findActivity() ?: return@TextButton
            val uri = webhookUrl?.let { Uri.parse(it) }
            val main = Handler(Looper.getMainLooper())
            KeyChain.choosePrivateKeyAlias(
                activity,
                // Called on a binder thread; null means the picker was dismissed, keep the current one.
                { chosen -> if (chosen != null) main.post { onAliasChange(chosen) } },
                arrayOf("RSA", "EC"),
                null,
                uri?.host,
                uri?.port ?: -1,
                alias
            )
        }) {
            Text(stringResource(R.string.client_cert_choose), color = accent)
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
