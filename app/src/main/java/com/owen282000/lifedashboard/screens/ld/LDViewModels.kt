package com.owen282000.lifedashboard.screens.ld

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.owen282000.lifedashboard.ui.ld.LDSection
import com.owen282000.lifedashboard.viewmodel.HealthConnectViewModel
import com.owen282000.lifedashboard.viewmodel.MqttDraft
import com.owen282000.lifedashboard.viewmodel.ScreenTimeViewModel
import com.owen282000.lifedashboard.viewmodel.SectionActions
import com.owen282000.lifedashboard.viewmodel.UiMessage
import com.owen282000.lifedashboard.viewmodel.WebhookDraft

/** The Health section's view model. One per Activity: every screen gets the same one. */
@Composable
fun ldHealthViewModel(): HealthConnectViewModel {
    val context = LocalContext.current
    return viewModel(factory = HealthConnectViewModel.factory(context))
}

/** The Screen Time section's view model. One per Activity: every screen gets the same one. */
@Composable
fun ldScreenTimeViewModel(): ScreenTimeViewModel {
    val context = LocalContext.current
    return viewModel(factory = ScreenTimeViewModel.factory(context))
}

/** What the pages both sections have need of either one, so they do not have to branch on the section. */
data class LDSectionUi(
    val section: LDSection,
    val actions: SectionActions,
    /** As stored. */
    val webhook: WebhookDraft,
    /** As stored. */
    val mqtt: MqttDraft,
    val mqttLastStatus: String?,
    val allowHttpWebhooks: Boolean,
    val isPinging: Boolean,
    val refused: UiMessage?,
    /** Moves on when a sync or a ping wrote to the log. */
    val refreshKey: Int
)

@Composable
fun ldSection(section: LDSection): LDSectionUi = when (section) {
    LDSection.Health -> {
        val viewModel = ldHealthViewModel()
        val state by viewModel.state.collectAsStateWithLifecycle()
        LDSectionUi(section, viewModel, state.saved.webhook, state.saved.mqtt, state.mqttLastStatus, state.allowHttpWebhooks, state.isPinging, state.refused, state.refreshKey)
    }
    LDSection.ScreenTime -> {
        val viewModel = ldScreenTimeViewModel()
        val state by viewModel.state.collectAsStateWithLifecycle()
        LDSectionUi(section, viewModel, state.saved.webhook, state.saved.mqtt, state.mqttLastStatus, state.allowHttpWebhooks, state.isPinging, state.refused, state.refreshKey)
    }
}
