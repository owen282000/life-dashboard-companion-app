package com.owen282000.lifedashboard.screens

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.owen282000.lifedashboard.AppChoice
import com.owen282000.lifedashboard.AppFilterMode
import com.owen282000.lifedashboard.R
import com.owen282000.lifedashboard.ScreenTimeAppFilter

/** Above this many apps the picker gets a search field. */
private const val SEARCH_FROM = 8

/**
 * Which apps Screen Time sends (issue #63): every app, every app but a list, or only a list.
 * The apps on offer are the ones the usage statistics saw over the last month, plus any already
 * on the list, since the app may not look at the phone's other apps (ScreenTimeManager).
 */
@Composable
fun AppFilterRow(
    accent: Color,
    filter: ScreenTimeAppFilter,
    choices: List<AppChoice>?,
    expanded: Boolean,
    onToggle: () -> Unit,
    onModeChange: (AppFilterMode) -> Unit,
    onToggleApp: (String) -> Unit,
    onLoadChoices: () -> Unit
) {
    val count = filter.packages.size
    val subtitle = when (filter.mode) {
        AppFilterMode.ALL -> stringResource(R.string.screentime_apps_all)
        AppFilterMode.BLOCKLIST -> pluralStringResource(R.plurals.screentime_apps_all_except, count, count)
        AppFilterMode.ALLOWLIST -> pluralStringResource(R.plurals.screentime_apps_only, count, count)
    }
    LaunchedEffect(expanded) { if (expanded) onLoadChoices() }

    ExpandableRow(
        icon = Icons.Outlined.Apps,
        accent = accent,
        title = stringResource(R.string.screentime_apps_title),
        subtitle = subtitle,
        subtitleAccent = filter.active,
        expanded = expanded,
        onToggle = onToggle
    ) {
        val modes = AppFilterMode.entries
        SegmentedFilter(
            options = listOf(
                stringResource(R.string.screentime_apps_mode_all),
                stringResource(R.string.screentime_apps_mode_except),
                stringResource(R.string.screentime_apps_mode_only)
            ),
            selectedIndex = modes.indexOf(filter.mode),
            onSelect = { onModeChange(modes[it]) }
        )
        Text(
            stringResource(
                when (filter.mode) {
                    AppFilterMode.ALL -> R.string.screentime_apps_description_all
                    AppFilterMode.BLOCKLIST -> R.string.screentime_apps_description_except
                    AppFilterMode.ALLOWLIST -> R.string.screentime_apps_description_only
                }
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (filter.active) {
            Text(
                stringResource(R.string.screentime_apps_history_warning),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (filter.mode == AppFilterMode.ALLOWLIST && filter.packages.isEmpty()) {
                Text(
                    stringResource(R.string.screentime_apps_none_chosen),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.announced()
                )
            }
            AppPicker(accent, filter, choices, onToggleApp)
        }
    }
}

@Composable
private fun AppPicker(accent: Color, filter: ScreenTimeAppFilter, choices: List<AppChoice>?, onToggleApp: (String) -> Unit) {
    if (choices == null) {
        Text(stringResource(R.string.screentime_apps_loading), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    if (choices.isEmpty()) {
        Text(stringResource(R.string.screentime_apps_none_used), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    var query by rememberSaveable { mutableStateOf("") }
    if (choices.size > SEARCH_FROM) {
        FilledField(value = query, onValueChange = { query = it }, accent = accent, placeholder = stringResource(R.string.screentime_apps_search))
    }
    val shown = choices.filter {
        query.isBlank() || it.name.contains(query.trim(), ignoreCase = true) || it.packageName.contains(query.trim(), ignoreCase = true)
    }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        shown.forEach { choice -> AppLine(accent, choice, choice.packageName in filter.packages) { onToggleApp(choice.packageName) } }
    }
}

/** One app with a checkbox; the whole line is the control, so TalkBack reads "<app>, checked". */
@Composable
private fun AppLine(accent: Color, choice: AppChoice, checked: Boolean, onToggle: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = checked, interactionSource = interaction, indication = null, role = Role.Checkbox, onValueChange = { onToggle() })
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(choice.name, style = MaterialTheme.typography.bodyMedium)
            Text(
                if (choice.minutes > 0) pluralStringResource(R.plurals.screentime_apps_minutes_month, choice.minutes.toInt(), choice.minutes.toInt())
                else stringResource(R.string.screentime_apps_not_used),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Checkbox(checked = checked, onCheckedChange = null, interactionSource = interaction, colors = CheckboxDefaults.colors(checkedColor = accent))
    }
}
