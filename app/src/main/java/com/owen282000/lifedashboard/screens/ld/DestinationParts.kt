package com.owen282000.lifedashboard.screens.ld

import android.text.format.DateUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.owen282000.lifedashboard.R
import com.owen282000.lifedashboard.ui.ld.LDSection
import com.owen282000.lifedashboard.ui.ld.LDTheme
import com.owen282000.lifedashboard.ui.ld.LDType

/*
 * What the Add, Webhook and MQTT pages share: they run on both tabs, through `section`.
 */

/**
 * Whether the view model refused the last write, read at once. A removal runs on this thread and
 * its state is set by the time it returns, while the page's own `state` only shows it next frame.
 */
@Composable
internal fun rememberRefusedNow(section: LDSection): () -> Boolean {
    val health = ldHealthViewModel()
    val screenTime = ldScreenTimeViewModel()
    return { if (section == LDSection.Health) health.state.value.refused != null else screenTime.state.value.refused != null }
}

/** The Receive source of the Health section; Screen Time has none. */
@Composable
internal fun receiveSource(section: LDSection): String? {
    if (section != LDSection.Health) return null
    val state by ldHealthViewModel().state.collectAsStateWithLifecycle()
    return state.receive.sourceUrl
}

/** The eye in a secret's field. It starts hidden, and hides again when the page goes. */
@Composable
internal fun RevealButton(revealed: Boolean, onToggle: () -> Unit) {
    val label = stringResource(if (revealed) R.string.common_hide_password else R.string.common_show_password)
    Box(
        Modifier
            .size(48.dp)
            .clickable(role = Role.Button, onClick = onToggle)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            if (revealed) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
            contentDescription = null,
            tint = LDTheme.colors.muted,
            modifier = Modifier.size(20.dp)
        )
    }
}

/** The line that says what is wrong, as a row of its card. */
@Composable
internal fun CardRowError(text: String) {
    Text(
        text,
        style = LDType.rowSubtitle,
        color = LDTheme.colors.warning,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 8.dp)
            .semantics { liveRegion = LiveRegionMode.Polite }
    )
}

/** "4 minutes ago", or the words for under a minute, in the phone's language. */
@Composable
internal fun timeAgo(timestamp: Long, now: Long = System.currentTimeMillis()): String =
    if (now - timestamp < DateUtils.MINUTE_IN_MILLIS) {
        stringResource(R.string.ld_just_now)
    } else {
        DateUtils.getRelativeTimeSpanString(timestamp, now, DateUtils.MINUTE_IN_MILLIS).toString()
    }

/** Removing goes through this on both pages: the confirm says what goes with it. */
@Composable
internal fun RemoveDestinationDialog(message: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.ld_dest_remove)) },
        text = { Text(message) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.common_remove)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } }
    )
}
