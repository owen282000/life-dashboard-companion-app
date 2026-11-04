package com.owen282000.lifedashboard

import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map

/** A sync writes its rows and its outcome within moments, and all of that is one thing to read again. */
private const val SETTLE_MILLIS = 300L

/**
 * Fires when the log or the last outcome changed, whoever wrote them. A worker in the background
 * runs in this process and gives the screens no other signal, so they can read again at once
 * instead of finding out at their next poll.
 */
@OptIn(FlowPreview::class)
fun syncDataChanges(
    logs: Flow<Int> = WebhookLogStore.changes,
    outcomes: Flow<Int> = SyncStatusStore.changes
): Flow<Unit> = combine(logs, outcomes) { log, outcome -> log to outcome }
    // The first is what both already were.
    .drop(1)
    .debounce(SETTLE_MILLIS)
    .map { }
