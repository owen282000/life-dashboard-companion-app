package com.owen282000.lifedashboard

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/**
 * Store-and-forward outbox for payloads whose webhook delivery failed (server down, no
 * network). Items are persisted as one JSON file each and drained at the start of the next
 * sync, so sync watermarks can safely advance the moment a payload is read: delivery is
 * guaranteed to happen eventually instead of re-reading (and possibly re-losing) the data.
 * Mirrors the iOS app's PendingSyncStore. Pure file-based so it is unit testable on the JVM.
 */
class PendingSyncStore(private val dir: File) {

    @Serializable
    data class PendingItem(
        val id: String,
        val payload: String,
        val dataType: String,
        val logType: String,
        val recordCount: Int,
        val createdAt: Long,
        val attempts: Int = 0
    )

    private val json = Json { ignoreUnknownKeys = true }

    fun enqueue(payload: String, dataType: String, logType: String, recordCount: Int, nowMillis: Long) {
        dir.mkdirs()
        val item = PendingItem(
            id = UUID.randomUUID().toString(),
            payload = payload,
            dataType = dataType,
            logType = logType,
            recordCount = recordCount,
            createdAt = nowMillis
        )
        File(dir, "${item.id}.json").writeText(json.encodeToString(item))
        enforceCap()
    }

    /** Oldest first, so drains deliver in the original order. Unreadable files are dropped. */
    fun peekAll(): List<PendingItem> {
        val files = dir.listFiles { f -> f.extension == "json" } ?: return emptyList()
        return files.mapNotNull { file ->
            try {
                json.decodeFromString<PendingItem>(file.readText())
            } catch (e: Exception) {
                file.delete()
                null
            }
        }.sortedBy { it.createdAt }
    }

    fun remove(id: String) {
        File(dir, "$id.json").delete()
    }

    fun recordAttempt(item: PendingItem) {
        val updated = item.copy(attempts = item.attempts + 1)
        File(dir, "${item.id}.json").writeText(json.encodeToString(updated))
    }

    fun size(): Int = dir.listFiles { f -> f.extension == "json" }?.size ?: 0

    /** Bounds on-device storage: keep at most [MAX_ITEMS], dropping the oldest beyond that. */
    private fun enforceCap() {
        val items = peekAll()
        if (items.size > MAX_ITEMS) {
            items.take(items.size - MAX_ITEMS).forEach { remove(it.id) }
        }
    }

    companion object {
        const val MAX_ITEMS = 50

        fun forContext(context: android.content.Context): PendingSyncStore =
            PendingSyncStore(File(context.filesDir, "pending_sync"))
    }
}
