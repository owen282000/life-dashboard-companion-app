package com.owen282000.lifedashboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Retention rules for webhook logs (P0-2). Logs used to be one JSON string in the main
 * SharedPreferences file, capped only by entry count, so 100 entries holding raw payloads could
 * approach tens of megabytes rewritten on every delivery. The byte cap is what prevents that.
 */
class WebhookLogStoreTest {

    private val cap = WebhookLogStore.MAX_PAYLOAD_BYTES
    private val maxEntries = WebhookLogStore.MAX_ENTRIES

    @Test
    fun keepsEverythingWhenWellUnderBothCaps() {
        val entries = List(10) { 1_000L }
        assertEquals(10, WebhookLogStore.entriesToKeep(entries) { it })
    }

    @Test
    fun capsOnEntryCount() {
        val entries = List(maxEntries + 50) { 1L }
        assertEquals(maxEntries, WebhookLogStore.entriesToKeep(entries) { it })
    }

    @Test
    fun capsOnTotalBytesBeforeEntryCount() {
        // 20 payloads of 1 MB each: far under the entry cap, far over the byte cap.
        val oneMb = 1024L * 1024
        val entries = List(20) { oneMb }
        val kept = WebhookLogStore.entriesToKeep(entries) { it }

        assertTrue("should drop entries to stay under the byte cap", kept < 20)
        assertTrue("kept payloads must fit the cap", kept * oneMb <= cap)
    }

    @Test
    fun alwaysKeepsAtLeastOneEntryEvenIfItAloneExceedsTheCap() {
        val entries = listOf(cap * 3)
        assertEquals(1, WebhookLogStore.entriesToKeep(entries) { it })
    }

    @Test
    fun dropsOldestFirstSinceEntriesAreNewestFirst() {
        // Newest first: the newest entry must survive when the cap forces eviction.
        val entries = listOf(cap, 10L, 10L)
        val kept = WebhookLogStore.entriesToKeep(entries) { it }
        assertEquals("only the newest fits", 1, kept)
    }

    @Test
    fun emptyListKeepsNothing() {
        assertEquals(0, WebhookLogStore.entriesToKeep(emptyList<Long>()) { it })
    }

    @Test
    fun shortPayloadsAreStoredWhole() {
        val payload = """{"steps":[{"count":1234}]}"""
        assertEquals(payload, WebhookLogStore.payloadToStore(payload, keepFullPayloads = false))
    }

    @Test
    fun longPayloadsAreTruncatedByDefault() {
        val payload = "x".repeat(WebhookLogStore.DEFAULT_PAYLOAD_LIMIT * 4)
        val stored = WebhookLogStore.payloadToStore(payload, keepFullPayloads = false)

        assertTrue(stored.length < payload.length)
        assertTrue("user should be told it was cut", stored.endsWith(WebhookLogStore.TRUNCATION_MARKER))
        assertEquals(
            WebhookLogStore.DEFAULT_PAYLOAD_LIMIT + WebhookLogStore.TRUNCATION_MARKER.length,
            stored.length
        )
    }

    @Test
    fun longPayloadsAreKeptWholeWhenTheUserOptsIn() {
        val payload = "x".repeat(WebhookLogStore.DEFAULT_PAYLOAD_LIMIT * 4)
        assertEquals(payload, WebhookLogStore.payloadToStore(payload, keepFullPayloads = true))
    }

    /**
     * The regression this whole change exists for: 100 syncs at the per-type record caps used to
     * retain roughly 25 MB. With the byte cap the retained set stays bounded.
     */
    @Test
    fun oneHundredBusySyncsStayWithinTheByteCap() {
        val busyPayload = 260L * 1024  // HR + steps at their per-sync caps
        val entries = List(100) { busyPayload }

        val kept = WebhookLogStore.entriesToKeep(entries) { it }
        val retained = kept * busyPayload

        assertTrue("retained $retained bytes exceeds the cap", retained <= cap)
        assertTrue("should still keep a useful number of entries", kept >= 15)
    }
}
