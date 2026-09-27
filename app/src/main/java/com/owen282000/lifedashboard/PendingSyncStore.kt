package com.owen282000.lifedashboard

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
        val attempts: Int = 0,
        /**
         * Screen Time only: the first day (yyyy-MM-dd) no delivery has covered yet. Carried over
         * from the snapshot this one replaced, since that snapshot and the days it held are gone.
         */
        val undeliveredSince: String? = null
    )

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Queues a payload and returns what it pushed out undelivered, oldest first. Their records
     * are gone for good (the watermarks moved on when they were read), so the caller reports
     * them: see [Companion.enqueue].
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
        if (logType == LogType.SCREEN_TIME.name) return replaceSnapshot(item)
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
     * Bounds on-device storage for Health Connect, apart from Screen Time, so one source never
     * pushes out the other's payloads. Beyond the cap the oldest go, never the item just written.
     */
    private fun enforceCap(item: PendingItem): List<PendingItem> {
        // Counting files is enough while the whole outbox fits, which spares reading every payload.
        if (size() <= MAX_HEALTH_ITEMS) return emptyList()
        val same = peekAll().filter { it.logType == item.logType }
        if (same.size <= MAX_HEALTH_ITEMS) return emptyList()
        val pushedOut = same.filter { it.id != item.id }.take(same.size - MAX_HEALTH_ITEMS)
        pushedOut.forEach { remove(it.id) }
        return pushedOut
    }

    /**
     * A Screen Time snapshot carries the last 7 days, so it replaces the one queued before it
     * instead of queuing another. That loses nothing while no undelivered day falls out of the
     * 7: only after more than a week without a delivery does a replaced snapshot hold a day the
     * new one lacks, and then it is returned to be reported. The undelivered days start at the
     * newest day of the first snapshot that failed, where the last delivery, normally one sync
     * earlier, left off.
     */
    private fun replaceSnapshot(item: PendingItem): List<PendingItem> {
        val queued = peekAll().filter { it.logType == item.logType }
        val days = screenTimeDays(item.payload)
        val since = (queued.map { it.undeliveredSince ?: screenTimeDays(it.payload).maxOrNull() } + days.maxOrNull())
            .filterNotNull()
            .minOrNull()
        write(item.copy(undeliveredSince = since))
        queued.forEach { remove(it.id) }
        if (since == null) return emptyList()
        return queued.filter { old -> screenTimeDays(old.payload).any { it >= since && it !in days } }
    }

    /** The dates a Screen Time payload holds, yyyy-MM-dd so they sort as text; none when it does not parse. */
    private fun screenTimeDays(payload: String): List<String> = try {
        json.parseToJsonElement(payload).jsonObject["screen_time"]?.jsonArray
            ?.mapNotNull { it.jsonObject["date"]?.jsonPrimitive?.contentOrNull }
            .orEmpty()
    } catch (e: Exception) {
        emptyList()
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

        private const val STALE_TEMP_MS = 60L * 60 * 1000

        fun forContext(context: android.content.Context): PendingSyncStore =
            PendingSyncStore(File(context.filesDir, "pending_sync"))

        /**
         * Queues a payload in the app's outbox and reports each payload it pushed out
         * undelivered: a row in the Logs tab, and a notification, since their records are lost.
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
            val reason = if (source == LogType.SCREEN_TIME) context.getString(R.string.outbox_replaced_log)
                         else context.getString(R.string.outbox_dropped_log, MAX_HEALTH_ITEMS)
            for (item in dropped) {
                preferencesManager.addWebhookLog(
                    WebhookLog(
                        id = UUID.randomUUID().toString(),
                        timestamp = nowMillis,
                        url = urls.joinToString(", "),
                        statusCode = null,
                        success = false,
                        errorMessage = reason,
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
