package com.owen282000.lifedashboard

import android.content.Context
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

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
    fun add(log: WebhookLog, keepFullPayloads: Boolean) {
        val payload = log.rawPayload
        if (!payload.isNullOrEmpty()) {
            runCatching { payloadFile(log.id).writeText(payloadToStore(payload, keepFullPayloads)) }
        }

        val entries = buildList {
            add(log.copy(rawPayload = null))
            addAll(readEntries())
        }
        writeEntries(evict(entries))
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
        return filtered.map { entry ->
            val payload = runCatching {
                payloadFile(entry.id).takeIf { it.exists() }?.readText()
            }.getOrNull()
            entry.copy(rawPayload = payload)
        }
    }

    /** Clears every log, or only those of [filterType]. */
    fun clear(filterType: LogType? = null) {
        if (filterType == null) {
            prefs.edit().remove(KEY_ENTRIES).apply()
            payloadDir.listFiles()?.forEach { it.delete() }
            return
        }
        val remaining = readEntries().filterNot { it.logType == filterType.name }
        writeEntries(remaining)
        val keptIds = remaining.mapTo(mutableSetOf()) { it.id }
        payloadDir.listFiles()?.forEach { file ->
            if (file.nameWithoutExtension !in keptIds) file.delete()
        }
    }

    /**
     * Moves logs written by earlier versions out of the main prefs file. The payloads are
     * dropped rather than migrated: they are the bulk of the data, they are raw health records,
     * and the entries they belong to are about to age out anyway.
     */
    fun migrateFromLegacyPrefs(legacyPrefs: android.content.SharedPreferences) {
        val legacyJson = legacyPrefs.getString(LEGACY_KEY_WEBHOOK_LOGS, null) ?: return
        runCatching {
            val legacy = Json.decodeFromString<List<WebhookLog>>(legacyJson)
            if (readEntries().isEmpty() && legacy.isNotEmpty()) {
                writeEntries(legacy.take(MAX_ENTRIES).map { it.copy(rawPayload = null) })
            }
        }
        legacyPrefs.edit().remove(LEGACY_KEY_WEBHOOK_LOGS).apply()
    }

    companion object {
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
