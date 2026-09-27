package com.owen282000.lifedashboard

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BroadcastRateLimitTest {

    @Test
    fun `the first broadcast is accepted`() {
        assertTrue(BroadcastRateLimit.allows(now = 5_000L, lastAcceptedAt = null))
    }

    @Test
    fun `a broadcast within a minute of the last accepted one is ignored`() {
        assertFalse(BroadcastRateLimit.allows(now = 70_000L, lastAcceptedAt = 20_000L))
    }

    @Test
    fun `a broadcast a full minute later is accepted again`() {
        assertTrue(BroadcastRateLimit.allows(now = 80_000L, lastAcceptedAt = 20_000L))
    }
}
