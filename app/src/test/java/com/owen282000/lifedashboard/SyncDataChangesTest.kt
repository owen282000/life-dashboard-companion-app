package com.owen282000.lifedashboard

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SyncDataChangesTest {

    private val logs = MutableStateFlow(4)
    private val outcomes = MutableStateFlow(9)

    @Test
    fun `what was already there is no change`() = runTest(UnconfinedTestDispatcher()) {
        var fired = 0
        val watching = launch { syncDataChanges(logs, outcomes).collect { fired++ } }
        advanceTimeBy(5_000)
        assertEquals(0, fired)
        watching.cancel()
    }

    @Test
    fun `a sync's rows and outcome together are one read`() = runTest(UnconfinedTestDispatcher()) {
        var fired = 0
        val watching = launch { syncDataChanges(logs, outcomes).collect { fired++ } }
        logs.value = 5
        advanceTimeBy(50)
        logs.value = 6
        advanceTimeBy(50)
        outcomes.value = 10
        advanceTimeBy(5_000)
        assertEquals(1, fired)
        watching.cancel()
    }

    @Test
    fun `a row written alone counts, as a test ping or a drained outbox writes`() = runTest(UnconfinedTestDispatcher()) {
        var fired = 0
        val watching = launch { syncDataChanges(logs, outcomes).collect { fired++ } }
        logs.value = 5
        advanceTimeBy(1_000)
        logs.value = 6
        advanceTimeBy(1_000)
        assertEquals(2, fired)
        watching.cancel()
    }
}
