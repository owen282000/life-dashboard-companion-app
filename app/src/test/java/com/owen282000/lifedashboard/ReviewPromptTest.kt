package com.owen282000.lifedashboard

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When the Google Play build asks for a rating (P1-28): the same rule as the iPhone app's
 * ReviewPrompt. Only after a clean delivery or a finished backfill, after three days of use,
 * and once per version.
 */
class ReviewPromptTest {

    private val day = 24L * 60 * 60 * 1000
    private val now = 1_792_000_000_000L
    private val longAgo = now - 4 * day

    private fun good() = ReviewPrompt.State(
        lastSyncMillis = now - 60 * 60 * 1000,
        lastSyncSucceeded = true,
        failureStreak = 0,
        partialStreak = 0,
        pending = 0
    )

    @Test
    fun aRecentSyncDeliveredToEveryAddressIsAGoodMoment() {
        assertTrue(ReviewPrompt.isGoodMoment(good(), now))
    }

    @Test
    fun aFailedLastSyncIsNot() {
        assertFalse(ReviewPrompt.isGoodMoment(good().copy(lastSyncSucceeded = false), now))
    }

    @Test
    fun failingSyncsAreNot() {
        assertFalse(ReviewPrompt.isGoodMoment(good().copy(failureStreak = 1), now))
    }

    @Test
    fun anAddressThatMissesPayloadsIsNot() {
        assertFalse(ReviewPrompt.isGoodMoment(good().copy(partialStreak = 2), now))
    }

    @Test
    fun payloadsWaitingInTheOutboxAreNot() {
        assertFalse(ReviewPrompt.isGoodMoment(good().copy(pending = 3), now))
    }

    @Test
    fun nothingDeliveredWithinADayIsNot() {
        assertTrue(ReviewPrompt.isGoodMoment(good().copy(lastSyncMillis = now - day), now))
        assertFalse(ReviewPrompt.isGoodMoment(good().copy(lastSyncMillis = now - 2 * day), now))
        assertFalse(ReviewPrompt.isGoodMoment(good().copy(lastSyncMillis = null), now))
    }

    @Test
    fun aGoodMomentAfterThreeDaysAsks() {
        assertTrue(ReviewPrompt.shouldAsk(goodMoment = true, firstUse = longAgo, askedVersion = null, version = "1.29.0", now = now))
        assertTrue(ReviewPrompt.shouldAsk(goodMoment = true, firstUse = now - 3 * day, askedVersion = null, version = "1.29.0", now = now))
    }

    @Test
    fun anythingButAGoodMomentDoesNot() {
        assertFalse(ReviewPrompt.shouldAsk(goodMoment = false, firstUse = longAgo, askedVersion = null, version = "1.29.0", now = now))
    }

    @Test
    fun theFirstThreeDaysDoNot() {
        assertFalse(ReviewPrompt.shouldAsk(goodMoment = true, firstUse = now - day, askedVersion = null, version = "1.29.0", now = now))
        assertFalse(ReviewPrompt.shouldAsk(goodMoment = true, firstUse = now - 3 * day + 1, askedVersion = null, version = "1.29.0", now = now))
        assertFalse(ReviewPrompt.shouldAsk(goodMoment = true, firstUse = null, askedVersion = null, version = "1.29.0", now = now))
    }

    @Test
    fun onceForEachVersion() {
        assertFalse(ReviewPrompt.shouldAsk(goodMoment = true, firstUse = longAgo, askedVersion = "1.29.0", version = "1.29.0", now = now))
        assertTrue(ReviewPrompt.shouldAsk(goodMoment = true, firstUse = longAgo, askedVersion = "1.29.0", version = "1.30.0", now = now))
    }
}
