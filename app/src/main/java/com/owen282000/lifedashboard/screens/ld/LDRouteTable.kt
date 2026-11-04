package com.owen282000.lifedashboard.screens.ld

import androidx.compose.runtime.Composable
import com.owen282000.lifedashboard.ui.ld.LDRoute

/** Every route to its screen, once. Each screen has a file of its own in this folder. */
@Composable
fun LDRouteContent(route: LDRoute) {
    when (route) {
        LDRoute.Home -> HomeScreen()
        LDRoute.ScreenTimeHome -> ScreenTimeHomeScreen()
        LDRoute.LogsList -> LogsListScreen()
        is LDRoute.AddDestination -> AddDestinationScreen(route.section)
        is LDRoute.WebhookDestination -> WebhookDestinationScreen(route.section, route.destinationId, route.address)
        is LDRoute.MqttDestination -> MqttDestinationScreen(route.section)
        LDRoute.PairedResult -> PairedResultScreen()
        LDRoute.WhatToSend -> WhatToSendScreen()
        is LDRoute.TypeCategory -> TypeCategoryScreen(route.category)
        LDRoute.DataResolution -> DataResolutionScreen()
        is LDRoute.Backfill -> BackfillScreen(route.stage)
        is LDRoute.PayloadPreview -> PayloadPreviewScreen(route.section)
        LDRoute.WhenToSend -> WhenToSendScreen()
        LDRoute.Receive -> ReceiveScreen()
        LDRoute.More -> MoreScreen()
        is LDRoute.Advanced -> AdvancedScreen(route.section)
        LDRoute.ServerCertificates -> ServerCertificatesScreen()
        LDRoute.NotificationSettings -> NotificationSettingsScreen()
        is LDRoute.ExportData -> ExportDataScreen(route.section)
        LDRoute.BackupRestore -> BackupRestoreScreen()
        is LDRoute.LogDetail -> LogDetailScreen(route.logId)
        LDRoute.ScreenTimeApps -> ScreenTimeAppsScreen()
        LDRoute.ScreenTimeDay -> ScreenTimeDayScreen()
        LDRoute.ScreenTimeWhen -> ScreenTimeWhenScreen()
        LDRoute.ScreenTimeMore -> ScreenTimeMoreScreen()
        LDRoute.AboutHome -> AboutHomeScreen()
        LDRoute.AboutHelp -> AboutHelpScreen()
        LDRoute.AboutSupport -> AboutSupportScreen()
        LDRoute.AboutPrivacy -> AboutPrivacyScreen()
        LDRoute.AboutOpenSource -> AboutOpenSourceScreen()
        LDRoute.AboutNerd -> AboutNerdScreen()
    }
}
