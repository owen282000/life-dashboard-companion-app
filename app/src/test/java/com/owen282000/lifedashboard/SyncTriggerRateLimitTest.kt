package com.owen282000.lifedashboard

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncTriggerRateLimitTest {

    @Test
    fun `the first broadcast is accepted`() {
        assertTrue(SyncTriggerRateLimit.allows(now = 5_000L, lastAcceptedAt = null))
    }

    @Test
    fun `a broadcast within a minute of the last accepted one is ignored`() {
        assertFalse(SyncTriggerRateLimit.allows(now = 70_000L, lastAcceptedAt = 20_000L))
    }

    @Test
    fun `a broadcast a full minute later is accepted again`() {
        assertTrue(SyncTriggerRateLimit.allows(now = 80_000L, lastAcceptedAt = 20_000L))
    }

    @Test
    fun `a tile tap within a minute of an accepted trigger is ignored`() {
        val limit = SyncTriggerRateLimit()
        assertTrue("the broadcast", limit.tryAccept(now = 20_000L))
        assertFalse("the tile, 30 seconds later", limit.tryAccept(now = 50_000L))
        assertFalse("tapped again", limit.tryAccept(now = 79_999L))
    }

    @Test
    fun `an ignored tap does not move the minute along`() {
        val limit = SyncTriggerRateLimit()
        assertTrue(limit.tryAccept(now = 20_000L))
        assertFalse(limit.tryAccept(now = 70_000L))
        // A minute after the accepted one, not after the ignored one.
        assertTrue(limit.tryAccept(now = 80_000L))
        assertFalse(limit.tryAccept(now = 80_001L))
    }
}
