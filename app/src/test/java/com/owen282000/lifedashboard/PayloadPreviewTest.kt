package com.owen282000.lifedashboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the Logs tab and the data preview show of a payload (P2-11). */
class PayloadPreviewTest {

    private val max = PayloadPreview.MAX_CHARS

    @Test
    fun aShortPayloadIsShownWhole() {
        val preview = PayloadPreview.of("""{"steps":[]}""")
        assertEquals("""{"steps":[]}""", preview.text)
        assertFalse(preview.cutForDisplay)
        assertFalse(preview.cutInStorage)
    }

    @Test
    fun aPayloadOfExactlyTheLimitIsShownWhole() {
        val preview = PayloadPreview.of("a".repeat(max))
        assertEquals(max, preview.text.length)
        assertFalse(preview.cutForDisplay)
    }

    @Test
    fun aLongPayloadStopsAtTheLimitAndKnowsItsLength() {
        val preview = PayloadPreview.of("a".repeat(300_000))
        assertEquals(max, preview.text.length)
        assertEquals(300_000, preview.totalLength)
        assertTrue(preview.cutForDisplay)
        assertFalse(preview.cutInStorage)
    }

    @Test
    fun aCutEndsOnALineWhenOneIsClose() {
        val line = "  \"count\": 1234,\n"
        val payload = line.repeat(max / line.length + 100)
        val preview = PayloadPreview.of(payload)
        assertTrue(preview.text.length <= max)
        assertTrue("ends on a whole line", payload.startsWith(preview.text + "\n"))
    }

    @Test
    fun aCutNeverSplitsASurrogatePair() {
        // The emoji's two chars sit at max - 1 and max: a plain take(max) keeps only the first.
        val payload = "a".repeat(max - 1) + "👍" + "a".repeat(100)
        val preview = PayloadPreview.of(payload)
        assertFalse(Character.isHighSurrogate(preview.text.last()))
        assertEquals(max - 1, preview.text.length)
    }

    @Test
    fun theStoresMarkerBecomesAFlagAndLeavesTheText() {
        val stored = WebhookLogStore.payloadToStore("a".repeat(50_000), keepFullPayloads = false)
        val preview = PayloadPreview.of(stored)
        assertTrue(preview.cutInStorage)
        assertFalse(WebhookLogStore.TRUNCATION_MARKER.trim() in preview.text)
        assertEquals(WebhookLogStore.DEFAULT_PAYLOAD_LIMIT, preview.totalLength)
    }

    @Test
    fun prettyPrintsJsonAndLeavesTheRestAlone() {
        assertEquals("{\n    \"a\": 1\n}", PayloadPreview.pretty("""{"a":1}"""))
        val cut = """{"a":""" + WebhookLogStore.TRUNCATION_MARKER
        assertEquals(cut, PayloadPreview.pretty(cut))
    }
}
