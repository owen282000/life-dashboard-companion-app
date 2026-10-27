package com.owen282000.lifedashboard.screens

import android.content.Context
import android.content.ContextWrapper
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import com.owen282000.lifedashboard.ReviewMoment
import com.owen282000.lifedashboard.viewmodel.UiMessage

/**
 * A sync from the screen that delivered, or a backfill that finished, is a moment to ask for a
 * rating (P1-28). [ReviewMoment] decides; outside the Google Play build it does nothing. A
 * message that names a failure, a queue or a partial delivery never asks.
 */
@Composable
fun ReviewAfterSync(message: UiMessage?) {
    val context = LocalContext.current
    LaunchedEffect(message) {
        val backfillDone = message is UiMessage.BackfillComplete
        val delivered = message is UiMessage.SyncedRecords || message is UiMessage.SyncedRecordsWritten || message is UiMessage.SyncedApps
        if (!backfillDone && !delivered) return@LaunchedEffect
        context.componentActivity()?.let { ReviewMoment.consider(it, backfillDone) }
    }
}

private tailrec fun Context.componentActivity(): ComponentActivity? = when (this) {
    is ComponentActivity -> this
    is ContextWrapper -> baseContext.componentActivity()
    else -> null
}
