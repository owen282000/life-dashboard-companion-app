package com.owen282000.lifedashboard

import kotlinx.coroutines.sync.Mutex

/**
 * [kotlinx.coroutines.sync.withLock], telling [onWaiting] when the caller has to wait: true
 * when the lock is held elsewhere, false once it is this caller's. A free lock says nothing,
 * so a screen only shows "waiting" when there is something to wait for.
 */
internal suspend fun <T> Mutex.withLockReportingWait(onWaiting: (Boolean) -> Unit, action: suspend () -> T): T {
    if (!tryLock()) {
        onWaiting(true)
        lock()
        onWaiting(false)
    }
    try {
        return action()
    } finally {
        unlock()
    }
}
