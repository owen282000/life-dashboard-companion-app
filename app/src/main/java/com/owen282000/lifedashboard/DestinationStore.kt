package com.owen282000.lifedashboard

import android.content.SharedPreferences
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Where the destinations of the two webhook sections live (P2-13). The list (id, address,
 * format) is a plain setting; each destination's secret and headers are in the encrypted
 * store under its id.
 *
 * An install updated from 1.27.0 or older has no list yet. It is then derived from the old
 * keys on every read ([Destinations.derive]), and only stored by the first change in the
 * settings, a pairing or an import. Not once at startup: right after a reboot the Keystore can
 * be out of reach, and the secret store is then [InMemoryPrefs], exactly when a worker runs. A
 * list stored then would hold no secrets and stay that way. For the same reason no change is
 * stored at all while the secrets cannot be kept ([canWrite]).
 *
 * [secretsUsable] is false while the secret store is that stand-in.
 */
class DestinationStore(
    private val prefs: SharedPreferences,
    private val secrets: SharedPreferences,
    private val secretsUsable: () -> Boolean
) {

    /** The keys of one section: the list, and the keys of 1.27.0 it is derived from until then. */
    data class Keys(
        val list: String,
        val urls: String,
        val headers: String,
        val secret: String,
        val urlsWithoutHeaders: String
    )

    @Serializable
    private data class Entry(val id: String, val url: String, val format: String = Destination.FORMAT_DEFAULT)

    /** Whether [source] has a stored list, rather than one derived from the old keys. */
    fun isStored(source: LogType): Boolean = prefs.getString(keysOf(source).list, null) != null

    fun read(source: LogType): List<Destination> {
        val keys = keysOf(source)
        val entries = storedEntries(keys) ?: return Destinations.derive(
            source = source,
            urls = legacyUrls(keys),
            secret = sectionSecret(source),
            headers = decodeHeaders(secrets.getString(keys.headers, null)),
            urlsWithoutHeaders = legacyUrlsWithoutHeaders(keys)
        )
        return entries.map { entry ->
            Destination(
                id = entry.id,
                url = entry.url,
                format = entry.format,
                secret = secrets.getString(secretKey(entry.id), null)?.takeIf { it.isNotBlank() },
                headers = decodeHeaders(secrets.getString(headersKey(entry.id), null))
            )
        }
    }

    /**
     * Whether [destinations] can be stored as [source] now: the secrets can be kept, and none
     * of the values a store reads or replaces is one the Keystore cannot decrypt right now.
     * Otherwise a store would lose them, or, on the old keys, leave addresses that a later
     * read gives the section's headers and secret.
     */
    fun canWrite(source: LogType, destinations: List<Destination>): Boolean {
        if (!secretsUsable()) return false
        val keys = keysOf(source)
        val ids = (storedEntries(keys).orEmpty().map { it.id } + destinations.map { it.id }).distinct()
        val touched = listOf(keys.secret, keys.headers) + ids.flatMap { listOf(secretKey(it), headersKey(it)) }
        return touched.none { unreadableNow(it) }
    }

    /**
     * Stores [destinations] as the whole of [source], or nothing at all and false when
     * [canWrite] says no or the secrets could not be sealed: the caller says so and the user
     * tries again. The secrets go first, then the list, then the secrets of the ids that left it.
     *
     * The keys of 1.27.0 are written along, filled the way a settings backup fills them for an
     * older reader ([Destinations.legacy]): the address list, which the outbox, the drain and the
     * harness read, and the section headers and their exceptions, so a read that has to derive
     * again (an unreadable list) gives no address another one's headers. When every destination
     * has the same secret, it becomes the section's, which a destination added by hand starts with.
     */
    fun write(source: LogType, destinations: List<Destination>): Boolean {
        if (!canWrite(source, destinations)) return false
        val keys = keysOf(source)
        val previous = storedEntries(keys)
        val legacy = Destinations.legacy(destinations, sectionSecret(source))
        val sealed = secrets.edit().apply {
            for (destination in destinations) {
                putString(secretKey(destination.id), destination.secret?.trim()?.takeIf { it.isNotEmpty() })
                putString(headersKey(destination.id), encodeHeaders(destination.headers))
            }
            putString(keys.headers, encodeHeaders(legacy.headers))
            Destinations.sharedSecret(destinations)?.let { putString(keys.secret, it.trim()) }
        }.commit()
        if (!sealed) return false
        prefs.edit().apply {
            putString(keys.list, Json.encodeToString(destinations.map { Entry(it.id, it.url, it.format) }))
            putString(keys.urls, destinations.joinToString(",") { it.url })
            if (legacy.urlsWithoutHeaders.isEmpty()) {
                remove(keys.urlsWithoutHeaders)
            } else {
                putString(keys.urlsWithoutHeaders, Json.encodeToString(legacy.urlsWithoutHeaders))
            }
        }.apply()
        val kept = destinations.map { it.id }.toSet()
        val orphans = previous.orEmpty().map { it.id }.filter { it !in kept }
        if (orphans.isNotEmpty()) {
            secrets.edit().apply { orphans.forEach { remove(secretKey(it)); remove(headersKey(it)) } }.apply()
        }
        return true
    }

    /**
     * Makes the keys of 1.27.0 the settings again: the stored list and its secrets go, and the
     * destinations are derived from the old keys, as right after an update. For the harness,
     * which sets a phone up the way an update finds it.
     */
    fun forgetList(source: LogType) {
        val keys = keysOf(source)
        val previous = storedEntries(keys) ?: return
        secrets.edit().apply { previous.forEach { remove(secretKey(it.id)); remove(headersKey(it.id)) } }.apply()
        prefs.edit().remove(keys.list).apply()
    }

    /** The section's own secret: the old one secret of the section, now what a new destination starts with. */
    fun sectionSecret(source: LogType): String? = secrets.getString(keysOf(source).secret, null)?.takeIf { it.isNotBlank() }

    fun setSectionSecret(source: LogType, secret: String?) {
        val keys = keysOf(source)
        secrets.edit().putString(keys.secret, secret?.trim()?.takeIf { it.isNotEmpty() }).apply()
    }

    /** A value that is there but the Keystore could not decrypt right now; one written with another key never will be, and does not count. */
    private fun unreadableNow(key: String): Boolean =
        secrets.contains(key) && secrets.getString(key, null) == null &&
            (secrets as? EncryptedStore)?.peek(key) is EncryptedStore.Opened.Failed

    private fun storedEntries(keys: Keys): List<Entry>? {
        val stored = prefs.getString(keys.list, null) ?: return null
        return try {
            Json.decodeFromString<List<Entry>>(stored)
        } catch (e: IllegalArgumentException) {
            // Unreadable is treated as not stored: the old keys are still written alongside.
            null
        }
    }

    private fun legacyUrls(keys: Keys): List<String> {
        val stored = prefs.getString(keys.urls, "") ?: ""
        return if (stored.isEmpty()) emptyList() else stored.split(",")
    }

    private fun legacyUrlsWithoutHeaders(keys: Keys): Set<String> {
        val stored = prefs.getString(keys.urlsWithoutHeaders, null) ?: return emptySet()
        return try {
            Json.decodeFromString<List<String>>(stored).toSet()
        } catch (e: IllegalArgumentException) {
            emptySet()
        }
    }

    // ==================== The keys of 1.27.0, for the harness ====================

    fun setLegacyUrls(source: LogType, urls: List<String>) {
        forgetList(source)
        prefs.edit().putString(keysOf(source).urls, urls.joinToString(",")).apply()
    }

    fun setLegacyHeaders(source: LogType, headers: Map<String, String>) {
        forgetList(source)
        secrets.edit().putString(keysOf(source).headers, encodeHeaders(headers)).apply()
    }

    fun setLegacyUrlsWithoutHeaders(source: LogType, urls: Set<String>) {
        forgetList(source)
        val key = keysOf(source).urlsWithoutHeaders
        if (urls.isEmpty()) prefs.edit().remove(key).apply() else prefs.edit().putString(key, Json.encodeToString(urls.toList())).apply()
    }

    fun legacyHeaders(source: LogType): Map<String, String> = decodeHeaders(secrets.getString(keysOf(source).headers, null))

    companion object {
        val HEALTH = Keys(
            list = "health_destinations",
            urls = "health_webhook_urls",
            headers = "health_webhook_headers",
            secret = "health_webhook_secret",
            urlsWithoutHeaders = "health_webhook_urls_without_headers"
        )
        val SCREEN_TIME = Keys(
            list = "screentime_destinations",
            urls = "screentime_webhook_urls",
            headers = "screentime_webhook_headers",
            secret = "screentime_webhook_secret",
            urlsWithoutHeaders = "screentime_webhook_urls_without_headers"
        )

        fun keysOf(source: LogType): Keys = when (source) {
            LogType.HEALTH_CONNECT -> HEALTH
            LogType.SCREEN_TIME -> SCREEN_TIME
        }

        /** Secret-store keys per destination; ids are UUIDs, so a key never clashes with a section key. */
        fun secretKey(id: String) = "destination_secret_$id"
        fun headersKey(id: String) = "destination_headers_$id"

        /** No headers is no value, not "{}": the secret store only counts a real value as a secret entered again. */
        private fun encodeHeaders(headers: Map<String, String>): String? = headers.takeIf { it.isNotEmpty() }?.let { Json.encodeToString(it) }

        private fun decodeHeaders(json: String?): Map<String, String> {
            if (json == null) return emptyMap()
            return try {
                Json.decodeFromString<Map<String, String>>(json)
            } catch (e: IllegalArgumentException) {
                emptyMap()
            }
        }
    }
}
