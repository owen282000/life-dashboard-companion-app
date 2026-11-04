package com.owen282000.lifedashboard.screens.ld

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.permission.HealthPermission
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.owen282000.lifedashboard.HealthDataType
import com.owen282000.lifedashboard.R
import com.owen282000.lifedashboard.ui.ld.LDCard
import com.owen282000.lifedashboard.ui.ld.LDGap
import com.owen282000.lifedashboard.ui.ld.LDHeading
import com.owen282000.lifedashboard.ui.ld.LDNote
import com.owen282000.lifedashboard.ui.ld.LDNoteSize
import com.owen282000.lifedashboard.ui.ld.LDScreen
import com.owen282000.lifedashboard.ui.ld.LDToggleRow
import com.owen282000.lifedashboard.ui.ld.LDTypeCategory
import com.owen282000.lifedashboard.ui.ld.ldStaggerRise

/** The types of one category. Switching on a type without its permission asks for it first. */
@Composable
fun TypeCategoryScreen(category: LDTypeCategory) {
    val viewModel = ldHealthViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val enabled = state.saved.enabledTypes
    val types = category.types
    val isLocked = { type: HealthDataType -> HealthPermission.getReadPermission(type.recordClass) !in state.grantedPermissions }
    LDScreen(title = stringResource(R.string.ld_title_what_to_send)) {
        LDHeading(stringResource(category.title))
        LDNote(
            if (category == LDTypeCategory.Cycle) stringResource(R.string.ld_category_cycle_note)
            else stringResource(R.string.health_data_types_selected_of, types.count { it in enabled }, types.size),
            top = 4.dp
        )
        LDAccessBlockedLine(state)
        LDGap(12.dp)
        LDCard(Modifier.ldStaggerRise(0)) {
            types.forEach { type ->
                LDToggleRow(
                    stringResource(type.displayNameRes),
                    checked = type in enabled,
                    onCheckedChange = { viewModel.toggleType(type, it) },
                    locked = isLocked(type)
                )
            }
        }
        if (types.any(isLocked)) {
            LDNote(stringResource(R.string.ld_category_lock_note), size = LDNoteSize.Small)
        }
    }
}
