package com.owen282000.lifedashboard

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.URLDecoder
import java.util.UUID

/**
 * One address a section posts to, with what goes along to it alone: its signing secret and its
 * custom headers (P2-13). Before this, a section had one secret and one set of headers for all
 * of its addresses, so pairing a second receiver replaced the secret the first one checked.
 *
 * [id] stays when [url] is edited (http to https, a new port), so the secret and the headers
 * stay with the receiver they were set for. [format] is always [FORMAT_DEFAULT], the JSON
 * payload; it is stored so a later format needs no migration.
 */
data class Destination(
    val id: String,
    val url: String,
    val format: String = FORMAT_DEFAULT,
    val secret: String? = null,
    val headers: Map<String, String> = emptyMap()
) {
    companion object {
        const val FORMAT_DEFAULT = "default"
    }
}

/** What is wrong with an address that holds a credential: [parameter] names the query parameter, null is a user name or password before the @. */
data class UrlCredential(val parameter: String?)

/** The rules for a section's destinations, free of Android types so they can be unit tested. */
object Destinations {

    fun newId(): String = UUID.randomUUID().toString()

    /**
     * The id of an address read from the keys of 1.27.0 and older. Derived from the section and
     * the address, so every read of the same settings names the same destination, also before
     * the list is stored for the first time.
     */
    fun derivedId(source: LogType, url: String): String =
        UUID.nameUUIDFromBytes("${source.name}|$url".toByteArray(Charsets.UTF_8)).toString()

    /**
     * The destinations of a section as 1.27.0 and older stored it: every address gets the
     * section's secret, and its headers unless pairing added it ([urlsWithoutHeaders]), so
     * nothing a receiver gets changes with the update.
     */
    fun derive(
        source: LogType,
        urls: List<String>,
        secret: String?,
        headers: Map<String, String>,
        urlsWithoutHeaders: Set<String>
    ): List<Destination> = urls.filter { it.isNotBlank() }.distinct().map { url ->
        Destination(
            id = derivedId(source, url),
            url = url,
            secret = secret?.takeIf { it.isNotBlank() },
            headers = WebhookSupport.headersFor(url, headers, urlsWithoutHeaders)
        )
    }

    /**
     * [current] with [url] paired: an address already there gets [secret] and keeps its
     * headers; a new one is added with [secret] and no headers, since a pairing link can come
     * from anyone and the headers were typed for the others. The other destinations keep
     * their own secret.
     */
    fun paired(current: List<Destination>, url: String, secret: String, newId: () -> String = ::newId): List<Destination> =
        if (current.any { it.url == url }) {
            current.map { if (it.url == url) it.copy(secret = secret) else it }
        } else {
            current + Destination(id = newId(), url = url, secret = secret)
        }

    /** The one secret every destination has, or null when they differ or have none. */
    fun sharedSecret(destinations: List<Destination>): String? =
        destinations.map { it.secret?.takeIf { s -> s.isNotBlank() } }.distinct().singleOrNull()

    /**
     * The query parameters that are refused in an address, compared without case. Names that
     * only ever carry a credential: not `key` or single letters, which webhook URLs use for
     * other things (`?p=1`). Home Assistant, n8n, Node-RED, Make, Zapier and IFTTT keep their
     * ids and keys in the path, which is not checked.
     */
    private val CREDENTIAL_PARAMETERS = setOf("password", "token", "access_token", "apikey", "api_key", "secret", "auth")

    /**
     * The credential [url] holds, or null when it holds none: a user name or password before
     * the host, or a query parameter in [CREDENTIAL_PARAMETERS]. Addresses are kept in plain
     * settings, which go into Android's cloud backup, a settings export without secrets and
     * the logs; a credential belongs in a header of that destination.
     *
     * A Google Apps Script web app is the exception for the query: it cannot read headers, so
     * a token in its URL is the only check it can make (docs/webhook.md).
     */
    fun credentialIn(url: String): UrlCredential? {
        val trimmed = url.trim()
        val afterScheme = trimmed.substringAfter("://", trimmed)
        val authority = afterScheme.substringBefore('/').substringBefore('?').substringBefore('#')
        if ('@' in authority) return UrlCredential(null)
        if (WebhookSupport.isAppsScriptWebApp(trimmed)) return null
        val query = afterScheme.substringAfter('?', "").substringBefore('#')
        if (query.isEmpty()) return null
        return query.split('&')
            .map { decode(it.substringBefore('=')) }
            .firstOrNull { it.lowercase() in CREDENTIAL_PARAMETERS }
            ?.let { UrlCredential(it) }
    }

    private fun decode(name: String): String = try {
        URLDecoder.decode(name, "UTF-8")
    } catch (e: IllegalArgumentException) {
        name
    }

    /**
     * The section keys of a settings backup as a reader without destinations takes them: Android
     * versions before them, and the iPhone app. Such a reader sends the section's headers to every
     * address not in [LegacySection.urlsWithoutHeaders] and signs every address with
     * [LegacySection.secret]. So headers are only written when every destination with headers
     * has the same ones, and secret only when every destination has the same one; otherwise
     * none, and every address is listed as without headers: an old reader then gets a 401
     * instead of sending one receiver's key to another.
     */
    fun legacy(destinations: List<Destination>, sectionSecret: String?): LegacySection {
        val headerSets = destinations.map { it.headers }.filter { it.isNotEmpty() }.distinct()
        val headers = headerSets.singleOrNull().orEmpty()
        return LegacySection(
            headers = headers,
            // Without any headers there is nothing to hold back from an address.
            urlsWithoutHeaders = if (headerSets.isEmpty()) emptyList() else destinations.filter { it.headers.isEmpty() || it.headers != headers }.map { it.url },
            secret = if (destinations.isEmpty()) sectionSecret else sharedSecret(destinations)
        )
    }

    /** Whether [a] and [b] name the same host; an address that does not parse names none. */
    fun sameHost(a: String, b: String): Boolean {
        val hostA = a.trim().toHttpUrlOrNull()?.host ?: return false
        return hostA.equals(b.trim().toHttpUrlOrNull()?.host, ignoreCase = true)
    }

    /**
     * How [destination] is named among [section] on a button: its host, with the port when it
     * is not the scheme's own, and with the path when another destination has the same host
     * and port, so two addresses on one server are still told apart.
     */
    fun label(destination: Destination, section: List<Destination>): String {
        val url = destination.url.trim().toHttpUrlOrNull() ?: return destination.url
        val hostAndPort = if (url.port == HttpUrl.defaultPort(url.scheme)) url.host else "${url.host}:${url.port}"
        val sharesHost = section.any { other ->
            other.id != destination.id && other.url.trim().toHttpUrlOrNull()?.let { it.host == url.host && it.port == url.port } == true
        }
        return if (sharesHost) hostAndPort + url.encodedPath else hostAndPort
    }
}

/** See [Destinations.legacy]. */
data class LegacySection(
    val headers: Map<String, String>,
    val urlsWithoutHeaders: List<String>,
    val secret: String?
)
