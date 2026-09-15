package com.owen282000.lifedashboard

import com.owen282000.lifedashboard.viewmodel.SettingsRules
import java.net.URI
import java.net.URLDecoder

/** Which webhook section a pairing code is willing to feed. */
enum class PairingSource(val id: String) {
    HEALTH("health_connect"),
    SCREEN_TIME("screen_time");

    companion object {
        fun fromId(id: String): PairingSource? = entries.firstOrNull { it.id == id }
    }
}

/**
 * A scanned or opened pairing code: an address to post to and the secret to sign with.
 *
 * Deliberately nothing else. What is synced, and on what schedule, stays a choice made on
 * the phone: a code must never silently start sending heart rate and sleep somewhere.
 */
data class PairingLink(
    val url: String,
    val secret: String,
    /** What to call the receiver in the confirmation dialog, when it said. */
    val name: String?,
    /** Sections this receiver accepts; both when the code did not say. */
    val sources: Set<PairingSource>
) {
    /** Host and port, for the dialog. The user recognises their own machine by this. */
    val host: String
        get() = runCatching { URI(url).let { u -> u.port.let { p -> if (p == -1) u.host else "${u.host}:$p" } } }
            .getOrNull() ?: url

    val isPlainHttp: Boolean get() = url.startsWith("http://", ignoreCase = true)
}

/** The outcome of reading a link: usable, unusable, or not ours at all. */
sealed interface PairingParse {
    data class Ok(val link: PairingLink) : PairingParse

    /** Ours, but we cannot act on it. The reason is shown to the user. */
    data class Invalid(val reason: PairingProblem) : PairingParse

    /** Not a pairing link. The scanner keeps scanning; an intent is ignored. */
    data object NotAPairingLink : PairingParse
}

enum class PairingProblem {
    /** A newer format. The app needs updating, not the user's patience. */
    UnsupportedVersion,

    /** No address, no secret, or an address the app cannot post to. */
    Incomplete,

    /** A receiver that accepts nothing this app can send. */
    NoUsableSource
}

/**
 * Reading the pairing links a receiver hands out.
 *
 * Two shapes carry the same payload: an https App Link, which a phone camera opens
 * directly into the app, and a lifedashboard:// URI, which the landing page and the
 * in-app scanner use. The payload lives in the fragment after the '#', because a browser
 * never sends that to a server (RFC 3986 section 3.5), so the secret stays on the device
 * even when the link goes through a web page.
 *
 * Kept free of android.net.Uri so the whole format is covered by JVM unit tests. The
 * matching generator is pairing.py in the Home Assistant integration, and its test carries
 * the same known-good vector as PairingLinkTest.
 */
object PairingLinks {

    const val PAIR_HOST = "owen282000.github.io"
    const val PAIR_PATH = "/life-dashboard-companion-app/pair"
    const val SCHEME = "lifedashboard"
    const val SCHEME_HOST = "pair"

    /** The only format this build understands. */
    const val VERSION = "1"

    private const val MAX_URL = 2048
    private const val MAX_SECRET = 512
    private const val MAX_NAME = 64

    /**
     * Read a scanned string or an opened link.
     *
     * Returns NotAPairingLink for anything that is not addressed to us, so a scanner can
     * keep looking and an unrelated VIEW intent is left alone.
     */
    fun parse(text: String?): PairingParse {
        val trimmed = text?.trim().orEmpty()
        if (trimmed.isEmpty()) return PairingParse.NotAPairingLink

        // Split the fragment off by hand rather than letting URI do it. A link copied out
        // of a browser's address bar can carry a literal space, which URI refuses outright,
        // and that must not turn a real pairing link into "not ours".
        val hash = trimmed.indexOf('#')
        val address = if (hash >= 0) trimmed.substring(0, hash) else trimmed
        val rawFragment = if (hash >= 0) trimmed.substring(hash + 1) else null

        val uri = runCatching { URI(address) }.getOrNull() ?: return PairingParse.NotAPairingLink
        if (!isOurs(uri)) return PairingParse.NotAPairingLink

        // Split on & and = first, decode after. Decoding first would turn an encoded &
        // inside the URL into a separator.
        val fields = fields(rawFragment ?: return PairingParse.Invalid(PairingProblem.Incomplete))

        if (fields["v"] != VERSION) return PairingParse.Invalid(PairingProblem.UnsupportedVersion)

        val url = fields["url"]?.trim().orEmpty()
        val secret = fields["secret"]?.trim().orEmpty()
        if (!isUsableUrl(url) || !isUsableSecret(secret)) {
            return PairingParse.Invalid(PairingProblem.Incomplete)
        }

        val sources = sources(fields["sources"])
        if (sources.isEmpty()) return PairingParse.Invalid(PairingProblem.NoUsableSource)

        return PairingParse.Ok(
            PairingLink(url = url, secret = secret, name = name(fields["name"]), sources = sources)
        )
    }

    private fun isOurs(uri: URI): Boolean {
        val scheme = uri.scheme?.lowercase()
        return when (scheme) {
            "https" -> uri.host.equals(PAIR_HOST, ignoreCase = true) &&
                uri.path.orEmpty().trimEnd('/').equals(PAIR_PATH, ignoreCase = true)
            SCHEME -> {
                // lifedashboard://pair, and the form without the authority slashes.
                val target = uri.host ?: uri.schemeSpecificPart?.trim('/')
                target.equals(SCHEME_HOST, ignoreCase = true)
            }
            else -> false
        }
    }

    private fun fields(rawFragment: String): Map<String, String> =
        rawFragment.split('&')
            .mapNotNull { part ->
                val index = part.indexOf('=')
                if (index <= 0) return@mapNotNull null
                val key = decode(part.substring(0, index))
                val value = decode(part.substring(index + 1))
                key to value
            }
            .toMap()

    /**
     * Percent-decoding that leaves a literal '+' alone.
     *
     * URLDecoder is built for form bodies, where '+' means a space. A pairing link is not a
     * form: its generator encodes a space as %20 and a '+' in a secret as %2B, but a link
     * copied out of a browser's address bar can come back with a literal space, and a
     * hand-built one with a literal '+'. Both should survive as themselves.
     */
    private fun decode(value: String): String =
        runCatching { URLDecoder.decode(value.replace("+", "%2B"), "UTF-8") }.getOrDefault(value)

    private fun isUsableUrl(url: String): Boolean =
        url.length <= MAX_URL &&
            SettingsRules.isValidUrl(url) &&
            url.none { it.isWhitespace() } &&
            // The URL list is stored comma-joined, so a comma would split one URL into two.
            !url.contains(',')

    private fun isUsableSecret(secret: String): Boolean =
        secret.isNotBlank() && secret.length <= MAX_SECRET && secret.none { it.isWhitespace() }

    private fun name(raw: String?): String? =
        raw?.filter { !it.isISOControl() }?.trim()?.take(MAX_NAME)?.takeIf { it.isNotEmpty() }

    /** Unknown ids are ignored; an absent field means the receiver takes both. */
    private fun sources(raw: String?): Set<PairingSource> {
        if (raw.isNullOrBlank()) return PairingSource.entries.toSet()
        return raw.split(',').mapNotNull { PairingSource.fromId(it.trim()) }.toSet()
    }
}
