package com.owen282000.lifedashboard.screens.ld

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.owen282000.lifedashboard.R
import com.owen282000.lifedashboard.screens.TimePickerDialog
import com.owen282000.lifedashboard.screens.text
import com.owen282000.lifedashboard.ui.ld.LDCard
import com.owen282000.lifedashboard.ui.ld.LDGap
import com.owen282000.lifedashboard.ui.ld.LDHeading
import com.owen282000.lifedashboard.ui.ld.LDNote
import com.owen282000.lifedashboard.ui.ld.LDNoteSize
import com.owen282000.lifedashboard.ui.ld.LDRow
import com.owen282000.lifedashboard.ui.ld.LDScreen
import com.owen282000.lifedashboard.ui.ld.LDTheme
import com.owen282000.lifedashboard.ui.ld.LDToggleRow
import com.owen282000.lifedashboard.ui.ld.LDTopBarAction
import com.owen282000.lifedashboard.ui.ld.ldStaggerRise
import com.owen282000.lifedashboard.ui.ld.rememberLDDraft
import com.owen282000.lifedashboard.viewmodel.UiMessage
import java.time.LocalTime

/**
 * Where a logical day begins. The switch applies at once. The hour waits for Save, because it
 * moves the app's minutes between days at the receiver, and Back discards it.
 */
@Composable
fun ScreenTimeDayScreen() {
    val viewModel = ldScreenTimeViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val resources = LocalResources.current
    var hour by rememberLDDraft { state.saved.dayBoundaryHour }
    var problem by rememberLDDraft<UiMessage?> { null }
    var picking by rememberSaveable { mutableStateOf(false) }
    val useBoundary = state.saved.useDayBoundary

    LDScreen(
        title = stringResource(R.string.main_title_screen_time),
        actions = {
            if (useBoundary) {
                LDTopBarAction(stringResource(R.string.ld_save), enabled = hour != state.saved.dayBoundaryHour) {
                    problem = viewModel.saveDayBoundaryHour(hour)
                }
            }
        }
    ) {
        LDHeading(
            stringResource(
                R.string.ld_screen_day_heading,
                if (useBoundary) screenTimeHourText(state.saved.dayBoundaryHour) else screenTimeHourText("0")
            )
        )
        LDNote(stringResource(R.string.ld_screen_day_body), top = 6.dp)
        LDGap(12.dp)
        LDCard(Modifier.ldStaggerRise(0)) {
            LDToggleRow(stringResource(R.string.screentime_day_boundary_enable), useBoundary, viewModel::setUseDayBoundary)
            if (useBoundary) {
                LDRow(
                    stringResource(R.string.screentime_day_boundary_time_label),
                    value = screenTimeHourText(hour),
                    valueColor = LDTheme.colors.accentTextSoft,
                    onClick = { picking = true }
                )
            }
        }
        problem?.let { LDNote(it.text(resources), color = LDTheme.colors.warning, size = LDNoteSize.Small) }
        if (useBoundary) {
            LDNote(stringResource(R.string.ld_screen_day_whole_hours), size = LDNoteSize.Small)
        }
    }

    if (picking) {
        // The same clock as the sync times. The boundary is a whole hour, so the minutes it offers are dropped.
        TimePickerDialog(
            initial = LocalTime.of(hour.toIntOrNull()?.coerceIn(0, 23) ?: 4, 0),
            onDismiss = { picking = false },
            onPicked = { time ->
                hour = time.hour.toString()
                problem = null
                picking = false
            },
            confirmLabel = stringResource(R.string.common_set)
        )
    }
}
