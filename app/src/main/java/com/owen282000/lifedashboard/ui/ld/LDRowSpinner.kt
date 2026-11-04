package com.owen282000.lifedashboard.ui.ld

import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** What LDRow's `trailing` holds while the row is at work. */
@Composable
fun LDRowSpinner(modifier: Modifier = Modifier) {
    CircularProgressIndicator(modifier.size(18.dp), color = LDTheme.colors.accentText, strokeWidth = 2.dp)
}
