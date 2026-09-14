package com.owen282000.lifedashboard.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.owen282000.lifedashboard.HealthDataType
import com.owen282000.lifedashboard.R
import com.owen282000.lifedashboard.ResolutionFamily
import com.owen282000.lifedashboard.SeriesResolution

/**
 * Resolution per data type: send every record, or one averaged (or summed) value per window.
 *
 * Only the types where a window means something appear, grouped by what bucketing does to
 * them, so it is clear that a heart rate is averaged while steps are added up. Types the user
 * has not enabled are left out entirely; there is nothing to decide about a series that is
 * not being synced.
 */
@Composable
fun ResolutionRow(
    accent: Color,
    enabledTypes: Set<HealthDataType>,
    resolutions: Map<HealthDataType, SeriesResolution>,
    expanded: Boolean,
    onToggle: () -> Unit,
    onChange: (HealthDataType, SeriesResolution) -> Unit
) {
    val configurable = ResolutionFamily.configurableTypes.filter { it in enabledTypes }
    val bucketed = configurable.count { (resolutions[it] ?: SeriesResolution.RAW) != SeriesResolution.RAW }

    ExpandableRow(
        icon = Icons.Outlined.Tune,
        accent = accent,
        title = stringResource(R.string.resolution_title),
        subtitle = if (bucketed == 0) stringResource(R.string.resolution_subtitle_raw)
        else stringResource(R.string.resolution_subtitle_count, bucketed),
        subtitleAccent = bucketed > 0,
        expanded = expanded,
        onToggle = onToggle
    ) {
        Text(
            stringResource(R.string.resolution_explainer),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        ResolutionFamily.entries.forEach { family ->
            val typesInFamily = configurable.filter { ResolutionFamily.of(it) == family }
            if (typesInFamily.isEmpty()) return@forEach

            GroupDivider()
            Text(
                stringResource(
                    when (family) {
                        ResolutionFamily.SAMPLED -> R.string.resolution_group_sampled
                        ResolutionFamily.ACCUMULATED -> R.string.resolution_group_accumulated
                    }
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            typesInFamily.forEach { type ->
                TypeResolution(
                    accent = accent,
                    label = type.displayName,
                    selected = resolutions[type] ?: SeriesResolution.RAW,
                    onSelect = { onChange(type, it) }
                )
            }
        }
    }
}

@Composable
private fun TypeResolution(
    accent: Color,
    label: String,
    selected: SeriesResolution,
    onSelect: (SeriesResolution) -> Unit
) {
    Text(label, style = MaterialTheme.typography.bodyMedium)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        SeriesResolution.entries.forEach { resolution ->
            val isSelected = resolution == selected
            Surface(
                onClick = { onSelect(resolution) },
                modifier = Modifier.weight(if (resolution == SeriesResolution.RAW) 1.7f else 1f),
                shape = RoundedCornerShape(9.dp),
                color = if (isSelected) accent.copy(alpha = 0.18f) else MaterialTheme.colorScheme.surfaceVariant
            ) {
                Box(modifier = Modifier.padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                    Text(
                        stringResource(resolution.labelRes),
                        fontSize = 12.sp,
                        maxLines = 1,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (isSelected) accent else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

private val SeriesResolution.labelRes: Int
    get() = when (this) {
        SeriesResolution.RAW -> R.string.resolution_raw
        SeriesResolution.ONE_MINUTE -> R.string.resolution_1m
        SeriesResolution.FIVE_MINUTES -> R.string.resolution_5m
        SeriesResolution.FIFTEEN_MINUTES -> R.string.resolution_15m
        SeriesResolution.HOURLY -> R.string.resolution_1h
    }
