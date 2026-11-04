package com.owen282000.lifedashboard.screens.ld

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import com.owen282000.lifedashboard.screens.text
import com.owen282000.lifedashboard.ui.ld.LDNoticeLine
import com.owen282000.lifedashboard.ui.ld.LDNoticeTone
import com.owen282000.lifedashboard.viewmodel.UiMessage

/** What a line about a sync says with its colour and symbol, so the text does not have to decide it. */
fun UiMessage.noticeTone(): LDNoticeTone = when {
    isFailure -> LDNoticeTone.Warning
    this is UiMessage.SyncedRecords || this is UiMessage.SyncedRecordsWritten || this is UiMessage.SyncedApps ||
        this is UiMessage.BackfillComplete || this is UiMessage.PingDelivered -> LDNoticeTone.Success
    else -> LDNoticeTone.Info
}

/** The outcome of the last sync, under Sync Now. Read out by TalkBack when it changes. */
@Composable
fun LDSyncMessageLine(message: UiMessage?, modifier: Modifier = Modifier) {
    if (message == null) return
    LDNoticeLine(message.text(LocalResources.current), message.noticeTone(), modifier)
}
