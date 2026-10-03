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
 * sync, so sync watermarks can safely advance once a payload is on disk: delivery is
 * guaranteed to happen eventually instead of re-reading (and possibly re-losing) the data.
 *
 * A sync writes its payload here before it moves anything, see [writeAhead]: first into
 * [inFlightDir], where the drain does not look, and from there into the outbox when the post
 * does not deliver it. Mirrors the iOS app's PendingSyncStore and its WriteAhead. Pure
 * file-based so it is unit testable on the JVM.
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
     * Payloads a sync is posting right now. A subdirectory, so the outbox's own listings
     * ([peekAll], [size]) never see them: the drain must not post a payload its sync is still
     * waiting on, and the tile starts the health and Screen Time syncs together, each draining.
     */
    private val inFlightDir = File(dir, IN_FLIGHT_DIR)

    /**
     * Queues a payload and returns what it pushed out undelivered, oldest first. Their records
     * are gone for good (the watermarks moved on when they were read), so the caller reports
     * them: see [Companion.report]. The syncs queue through [writeAhead]; this is the same
     * step without a post in between.
     */
    fun enqueue(payload: String, dataType: String, logType: String, recordCount: Int, nowMillis: Long): List<PendingItem> =
        queueInFlight(writeInFlight(payload, dataType, logType, recordCount, nowMillis))

    /**
     * Runs a sync's delivery write-ahead, in the order of the iOS app's WriteAhead:
     * 1. the payload goes to disk as an in-flight item, out of the drain's sight;
     * 2. [commit] stores what reading it moved (watermarks, bucket carry, the deletions it
     *    carries), which is safe now that the payload cannot be lost;
     * 3. [post] sends it;
     * 4. delivered, the item is removed; anything else, a failed post, a throw or a
     *    cancellation (a stopped worker), puts it in the outbox like any failed payload, and
     *    [onQueued] gets what that pushed out.
     * A process that dies anywhere in there leaves the item in flight, and the next drain
     * queues it ([recoverInFlight]). Every record is then delivered, queued, or still ahead of
     * its watermark; at worst a payload arrives twice, which a receiver deduplicates on uuid.
     *
     * A payload that cannot be written (a full disk) still goes out, as on iOS, but without a
     * copy to fall back on: [onUnwritable] hears why, the post comes first, and [commit] runs
     * only when it delivered. Undelivered, nothing moved and nothing is queued, so the next
     * sync reads the same records again.
     */
    suspend fun <T> writeAhead(
        payload: String,
        dataType: String,
        logType: String,
        recordCount: Int,
        nowMillis: Long,
        commit: () -> Unit,
        post: suspend () -> Result<T>,
        onQueued: (List<PendingItem>) -> Unit,
        onUnwritable: (java.io.IOException) -> Unit = {}
    ): Result<T> {
        val item = try {
            writeInFlight(payload, dataType, logType, recordCount, nowMillis)
        } catch (e: java.io.IOException) {
            onUnwritable(e)
            val outcome = post()
            if (outcome.isSuccess) commit()
            return outcome
        }
        var delivered = false
        try {
            commit()
            val outcome = post()
            delivered = outcome.isSuccess
            return outcome
        } finally {
            if (delivered) landInFlight(item.id) else onQueued(queueInFlight(item))
        }
    }

    /**
     * Turns what a dead process left in flight into queued items, with the cap and the Screen
     * Time replacement, and returns what that pushed out. Items of a sync still running in
     * this process are left alone. Called by the drain, which every sync runs first.
     */
    fun recoverInFlight(): List<PendingItem> {
        val files = inFlightDir.listFiles() ?: return emptyList()
        // A temp file of a write that died with its process; a live one belongs to a sync.
        files.filter { it.extension == "tmp" && it.nameWithoutExtension !in LIVE }.forEach { it.delete() }
        return files.filter { it.extension == "json" }.flatMap { file ->
            val id = file.nameWithoutExtension
            if (!claim(id)) return@flatMap emptyList()
            val item = try {
                json.decodeFromString<PendingItem>(file.readText())
            } catch (e: Exception) {
                // Only outside damage makes one, see [peekAll].
                file.delete()
                release(id)
                return@flatMap emptyList()
            }
            queueInFlight(item)
        }
    }

    /** The ids of the payloads in flight; none of them is in [peekAll]. */
    fun inFlightIds(): List<String> =
        inFlightDir.listFiles { f -> f.extension == "json" }?.map { it.nameWithoutExtension }.orEmpty()

    /**
     * Writes an in-flight item. Its id is claimed before the file exists, so a drain running
     * beside it never takes it for a leftover.
     */
    private fun writeInFlight(payload: String, dataType: String, logType: String, recordCount: Int, nowMillis: Long): PendingItem {
        val item = PendingItem(
            id = UUID.randomUUID().toString(),
            payload = payload,
            dataType = dataType,
            logType = logType,
            recordCount = recordCount,
            createdAt = nowMillis
        )
        claim(item.id)
        try {
            inFlightDir.mkdirs()
            write(item, inFlightDir)
        } catch (e: Exception) {
            release(item.id)
            throw e
        }
        return item
    }

    /** Delivered: the in-flight copy goes. */
    private fun landInFlight(id: String) {
        try {
            File(inFlightDir, "$id.json").delete()
        } finally {
            release(id)
        }
    }

    /**
     * Moves an in-flight item into the outbox by renaming its file, so it is in flight or
     * queued at every moment and never both. Returns what the cap or the Screen Time
     * replacement pushed out.
     */
    private fun queueInFlight(item: PendingItem): List<PendingItem> = try {
        if (item.logType == LogType.SCREEN_TIME.name) {
            replaceSnapshot(item) { placed ->
                write(placed, inFlightDir)
                moveIn(placed.id)
            }
        } else {
            moveIn(item.id)
            enforceCap(item)
        }
    } finally {
        release(item.id)
    }

    private fun moveIn(id: String) {
        Files.move(
            File(inFlightDir, "$id.json").toPath(),
            File(dir, "$id.json").toPath(),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING
        )
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
        write(item.copy(attempts = item.attempts + 1), dir)
    }

    fun size(): Int = dir.listFiles { f -> f.extension == "json" }?.size ?: 0

    /** Temp file plus rename, so a crash mid-write leaves the old file or none, never half of one. */
    private fun write(item: PendingItem, into: File) {
        val temp = File(into, "${item.id}.tmp")
        try {
            FileOutputStream(temp).use { out ->
                out.write(json.encodeToString(item).toByteArray())
                out.fd.sync()
            }
            Files.move(
                temp.toPath(),
                File(into, "${item.id}.json").toPath(),
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
     * earlier, left off. [place] puts the new snapshot in the outbox before the old ones go.
     */
    private fun replaceSnapshot(item: PendingItem, place: (PendingItem) -> Unit): List<PendingItem> {
        val queued = peekAll().filter { it.logType == item.logType && it.id != item.id }
        val days = screenTimeDays(item.payload)
        val since = (queued.map { it.undeliveredSince ?: screenTimeDays(it.payload).maxOrNull() } + days.maxOrNull())
            .filterNotNull()
            .minOrNull()
        place(item.copy(undeliveredSince = since))
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

        private const val IN_FLIGHT_DIR = "in_flight"

        private const val TAG = "PendingSyncStore"

        /**
         * The in-flight ids a sync of this process holds. Memory on purpose: a process that
         * dies takes it along, and what it left in flight is then free for [recoverInFlight].
         */
        private val LIVE: MutableSet<String> = java.util.Collections.synchronizedSet(mutableSetOf())

        private fun claim(id: String): Boolean = LIVE.add(id)

        private fun release(id: String) {
            LIVE.remove(id)
        }

        fun forContext(context: android.content.Context): PendingSyncStore =
            PendingSyncStore(File(context.filesDir, "pending_sync"))

        /** [PendingSyncStore.writeAhead] in the app's outbox, with [report] for what queuing pushed out. */
        suspend fun <T> writeAhead(
            context: android.content.Context,
            payload: String,
            dataType: String,
            logType: String,
            recordCount: Int,
            commit: () -> Unit,
            post: suspend () -> Result<T>
        ): Result<T> = forContext(context).writeAhead(
            payload = payload,
            dataType = dataType,
            logType = logType,
            recordCount = recordCount,
            nowMillis = System.currentTimeMillis(),
            commit = commit,
            post = post,
            onQueued = { dropped -> report(context, logType, dropped, System.currentTimeMillis()) },
            onUnwritable = { e ->
                android.util.Log.w(TAG, "Outbox not writable, $dataType payload posted without a copy; watermarks move only on delivery", e)
            }
        )

        /** [PendingSyncStore.recoverInFlight] in the app's outbox, with [report] for what it pushed out. */
        fun recoverInFlight(context: android.content.Context) {
            forContext(context).recoverInFlight().groupBy { it.logType }.forEach { (logType, dropped) ->
                report(context, logType, dropped, System.currentTimeMillis())
            }
        }

        /**
         * Reports each payload that queuing pushed out undelivered: a row in the Logs tab, and
         * a notification, since their records are lost.
         */
        private fun report(context: android.content.Context, logType: String, dropped: List<PendingItem>, nowMillis: Long) {
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
