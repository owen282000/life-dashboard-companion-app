package com.owen282000.lifedashboard

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The backfill takes the sync lock through this; a sync already running makes it wait, not fail. */
class MutexWaitTest {

    @Test
    fun `a free lock runs at once and reports no wait`() = runTest {
        val mutex = Mutex()
        val reported = mutableListOf<Boolean>()
        val result = mutex.withLockReportingWait({ reported += it }) { "ran" }
        assertEquals("ran", result)
        assertTrue(reported.isEmpty())
        assertFalse(mutex.isLocked)
    }

    @Test
    fun `a held lock is waited for, said so, and then run`() = runTest {
        val mutex = Mutex()
        val syncMayEnd = CompletableDeferred<Unit>()
        val sync = launch(start = CoroutineStart.UNDISPATCHED) { mutex.withLock { syncMayEnd.await() } }

        val reported = mutableListOf<Boolean>()
        var ranWhileSyncHeldIt = false
        val backfill = async(start = CoroutineStart.UNDISPATCHED) {
            mutex.withLockReportingWait({ reported += it }) { ranWhileSyncHeldIt = !sync.isCompleted }
        }
        assertEquals(listOf(true), reported)
        assertFalse(backfill.isCompleted)

        syncMayEnd.complete(Unit)
        backfill.await()
        assertEquals(listOf(true, false), reported)
        assertFalse(ranWhileSyncHeldIt)
        assertFalse(mutex.isLocked)
    }

    @Test
    fun `the lock is released when the work fails`() = runTest {
        val mutex = Mutex()
        runCatching { mutex.withLockReportingWait({}) { error("boom") } }
        assertFalse(mutex.isLocked)
    }
}
