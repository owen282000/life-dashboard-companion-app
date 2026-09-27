package com.owen282000.lifedashboard

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
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

    /**
     * Queues a payload and returns what the cap of its source pushed out, oldest first. Their
     * records are gone for good (the watermarks moved on when they were read), so the caller
     * reports them: see [Companion.enqueue].
     */
    fun enqueue(payload: String, dataType: String, logType: String, recordCount: Int, nowMillis: Long): List<PendingItem> {
        dir.mkdirs()
        val item = PendingItem(
            id = UUID.randomUUID().toString(),
            payload = payload,
            dataType = dataType,
            logType = logType,
            recordCount = recordCount,
            createdAt = nowMillis
        )
        write(item)
        return enforceCap(item)
    }

    /**
     * Oldest first, so drains deliver in the original order. Unreadable files are dropped:
     * [write] never leaves half a file, so only outside damage makes one. A temp file left by
     * a crash mid-write is cleared once it is an hour old.
     */
    fun peekAll(): List<PendingItem> {
        val stale = System.currentTimeMillis() - STALE_TEMP_MS
        dir.listFiles { f -> f.extension == "tmp" && f.lastModified() < stale }?.forEach { it.delete() }
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
        // Replaced by a newer Screen Time snapshot while the drain was posting it: writing it
        // back would queue it again.
        if (!File(dir, "${item.id}.json").exists()) return
        write(item.copy(attempts = item.attempts + 1))
    }

    fun size(): Int = dir.listFiles { f -> f.extension == "json" }?.size ?: 0

    /** Temp file plus rename, so a crash mid-write leaves the old file or none, never half of one. */
    private fun write(item: PendingItem) {
        val temp = File(dir, "${item.id}.tmp")
        try {
            FileOutputStream(temp).use { out ->
                out.write(json.encodeToString(item).toByteArray())
                out.fd.sync()
            }
            Files.move(
                temp.toPath(),
                File(dir, "${item.id}.json").toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            )
        } finally {
            temp.delete()
        }
    }

    /**
     * Bounds on-device storage per source, so one source never pushes out the other's
     * payloads. Beyond the cap the oldest go, never the item just written.
     */
    private fun enforceCap(item: PendingItem): List<PendingItem> {
        val isScreenTime = item.logType == LogType.SCREEN_TIME.name
        val cap = if (isScreenTime) MAX_SCREEN_TIME_ITEMS else MAX_HEALTH_ITEMS
        // Counting files is enough while the whole outbox fits, which spares reading every payload.
        if (size() <= cap) return emptyList()
        val same = peekAll().filter { it.logType == item.logType }
        if (same.size <= cap) return emptyList()
        val pushedOut = same.filter { it.id != item.id }.take(same.size - cap)
        pushedOut.forEach { remove(it.id) }
        // A Screen Time snapshot carries all 7 days, so the one it replaced lost nothing.
        return if (isScreenTime) emptyList() else pushedOut
    }

    companion object {
        /**
         * A week of failed syncs at the shortest interval, 15 minutes (7 x 96 = 672), with room
         * for manual syncs: the same week the drainer keeps offering a refused payload. An
         * outage of several hours, or a week away with Home Assistant off, loses nothing. A
         * sync with nothing new queues nothing, and a 15-minute payload holds 15 minutes of
         * records, so even a full outbox stays at a few megabytes.
         */
        const val MAX_HEALTH_ITEMS = 700

        /** Every Screen Time snapshot carries the full 7 days, so the newest replaces the one queued. */
        const val MAX_SCREEN_TIME_ITEMS = 1

        private const val STALE_TEMP_MS = 60L * 60 * 1000

        fun forContext(context: android.content.Context): PendingSyncStore =
            PendingSyncStore(File(context.filesDir, "pending_sync"))

        /**
         * Queues a payload in the app's outbox and reports each payload the cap pushed out: a
         * row in the Logs tab, and a notification, since their records are lost.
         */
        fun enqueue(
            context: android.content.Context,
            payload: String,
            dataType: String,
            logType: String,
            recordCount: Int,
            nowMillis: Long
        ) {
            val dropped = forContext(context).enqueue(payload, dataType, logType, recordCount, nowMillis)
            if (dropped.isEmpty()) return
            val source = if (logType == LogType.SCREEN_TIME.name) LogType.SCREEN_TIME else LogType.HEALTH_CONNECT
            val preferencesManager = PreferencesManager(context)
            val urls = if (source == LogType.SCREEN_TIME) preferencesManager.getScreenTimeWebhookUrls()
                       else preferencesManager.getHealthWebhookUrls()
            for (item in dropped) {
                preferencesManager.addWebhookLog(
                    WebhookLog(
                        id = UUID.randomUUID().toString(),
                        timestamp = nowMillis,
                        url = urls.joinToString(", "),
                        statusCode = null,
                        success = false,
                        errorMessage = context.getString(R.string.outbox_dropped_log, MAX_HEALTH_ITEMS),
                        dataType = item.dataType,
                        recordCount = item.recordCount,
                        rawPayload = item.payload,
                        logType = source.name
                    )
                )
            }
            SyncFailureNotifier.notifyOutboxDropped(context, source, dropped.size)
        }
    }
}
