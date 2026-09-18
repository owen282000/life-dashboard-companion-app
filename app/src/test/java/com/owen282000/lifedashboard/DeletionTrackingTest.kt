package com.owen282000.lifedashboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The decisions a receiver depends on when it reconciles deletions (issue #61): which token is
 * worth using, what the deleted list looks like, and which types admit they may have missed
 * something. Health Connect itself is not involved in any of these.
 */
class DeletionTrackingTest {

    private val dayMs = 24 * 60 * 60 * 1000L
    private val now = 1_758_000_000_000L // a fixed "now"; the values only matter relative to it

    private fun deletion(type: String, uuid: String) = DeletedRecord(type, uuid)

    @Test
    fun `no token means nothing to use`() {
        assertFalse(DeletionTracking.isTokenUsable(null, now, now))
        assertFalse(DeletionTracking.isTokenUsable("", now, now))
    }

    @Test
    fun `a token without an issue time cannot be judged and is not used`() {
        // An install that stored a token before this field existed lands here: registering a new
        // token costs one call, while trusting an undatable one risks a silent 30-day gap.
        assertFalse(DeletionTracking.isTokenUsable("token", null, now))
    }

    @Test
    fun `a fresh token is used`() {
        assertTrue(DeletionTracking.isTokenUsable("token", now - dayMs, now))
    }

    @Test
    fun `a token just under thirty days is still used`() {
        val justUnder = now - (DeletionTracking.TOKEN_MAX_AGE_DAYS * dayMs) + 1000
        assertTrue(DeletionTracking.isTokenUsable("token", justUnder, now))
    }

    @Test
    fun `a token at thirty days is not used`() {
        // Health Connect refuses it at this point, so asking would spend a call to be told so.
        val exactly = now - (DeletionTracking.TOKEN_MAX_AGE_DAYS * dayMs)
        assertFalse(DeletionTracking.isTokenUsable("token", exactly, now))
    }

    @Test
    fun `a token issued in the future is not trusted`() {
        // The clock moved backwards (timezone change, manual set). Age is then meaningless, so
        // the token is replaced rather than believed.
        assertFalse(DeletionTracking.isTokenUsable("token", now + dayMs, now))
    }

    @Test
    fun `merge sorts by type then uuid so two syncs produce the same payload`() {
        val results = mapOf(
            HealthDataType.NUTRITION to ChangesResult(
                deleted = listOf(deletion("nutrition", "b"), deletion("nutrition", "a"))
            ),
            HealthDataType.HYDRATION to ChangesResult(deleted = listOf(deletion("hydration", "z")))
        )

        assertEquals(
            listOf(
                deletion("hydration", "z"),
                deletion("nutrition", "a"),
                deletion("nutrition", "b")
            ),
            DeletionTracking.merge(results)
        )
    }

    @Test
    fun `merge drops a duplicate of the same record`() {
        // Two types can report the same id only through overlap in the feed; a receiver should
        // see one instruction to drop it, not two.
        val results = mapOf(
            HealthDataType.NUTRITION to ChangesResult(
                deleted = listOf(deletion("nutrition", "a"), deletion("nutrition", "a"))
            )
        )

        assertEquals(listOf(deletion("nutrition", "a")), DeletionTracking.merge(results))
    }

    @Test
    fun `merge of nothing is empty`() {
        val results = mapOf(HealthDataType.STEPS to ChangesResult(nextToken = "t"))
        assertTrue(DeletionTracking.merge(results).isEmpty())
    }

    @Test
    fun `expired types are reported by payload key and sorted`() {
        val results = mapOf(
            HealthDataType.NUTRITION to ChangesResult(expired = true),
            HealthDataType.HEART_RATE to ChangesResult(expired = true),
            HealthDataType.STEPS to ChangesResult(deleted = listOf(deletion("steps", "a")))
        )

        assertEquals(listOf("heart_rate", "nutrition"), DeletionTracking.expiredTypes(results))
    }

    @Test
    fun `a type that could not be read is reported as unreconcilable too`() {
        // Its token is kept, so the next sync catches up, but this payload must not pass for a
        // complete picture: a receiver would keep records that were deleted.
        val results = mapOf(
            HealthDataType.NUTRITION to ChangesResult(error = "Health Connect is not available")
        )

        assertEquals(listOf("nutrition"), DeletionTracking.expiredTypes(results))
    }

    @Test
    fun `a type that read cleanly is not reported`() {
        val results = mapOf(
            HealthDataType.STEPS to ChangesResult(deleted = listOf(deletion("steps", "a")), nextToken = "t")
        )

        assertTrue(DeletionTracking.expiredTypes(results).isEmpty())
    }

    @Test
    fun `every type has a payload key and the keys are unique`() {
        // deleted_records points at the collection a record arrived in, so a key that is wrong
        // or shared would send a receiver to the wrong list.
        val keys = HealthDataType.entries.map { DeletionTracking.payloadKey(it) }

        assertTrue(keys.none { it.isBlank() })
        assertEquals(HealthDataType.entries.size, keys.toSet().size)
    }

    @Test
    fun `an empty summary is recognisable as nothing to report`() {
        assertTrue(DeletionSummary.EMPTY.isEmpty)
        assertFalse(DeletionSummary(deleted = listOf(deletion("steps", "a"))).isEmpty)
        assertFalse(DeletionSummary(expiredTypes = listOf("steps")).isEmpty)
    }

    @Test
    fun `a summary carried from an earlier sync joins what this sync found`() {
        // The changes feed is consumed by reading it, so a sync that read deletions but never
        // built a payload has to hand them to the next one. Both sets must survive the join.
        val carried = DeletionSummary(
            deleted = listOf(deletion("nutrition", "old")),
            expiredTypes = listOf("hydration")
        )
        val fresh = DeletionSummary(
            deleted = listOf(deletion("nutrition", "new")),
            expiredTypes = listOf("steps")
        )

        val merged = carried.merge(fresh)

        assertEquals(
            listOf(deletion("nutrition", "new"), deletion("nutrition", "old")),
            merged.deleted
        )
        assertEquals(listOf("hydration", "steps"), merged.expiredTypes)
    }

    @Test
    fun `merging the same deletion twice leaves one`() {
        // A sync that failed to deliver keeps its deletions; the next sync must not send the
        // same uuid twice just because it was carried.
        val carried = DeletionSummary(deleted = listOf(deletion("nutrition", "a")))

        assertEquals(carried.deleted, carried.merge(carried).deleted)
    }

    @Test
    fun `merging with nothing changes nothing`() {
        val summary = DeletionSummary(deleted = listOf(deletion("steps", "a")))

        assertEquals(summary, summary.merge(DeletionSummary.EMPTY))
        assertEquals(summary, DeletionSummary.EMPTY.merge(summary))
        assertTrue(DeletionSummary.EMPTY.merge(DeletionSummary.EMPTY).isEmpty)
    }

    @Test
    fun `a carried summary survives being stored and read back`() {
        // It lives in preferences between syncs, so the round trip has to preserve it exactly.
        val summary = DeletionSummary(
            deleted = listOf(deletion("nutrition", "a"), deletion("steps", "b")),
            expiredTypes = listOf("hydration")
        )

        val json = kotlinx.serialization.json.Json.encodeToString(summary)
        val restored = kotlinx.serialization.json.Json.decodeFromString<DeletionSummary>(json)

        assertEquals(summary, restored)
    }
}
