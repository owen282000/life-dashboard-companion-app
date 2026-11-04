package com.owen282000.lifedashboard.ui.ld

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

enum class LDNoticeTone { Success, Warning, Info }

/**
 * A result or a hold in one line: a symbol as well as a colour, so the state does not rest on colour
 * alone. Announced by TalkBack when it appears or changes, since the user is still on the button.
 */
@Composable
fun LDNoticeLine(text: String, tone: LDNoticeTone, modifier: Modifier = Modifier, top: Dp = 8.dp) {
    val colors = LDTheme.colors
    val color = when (tone) {
        LDNoticeTone.Success -> colors.accentText
        LDNoticeTone.Warning -> colors.warning
        LDNoticeTone.Info -> colors.secondary
    }
    val icon = when (tone) {
        LDNoticeTone.Success -> Icons.Outlined.CheckCircle
        LDNoticeTone.Warning -> Icons.Outlined.WarningAmber
        LDNoticeTone.Info -> Icons.Outlined.Info
    }
    Row(
        modifier
            .fillMaxWidth()
            .padding(start = LDTheme.textInset, end = LDTheme.textInset, top = top)
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.padding(top = 2.dp).size(16.dp))
        Text(text, style = LDType.note, color = color)
    }
}
