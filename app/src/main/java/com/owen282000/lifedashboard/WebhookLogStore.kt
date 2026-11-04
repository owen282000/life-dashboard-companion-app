package com.owen282000.lifedashboard

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.RandomAccessFile

/**
 * A log entry for the list: what a row shows, without its payload. Reading up to 5 MB of payloads
 * back for every refresh, only to show their sizes, is what [WebhookLogStore.rows] is there to
 * avoid. [payloadBytes] is what is left of the payload without the store's marker, null without a
 * payload. [receiveTypes] are the types of a Receive row's readings.
 */
data class WebhookLogRow(
    val log: WebhookLog,
    val payloadBytes: Long? = null,
    val cutInStorage: Boolean = false,
    val receiveTypes: List<String> = emptyList()
)

/**
 * Storage for webhook logs.
 *
 * Logs used to live as one JSON string inside the main SharedPreferences file. Every delivery
 * rewrote the whole thing, and because each entry keeps its raw payload (a busy Health Connect
 * sync is ~260 KB at the per-type caps) the retained set approached tens of megabytes in a
 * single XML value that SharedPreferences also holds in memory.
 *
 * Here metadata and payloads are separate:
 *
 * - metadata (url, status, counts, error) lives in its own small prefs file, rewritten per log
 *   but only kilobytes in size;
 * - each raw payload is a separate file under `webhook_payloads/`, written once and deleted
 *   when its entry is evicted.
 *
 * Eviction is by total payload bytes ([MAX_PAYLOAD_BYTES]) as well as entry count
 * ([MAX_ENTRIES]), so a run of large payloads cannot blow up storage the way a count-only cap
 * allowed. Payloads are truncated to [DEFAULT_PAYLOAD_LIMIT] unless the user asks to keep them
 * in full.
 *
 * Neither file is backed up; see `res/xml/backup_rules.xml`. Payloads are raw health data.
 */
class WebhookLogStore(private val context: Context) {

    private val prefs = context.getSharedPreferences(LOGS_PREFS_NAME, Context.MODE_PRIVATE)

    private val payloadDir: File by lazy {
        File(context.filesDir, PAYLOAD_DIR).apply { mkdirs() }
    }

    /** Metadata only; [WebhookLog.rawPayload] is always null here. */
    private fun readEntries(): List<WebhookLog> {
        val json = prefs.getString(KEY_ENTRIES, null) ?: return emptyList()
        return try {
            Json.decodeFromString<List<WebhookLog>>(json)
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun writeEntries(entries: List<WebhookLog>) {
        prefs.edit().putString(KEY_ENTRIES, Json.encodeToString(entries)).apply()
    }

    private fun payloadFile(id: String) = File(payloadDir, "$id.json")

    /**
     * Adds [log], newest first, storing its payload separately. Returns after evicting whatever
     * exceeds the entry and byte caps.
     */
    fun add(log: WebhookLog, keepFullPayloads: Boolean) = synchronized(LOCK) {
        val payload = log.rawPayload
        if (!payload.isNullOrEmpty()) {
            runCatching { payloadFile(log.id).writeText(payloadToStore(payload, keepFullPayloads)) }
        }

        val entries = buildList {
            add(log.copy(rawPayload = null))
            addAll(readEntries())
        }
        writeEntries(evict(entries))
        _changes.update { it + 1 }
    }

    /**
     * Drops entries past [MAX_ENTRIES], then keeps dropping the oldest while the payloads on
     * disk exceed [MAX_PAYLOAD_BYTES]. Payload files of dropped entries are deleted, along with
     * any orphans left behind by an interrupted write.
     */
    private fun evict(entries: List<WebhookLog>): List<WebhookLog> {
        val kept = entries.take(entriesToKeep(entries) { payloadFile(it.id).length() })

        val keptIds = kept.mapTo(mutableSetOf()) { it.id }
        payloadDir.listFiles()?.forEach { file ->
            if (file.nameWithoutExtension !in keptIds) file.delete()
        }
        return kept
    }

    /** Logs newest first, each with its stored payload read back from disk. */
    fun getAll(filterType: LogType? = null): List<WebhookLog> {
        val entries = readEntries()
        val filtered = if (filterType != null) {
            entries.filter { it.logType == filterType.name }
        } else {
            entries
        }
        return filtered.map(::withPayload)
    }

    private fun withPayload(entry: WebhookLog): WebhookLog {
        val payload = runCatching {
            payloadFile(entry.id).takeIf { it.exists() }?.readText()
        }.getOrNull()
        return entry.copy(rawPayload = payload)
    }

    /** One log with its stored payload, null when it is no longer kept. */
    fun get(id: String): WebhookLog? = readEntries().firstOrNull { it.id == id }?.let(::withPayload)

    /** Logs newest first as the list shows them; see [WebhookLogRow]. */
    fun rows(): List<WebhookLogRow> = readEntries().map { entry ->
        val file = payloadFile(entry.id)
        val length = file.length()
        when {
            length == 0L -> WebhookLogRow(entry)
            entry.direction == LogDirection.IN.name -> WebhookLogRow(
                entry,
                payloadBytes = length,
                receiveTypes = ReceiveLogLine.decode(withPayload(entry).rawPayload).orEmpty().mapNotNull { it.type }.distinct()
            )
            endsWithMarker(file, length) -> WebhookLogRow(entry, length - MARKER_BYTES.size, cutInStorage = true)
            else -> WebhookLogRow(entry, length)
        }
    }

    /** A payload is only cut past the limit, so the many small ones need no second look. */
    private fun endsWithMarker(file: File, length: Long): Boolean {
        if (length < DEFAULT_PAYLOAD_LIMIT + MARKER_BYTES.size) return false
        return runCatching {
            RandomAccessFile(file, "r").use { tail ->
                tail.seek(length - MARKER_BYTES.size)
                ByteArray(MARKER_BYTES.size).also(tail::readFully).contentEquals(MARKER_BYTES)
            }
        }.getOrDefault(false)
    }

    /** Removes the logs in [ids], with their payloads. */
    fun delete(ids: Set<String>) = synchronized(LOCK) {
        writeEntries(readEntries().filterNot { it.id in ids })
        ids.forEach { payloadFile(it).delete() }
        _changes.update { it + 1 }
    }

    /** Logs newest first without their payloads, for what only needs to know how a delivery went. */
    fun getAllWithoutPayloads(filterType: LogType? = null): List<WebhookLog> {
        val entries = readEntries()
        return if (filterType != null) entries.filter { it.logType == filterType.name } else entries
    }

    /** Clears every log, or only those of [filterType]. */
    fun clear(filterType: LogType? = null) = synchronized(LOCK) {
        if (filterType == null) {
            prefs.edit().remove(KEY_ENTRIES).apply()
            payloadDir.listFiles()?.forEach { it.delete() }
            _changes.update { it + 1 }
            return@synchronized
        }
        val remaining = readEntries().filterNot { it.logType == filterType.name }
        writeEntries(remaining)
        val keptIds = remaining.mapTo(mutableSetOf()) { it.id }
        payloadDir.listFiles()?.forEach { file ->
            if (file.nameWithoutExtension !in keptIds) file.delete()
        }
        _changes.update { it + 1 }
    }

    /**
     * Moves logs written by earlier versions out of the main prefs file. The payloads are
     * dropped rather than migrated: they are the bulk of the data, they are raw health records,
     * and the entries they belong to are about to age out anyway.
     */
    fun migrateFromLegacyPrefs(legacyPrefs: android.content.SharedPreferences) = synchronized(LOCK) {
        val legacyJson = legacyPrefs.getString(LEGACY_KEY_WEBHOOK_LOGS, null) ?: return@synchronized
        runCatching {
            val legacy = Json.decodeFromString<List<WebhookLog>>(legacyJson)
            if (readEntries().isEmpty() && legacy.isNotEmpty()) {
                writeEntries(legacy.take(MAX_ENTRIES).map { it.copy(rawPayload = null) })
            }
        }
        legacyPrefs.edit().remove(LEGACY_KEY_WEBHOOK_LOGS).apply()
    }

    companion object {
        /**
         * One lock for every store in the process. Adding is read, prepend, write: a Health
         * Connect sync, a Screen Time sync and Receive log from their own threads, each through
         * its own [WebhookLogStore], and without the lock one of two rows written at the same
         * moment was lost. Eviction also deleted the payload file of a row another thread had
         * written but not listed yet.
         */
        private val LOCK = Any()

        private val _changes = MutableStateFlow(0)

        /** Moves on with every row added or removed, so what is on screen knows to read again. */
        val changes: StateFlow<Int> = _changes.asStateFlow()

        private const val LOGS_PREFS_NAME = "life_dashboard_logs"
        private const val PAYLOAD_DIR = "webhook_payloads"
        private const val KEY_ENTRIES = "entries"
        private const val LEGACY_KEY_WEBHOOK_LOGS = "webhook_logs"

        const val MAX_ENTRIES = 100

        /** Total payload bytes kept on disk, across all entries. */
        const val MAX_PAYLOAD_BYTES = 5L * 1024 * 1024

        /** Characters kept per payload unless the user opts into full payloads. */
        const val DEFAULT_PAYLOAD_LIMIT = 16 * 1024

        const val TRUNCATION_MARKER = "\n... truncated, enable full payloads in settings to keep everything"

        private val MARKER_BYTES = TRUNCATION_MARKER.toByteArray()

        /**
         * What actually gets written for [payload]: the whole thing when the user keeps full
         * payloads or it is already small, otherwise a truncated copy with a marker.
         *
         * Pure so it can be unit tested on the JVM.
         */
        fun payloadToStore(payload: String, keepFullPayloads: Boolean): String =
            if (keepFullPayloads || payload.length <= DEFAULT_PAYLOAD_LIMIT) {
                payload
            } else {
                payload.take(DEFAULT_PAYLOAD_LIMIT) + TRUNCATION_MARKER
            }

        /**
         * How many of [entries] (newest first) to keep: at most [MAX_ENTRIES], and only while
         * the running payload total stays within [MAX_PAYLOAD_BYTES]. [sizeOf] gives the stored
         * payload size of an entry.
         *
         * At least one entry is always kept, so a single oversized payload still shows up in
         * the logs instead of vanishing. Pure so it can be unit tested on the JVM.
         */
        fun <T> entriesToKeep(entries: List<T>, sizeOf: (T) -> Long): Int {
            if (entries.isEmpty()) return 0
            var kept = minOf(entries.size, MAX_ENTRIES)
            var total = (0 until kept).sumOf { sizeOf(entries[it]) }
            while (total > MAX_PAYLOAD_BYTES && kept > 1) {
                kept--
                total -= sizeOf(entries[kept])
            }
            return kept
        }
    }
}
