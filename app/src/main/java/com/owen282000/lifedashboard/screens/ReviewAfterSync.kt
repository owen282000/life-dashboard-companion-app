package com.owen282000.lifedashboard.screens

import android.content.Context
import android.content.ContextWrapper
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.owen282000.lifedashboard.ReviewMoment
import com.owen282000.lifedashboard.ReviewPrompt
import com.owen282000.lifedashboard.viewmodel.UiMessage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * A sync from the screen that delivered, or a backfill that finished, is a moment to ask for a
 * rating (P1-28, P1-43). [ReviewMoment] decides; outside the Google Play build it does nothing.
 * A message that names a failure, a queue or a partial delivery never asks, and the message is
 * on screen for a moment first.
 */
@Composable
fun ReviewAfterSync(message: UiMessage?) {
    val context = LocalContext.current
    LaunchedEffect(message) {
        val moment = when (message) {
            is UiMessage.BackfillComplete -> ReviewPrompt.Moment.BACKFILL_DONE
            is UiMessage.SyncedRecords, is UiMessage.SyncedRecordsWritten, is UiMessage.SyncedApps -> ReviewPrompt.Moment.SYNC_DELIVERED
            else -> return@LaunchedEffect
        }
        delay(ReviewPrompt.REST_MILLIS)
        context.componentActivity()?.let { ReviewMoment.consider(it, moment) }
    }
}

/**
 * The app in front on a tab, and left alone there for a moment: the one time most people see it
 * after the setup, so a moment to ask when the syncs behind it went well. Leaving the tab or the
 * app within that moment drops the question. [atTabRoot] is false on a page above the tabs,
 * where someone is busy with a setting.
 */
@Composable
fun ReviewWhenOpened(atTabRoot: Boolean) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    LifecycleResumeEffect(atTabRoot) {
        val job = if (atTabRoot) {
            owner.lifecycleScope.launch {
                delay(ReviewPrompt.REST_MILLIS)
                context.componentActivity()?.let { ReviewMoment.consider(it, ReviewPrompt.Moment.OPENED) }
            }
        } else {
            null
        }
        onPauseOrDispose { job?.cancel() }
    }
}

private tailrec fun Context.componentActivity(): ComponentActivity? = when (this) {
    is ComponentActivity -> this
    is ContextWrapper -> baseContext.componentActivity()
    else -> null
}
