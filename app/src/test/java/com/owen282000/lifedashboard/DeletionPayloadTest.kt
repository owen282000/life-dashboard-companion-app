package com.owen282000.lifedashboard

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The deletion fields exactly as they reach a receiver (issue #61), taken from the production
 * builder rather than a copy of it: these fields instruct a receiver to remove data it already
 * stored, so a wrong key or a field that appears when it should not destroys user data on their
 * own server.
 */
class DeletionPayloadTest {

    private fun fields(summary: DeletionSummary) = DeletionTracking.payloadFields(summary)

    private fun deletion(type: String, uuid: String) = DeletedRecord(type, uuid)

    @Test
    fun `nothing deleted means no deletion fields at all`() {
        // A receiver distinguishes "nothing was deleted" from "deletions I cannot see" by
        // presence, so an empty array would be a different statement than an absent one.
        val json = fields(DeletionSummary.EMPTY)

        assertFalse(json.containsKey("deleted_records"))
        assertFalse(json.containsKey("deletions_unavailable"))
    }

    @Test
    fun `a deleted record names the collection it arrived in and its uuid`() {
        val json = fields(DeletionSummary(deleted = listOf(deletion("nutrition", "abc-123"))))

        val entry = json.getValue("deleted_records").jsonArray.single().jsonObject
        assertEquals("nutrition", entry.getValue("type").jsonPrimitive.content)
        assertEquals("abc-123", entry.getValue("uuid").jsonPrimitive.content)
    }

    @Test
    fun `several deletions keep their order and each stay separate`() {
        // Two entries that differ only in uuid are two distinct records: a receiver that merged
        // them would drop one deletion and keep a record Health Connect no longer has.
        val json = fields(
            DeletionSummary(
                deleted = listOf(deletion("hydration", "a"), deletion("nutrition", "b"))
            )
        )

        val entries = json.getValue("deleted_records").jsonArray.map {
            it.jsonObject.getValue("type").jsonPrimitive.content to
                it.jsonObject.getValue("uuid").jsonPrimitive.content
        }
        assertEquals(listOf("hydration" to "a", "nutrition" to "b"), entries)
    }

    @Test
    fun `an unreconcilable type is named without pretending to know its deletions`() {
        // Its deletions were never observed, so there is nothing to list; naming the type is
        // what lets a receiver fall back to a backfill window for it.
        val json = fields(DeletionSummary(expiredTypes = listOf("nutrition")))

        assertFalse(json.containsKey("deleted_records"))
        assertEquals(
            listOf("nutrition"),
            json.getValue("deletions_unavailable").jsonArray.map { it.jsonPrimitive.content }
        )
    }

    @Test
    fun `deletions and unreconcilable types can appear together`() {
        // One type can be readable while another lost its token in the same sync.
        val json = fields(
            DeletionSummary(
                deleted = listOf(deletion("nutrition", "a")),
                expiredTypes = listOf("heart_rate")
            )
        )

        assertEquals(1, json.getValue("deleted_records").jsonArray.size)
        assertEquals(1, json.getValue("deletions_unavailable").jsonArray.size)
    }

    @Test
    fun `the schema declares every field these payloads can produce`() {
        // A receiver written against the published schema has to be able to find them.
        val schema = Json.parseToJsonElement(File("../docs/webhook-schema.json").readText())
            .jsonObject.getValue("properties").jsonObject.keys

        val emitted = fields(
            DeletionSummary(
                deleted = listOf(deletion("nutrition", "a")),
                expiredTypes = listOf("steps")
            )
        ).keys

        val missing = emitted.filter { it !in schema }
        assertTrue("schema missing keys: $missing", missing.isEmpty())
    }
}
