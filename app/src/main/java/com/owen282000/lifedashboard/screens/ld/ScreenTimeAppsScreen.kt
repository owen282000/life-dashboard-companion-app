package com.owen282000.lifedashboard.screens.ld

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.owen282000.lifedashboard.AppChoice
import com.owen282000.lifedashboard.AppFilterMode
import com.owen282000.lifedashboard.R
import com.owen282000.lifedashboard.ui.ld.LDBanner
import com.owen282000.lifedashboard.ui.ld.LDBannerAction
import com.owen282000.lifedashboard.ui.ld.LDCard
import com.owen282000.lifedashboard.ui.ld.LDFieldRow
import com.owen282000.lifedashboard.ui.ld.LDGap
import com.owen282000.lifedashboard.ui.ld.LDHeading
import com.owen282000.lifedashboard.ui.ld.LDIcons
import com.owen282000.lifedashboard.ui.ld.LDNote
import com.owen282000.lifedashboard.ui.ld.LDNoteSize
import com.owen282000.lifedashboard.ui.ld.LDScreen
import com.owen282000.lifedashboard.ui.ld.LDSectionLabel
import com.owen282000.lifedashboard.ui.ld.LDSegment
import com.owen282000.lifedashboard.ui.ld.LDSegmented
import com.owen282000.lifedashboard.ui.ld.LDTheme
import com.owen282000.lifedashboard.ui.ld.LDType
import com.owen282000.lifedashboard.ui.ld.ldStaggerRise

/** Above this many apps the list gets a search field. */
private const val SEARCH_FROM = 8

/** Which apps Screen Time sends. Every tap is stored at once, and the warning says what that does at the receiver. */
@Composable
fun ScreenTimeAppsScreen() {
    val viewModel = ldScreenTimeViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val filter = state.saved.appFilter

    // The list comes from the usage statistics and is read once, so not before they can be read.
    LaunchedEffect(state.hasUsageAccess) {
        if (state.hasUsageAccess) viewModel.loadAppChoices()
    }

    LDScreen(title = stringResource(R.string.main_title_screen_time)) {
        LDHeading(stringResource(R.string.screentime_apps_title))
        LDGap(12.dp)
        LDSegmented(
            filter.mode,
            listOf(
                LDSegment(AppFilterMode.ALL, stringResource(R.string.screentime_apps_mode_all)),
                LDSegment(AppFilterMode.BLOCKLIST, stringResource(R.string.screentime_apps_mode_except)),
                LDSegment(AppFilterMode.ALLOWLIST, stringResource(R.string.screentime_apps_mode_only))
            ),
            onSelect = viewModel::setAppFilterMode,
            label = stringResource(R.string.screentime_apps_title)
        )
        LDNote(
            stringResource(
                when (filter.mode) {
                    AppFilterMode.ALL -> R.string.ld_screen_apps_description_all
                    AppFilterMode.BLOCKLIST -> R.string.ld_screen_apps_description_except
                    AppFilterMode.ALLOWLIST -> R.string.ld_screen_apps_description_only
                }
            ),
            top = 10.dp
        )
        LDNote(stringResource(R.string.ld_screen_apps_history_warning), color = LDTheme.colors.warning, size = LDNoteSize.Small)
        if (filter.active) {
            if (filter.mode == AppFilterMode.ALLOWLIST && filter.packages.isEmpty()) {
                LDBanner(
                    stringResource(R.string.screentime_apps_none_chosen),
                    Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                )
            }
            LDGap(10.dp)
            ScreenTimeAppList(state.appChoices, filter.packages, state.hasUsageAccess, viewModel::requestUsageAccess, viewModel::toggleFilteredApp)
        }
    }
}

@Composable
private fun ScreenTimeAppList(
    choices: List<AppChoice>?,
    ticked: Set<String>,
    hasUsageAccess: Boolean,
    onAllow: () -> Unit,
    onToggle: (String) -> Unit
) {
    if (choices == null) {
        if (hasUsageAccess) {
            LDNote(stringResource(R.string.screentime_apps_loading), top = 0.dp)
        } else {
            LDBanner(stringResource(R.string.screentime_access_needed)) {
                LDBannerAction(stringResource(R.string.screentime_allow), onClick = onAllow)
            }
        }
        return
    }
    if (choices.isEmpty()) {
        LDNote(stringResource(R.string.screentime_apps_none_used), top = 0.dp)
        return
    }
    var query by rememberSaveable { mutableStateOf("") }
    if (choices.size > SEARCH_FROM) {
        LDCard {
            LDFieldRow(
                stringResource(R.string.ld_screen_apps_search_label),
                query,
                onValueChange = { query = it },
                placeholder = stringResource(R.string.screentime_apps_search),
                mono = false,
                isCode = false
            )
        }
    }
    val wanted = query.trim()
    val shown = choices.filter {
        wanted.isEmpty() || it.name.contains(wanted, ignoreCase = true) || it.packageName.contains(wanted, ignoreCase = true)
    }
    LDSectionLabel(stringResource(R.string.ld_screen_apps_used_label))
    if (shown.isEmpty()) {
        LDNote(stringResource(R.string.ld_screen_apps_no_match), top = 0.dp)
        return
    }
    LDCard(Modifier.ldStaggerRise(0)) {
        shown.forEach { choice ->
            ScreenTimeAppCheckRow(choice, choice.packageName in ticked) { onToggle(choice.packageName) }
        }
    }
}

/** One app with its box and minutes; the whole row is the control, so TalkBack reads "<app>, checked". */
@Composable
private fun ScreenTimeAppCheckRow(choice: AppChoice, checked: Boolean, onToggle: () -> Unit) {
    val colors = LDTheme.colors
    val box = RoundedCornerShape(7.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Checkbox, onValueChange = { onToggle() })
            .heightIn(min = 50.dp)
            .padding(horizontal = 14.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(24.dp)
                .clip(box)
                .background(if (checked) colors.accent else Color.Transparent)
                .border(2.dp, if (checked) colors.accent else colors.optionOff, box),
            contentAlignment = Alignment.Center
        ) {
            if (checked) Icon(LDIcons.check, contentDescription = null, tint = colors.onAccent, modifier = Modifier.size(16.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(choice.name, style = LDType.row, color = colors.text)
            if (choice.minutes <= 0) {
                Text(stringResource(R.string.screentime_apps_not_used), style = LDType.rowSubtitle, color = colors.muted)
            }
        }
        if (choice.minutes > 0) {
            Text(screenTimeDuration(choice.minutes), style = LDType.rowSubtitle, color = colors.muted)
        }
    }
}
