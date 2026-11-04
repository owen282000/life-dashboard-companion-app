package com.owen282000.lifedashboard.screens.ld

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.owen282000.lifedashboard.HealthDataType
import com.owen282000.lifedashboard.R
import com.owen282000.lifedashboard.ResolutionFamily
import com.owen282000.lifedashboard.SeriesResolution
import com.owen282000.lifedashboard.ui.ld.LDCard
import com.owen282000.lifedashboard.ui.ld.LDHeading
import com.owen282000.lifedashboard.ui.ld.LDIcons
import com.owen282000.lifedashboard.ui.ld.LDNote
import com.owen282000.lifedashboard.ui.ld.LDNoteSize
import com.owen282000.lifedashboard.ui.ld.LDRow
import com.owen282000.lifedashboard.ui.ld.LDScreen
import com.owen282000.lifedashboard.ui.ld.LDSectionLabel
import com.owen282000.lifedashboard.ui.ld.LDTheme
import com.owen282000.lifedashboard.ui.ld.LDType
import com.owen282000.lifedashboard.ui.ld.ldStaggerRise

/**
 * Resolution per type: every record, or one averaged (or summed) value per window. Only the types
 * where a window means something appear, and only the ones switched on, grouped by what a window
 * does to them.
 */
@Composable
fun DataResolutionScreen() {
    val viewModel = ldHealthViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val configurable = ResolutionFamily.configurableTypes.filter { it in state.saved.enabledTypes }
    LDScreen(title = stringResource(R.string.ld_title_what_to_send)) {
        LDHeading(stringResource(R.string.resolution_title))
        LDNote(stringResource(R.string.resolution_explainer), top = 6.dp)
        ResolutionFamily.entries.forEachIndexed { index, family ->
            val types = configurable.filter { ResolutionFamily.of(it) == family }
            if (types.isNotEmpty()) {
                LDSectionLabel(
                    stringResource(
                        when (family) {
                            ResolutionFamily.SAMPLED -> R.string.resolution_group_sampled
                            ResolutionFamily.ACCUMULATED -> R.string.resolution_group_accumulated
                        }
                    )
                )
                LDCard(Modifier.ldStaggerRise(index)) {
                    types.forEach { type ->
                        ResolutionRow(
                            type,
                            selected = state.saved.resolutions[type] ?: SeriesResolution.RAW,
                            onSelect = { viewModel.setResolution(type, it) }
                        )
                    }
                }
            }
        }
        if (configurable.isEmpty()) {
            LDNote(stringResource(R.string.ld_resolution_empty), top = 14.dp)
        } else {
            LDNote(stringResource(R.string.ld_resolution_footnote), size = LDNoteSize.Small)
        }
    }
}

@Composable
private fun ResolutionRow(type: HealthDataType, selected: SeriesResolution, onSelect: (SeriesResolution) -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    Box {
        LDRow(
            stringResource(type.displayNameRes),
            value = stringResource(selected.labelRes),
            valueColor = if (selected == SeriesResolution.RAW) LDTheme.colors.muted else LDTheme.colors.accentTextSoft,
            onClick = { open = true }
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            SeriesResolution.entries.forEach { resolution ->
                val isSelected = resolution == selected
                DropdownMenuItem(
                    text = { Text(stringResource(resolution.labelRes), style = LDType.row) },
                    onClick = {
                        open = false
                        onSelect(resolution)
                    },
                    modifier = Modifier.semantics { this.selected = isSelected },
                    trailingIcon = if (isSelected) {
                        { Icon(LDIcons.check, contentDescription = null, tint = LDTheme.colors.accent, modifier = Modifier.size(18.dp)) }
                    } else {
                        null
                    }
                )
            }
        }
    }
}

@get:StringRes
private val SeriesResolution.labelRes: Int
    get() = when (this) {
        SeriesResolution.RAW -> R.string.resolution_raw
        SeriesResolution.ONE_MINUTE -> R.string.resolution_1m
        SeriesResolution.FIVE_MINUTES -> R.string.resolution_5m
        SeriesResolution.FIFTEEN_MINUTES -> R.string.resolution_15m
        SeriesResolution.HOURLY -> R.string.resolution_1h
    }
