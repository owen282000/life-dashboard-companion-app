package com.owen282000.lifedashboard

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Pure webhook helpers, kept free of Android/OkHttp types so they can be unit tested. */
object WebhookSupport {

    const val SIGNATURE_HEADER = "X-Signature"

    /**
     * HMAC-SHA256 signature header value for a payload: "sha256=<lowercase hex>". Receivers
     * verify by recomputing the HMAC over the raw request body with the shared secret and
     * comparing it (constant-time) against this header.
     */
    fun signature(payload: String, secret: String): String =
        "sha256=" + hex(hmac(secret.toByteArray(Charsets.UTF_8), payload.toByteArray(Charsets.UTF_8)))

    /**
     * The label the response key is derived under (write-back protocol v1, issue #62). The
     * integration signs its response with HMAC-SHA256 under a key that is itself
     * HMAC-SHA256(secret, this label), never under the secret directly, so a request the app
     * signed can never be played back to it as a response: the two directions use different
     * keys, and only one of them is ever seen on the wire in each direction.
     */
    const val RESPONSE_KEY_LABEL = "life-dashboard-response-v1"

    /** The 32 raw bytes the response direction is keyed with. */
    fun responseKey(secret: String): ByteArray =
        hmac(secret.toByteArray(Charsets.UTF_8), RESPONSE_KEY_LABEL.toByteArray(Charsets.UTF_8))

    /** What the X-Signature header on a response must equal, computed over the raw body bytes. */
    fun responseSignature(body: ByteArray, secret: String): String = "sha256=" + hex(hmac(responseKey(secret), body))

    /**
     * Constant-time comparison of two signature strings, so a byte-by-byte mismatch cannot be
     * timed. A missing header never matches anything.
     */
    fun signaturesMatch(presented: String?, expected: String): Boolean {
        if (presented == null) return false
        return java.security.MessageDigest.isEqual(
            presented.toByteArray(Charsets.UTF_8),
            expected.toByteArray(Charsets.UTF_8)
        )
    }

    private fun hmac(key: ByteArray, message: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(message)
    }

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    /**
     * Whether a failed delivery attempt is worth retrying. Network-level failures (no HTTP
     * status) and transient statuses are; client errors like 401 or 404 will not change on
     * retry and only delay the sync.
     */
    fun isRetryable(statusCode: Int?): Boolean {
        if (statusCode == null) return true
        return statusCode == 408 || statusCode == 429 || statusCode in 500..599
    }

    /**
     * Whether a status says the receiver refuses this payload itself rather than every
     * payload: 400, 413 and 422. The outbox drain skips such a payload instead of stopping
     * behind it, so it cannot hold back what was queued after it (F6 of P2-4); it stays queued
     * for a week, since a bug on the receiving side can answer 400 too (see [PendingDrainer]).
     *
     * Every other refusal is about the receiver's setup and would refuse the next payload the
     * same way: a wrong or missing key (401, 403, 407), or a webhook that is gone or switched
     * off (404, 405, 410, which is what n8n answers for an inactive workflow). The drain stops
     * at those like at an outage, and a correction in the settings delivers the whole queue.
     */
    fun refusesPayload(statusCode: Int?): Boolean = statusCode == 400 || statusCode == 413 || statusCode == 422

    /**
     * The custom headers one URL got in 1.27.0 and older, where a section had one set: the
     * ones typed for the URLs the user entered by hand, and none for a URL that QR pairing
     * added ([urlsWithoutHeaders]). The destinations derived after the update keep exactly
     * that (see [Destinations.derive]); since then each destination has headers of its own.
     */
    fun headersFor(url: String, headers: Map<String, String>, urlsWithoutHeaders: Set<String>): Map<String, String> =
        if (url in urlsWithoutHeaders) emptyMap() else headers

    /** The characters an HTTP token may hold besides letters and digits (tchar, RFC 9110 section 5.6.2). */
    private const val TOKEN_SYMBOLS = "!#$%&'*+-.^_`|~"

    /**
     * Whether [name] can go out as a header name: a non-empty HTTP token, so no spaces, colons
     * or anything outside ASCII. OkHttp refuses a name with a space or a control character when
     * the request is built, which made every delivery fail; a token is a subset of what it takes.
     */
    fun isValidHeaderName(name: String): Boolean =
        name.isNotEmpty() && name.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it in TOKEN_SYMBOLS }

    /**
     * Whether [value] can go out as a header value: what OkHttp accepts, printable ASCII and
     * tabs. A line break could start a header of its own, and OkHttp refuses it, a control
     * character or a letter like é outright.
     */
    fun isValidHeaderValue(value: String): Boolean = value.all { it == '\t' || it in ' '..'~' }

    /**
     * A custom header that cannot be sent: its [name], and whether it is the value that is
     * wrong. The value itself is never part of it, since a custom header usually carries a key.
     */
    data class HeaderProblem(val name: String, val inValue: Boolean) {
        /** [name] as it can be shown on one line: a control character, such as a line break, as a question mark. */
        val shownName: String
            get() = name.map { if (it.isISOControl()) '?' else it }.joinToString("")

        /** The log line and failure reason; FailureReason keeps the part before the full stop. */
        val message: String
            get() {
                val what = if (inValue) "Invalid value in header \"$shownName\"" else "Invalid header name \"$shownName\""
                return "$what, so nothing was sent. Remove the header in the webhook settings and add it again"
            }
    }

    /** The first header in [headers] that cannot be sent, or null when all of them can. */
    fun headerProblem(headers: Map<String, String>): HeaderProblem? = headers.entries.firstNotNullOfOrNull { (name, value) ->
        when {
            !isValidHeaderName(name) -> HeaderProblem(name, inValue = false)
            !isValidHeaderValue(value) -> HeaderProblem(name, inValue = true)
            else -> null
        }
    }

    /** [headers] without the ones that cannot be sent, see [headerProblem]. */
    fun sendableHeaders(headers: Map<String, String>): Map<String, String> =
        headers.filter { (name, value) -> isValidHeaderName(name) && isValidHeaderValue(value) }

    /** Redirects one delivery follows at most, all on the same host (Apps Script aside, see [appsScriptAnswer]). */
    const val MAX_REDIRECTS = 5

    /**
     * Where a delivery to [from] follows a redirect to [location], or null when it must not.
     * The body, the signature and the custom headers go along, so only the same host is
     * followed: the same port, or http on port 80 moving up to https on 443. Another host, a
     * step down from https to http, or plain http without the opt-in is not, because that
     * would reach an address the user never entered, past the checks made on the one they did.
     * [location] may be relative, as the Location header allows.
     */
    fun followableRedirect(from: String, location: String?, allowHttp: Boolean): String? {
        if (location.isNullOrBlank()) return null
        val base = runCatching { java.net.URI(from.trim()) }.getOrNull() ?: return null
        val target = runCatching { base.resolve(location.trim()) }.getOrNull() ?: return null
        val fromScheme = base.scheme?.lowercase() ?: return null
        val toScheme = target.scheme?.lowercase() ?: return null
        if (toScheme != "http" && toScheme != "https") return null
        val host = target.host ?: return null
        if (!host.equals(base.host, ignoreCase = true)) return null
        if (fromScheme == "https" && toScheme == "http") return null
        val fromPort = effectivePort(base.port, fromScheme)
        val toPort = effectivePort(target.port, toScheme)
        val upgrade = fromScheme == "http" && toScheme == "https" && fromPort == 80 && toPort == 443
        if (fromPort != toPort && !upgrade) return null
        val resolved = target.toString()
        return if (cleartextBlockReason(resolved, allowHttp) == null) resolved else null
    }

    private fun effectivePort(port: Int, scheme: String): Int =
        if (port != -1) port else if (scheme == "https") 443 else 80

    /**
     * The one redirect to another host a delivery accepts: a Google Apps Script web app. Its
     * `/exec` URL runs doPost on the POST itself and then answers 302 with the script's output
     * parked on [answerHost]. By then the payload has arrived, so nothing is sent again: the
     * answer is read with a plain GET, without the body, the signature or the custom headers.
     * [scheme] and the hosts are parameters only so a test can stand in for Google; a delivery
     * always uses [GOOGLE].
     */
    data class AppsScriptRule(val scheme: String, val scriptHost: String, val answerHost: String) {
        companion object {
            val GOOGLE = AppsScriptRule("https", "script.google.com:443", "script.googleusercontent.com:443")
        }
    }

    /**
     * Where a delivery to [from] that got [statusCode] reads the Apps Script answer, or null when
     * this is not that redirect. Only a 301, 302 or 303 (the ones that become a GET) from a
     * `/macros/` path on the script host, to a `/macros/` path on the answer host, over
     * [AppsScriptRule.scheme] on its usual port. A redirect to a sign-in page, to another Google
     * host or anywhere else is not this, and fails the delivery as before.
     */
    fun appsScriptAnswer(from: String, statusCode: Int, location: String?, rule: AppsScriptRule = AppsScriptRule.GOOGLE): String? {
        if (statusCode !in setOf(301, 302, 303) || location.isNullOrBlank()) return null
        val base = runCatching { java.net.URI(from.trim()) }.getOrNull() ?: return null
        val target = runCatching { base.resolve(location.trim()) }.getOrNull() ?: return null
        if (!isAppsScriptWebApp(from, rule) || !isAt(target, rule.scheme, rule.answerHost)) return null
        if (target.rawPath?.startsWith("/macros/") != true) return null
        if (target.rawUserInfo != null) return null
        return target.toString()
    }

    /**
     * Whether [url] is a Google Apps Script web app: a `/macros/` path on the script host, also
     * a Google Workspace one (`/a/macros/<domain>/s/<id>/exec`). The one rule for both the
     * redirect it answers with and the token it may carry in its query (Destinations.credentialIn).
     */
    fun isAppsScriptWebApp(url: String, rule: AppsScriptRule = AppsScriptRule.GOOGLE): Boolean {
        val uri = runCatching { java.net.URI(url.trim()) }.getOrNull() ?: return false
        return isAt(uri, rule.scheme, rule.scriptHost) && uri.rawPath?.contains("/macros/") == true
    }

    private fun isAt(uri: java.net.URI, scheme: String, hostAndPort: String): Boolean {
        val uriScheme = uri.scheme?.lowercase() ?: return false
        val host = uri.host ?: return false
        if (uriScheme != scheme) return false
        return "${host.lowercase()}:${effectivePort(uri.port, uriScheme)}" == hostAndPort.lowercase()
    }

    /** The log note on a delivery Apps Script took; [answer] says how reading its answer went. */
    fun appsScriptNote(answerHost: String, answer: String?): String =
        if (answer == null) {
            "Google Apps Script took the payload; its answer came from $answerHost, the one other host the app follows"
        } else {
            "Google Apps Script took the payload; reading its answer from $answerHost failed ($answer), which does not undo the delivery"
        }

    /**
     * The log line for an Apps Script URL that sent the POST to Google's sign-in page: the web
     * app is deployed for signed-in users only, which a phone cannot be.
     */
    fun appsScriptSignInMessage(statusCode: Int): String =
        "HTTP $statusCode: Google asked for a sign-in, so nothing reached the script. Deploy the Apps Script web app with \"Who has access\" set to Anyone."

    /** True when [from] is an Apps Script web app and [targetHost] is Google's sign-in page. */
    fun isAppsScriptSignIn(from: String, targetHost: String?): Boolean {
        val base = runCatching { java.net.URI(from.trim()) }.getOrNull() ?: return false
        return base.host.equals("script.google.com", ignoreCase = true) &&
            targetHost.equals("accounts.google.com", ignoreCase = true)
    }

    /**
     * The log line for a 3xx that was not followed (see [followableRedirect]). Names the host
     * the redirect pointed at, so the user can put the final address in the settings.
     */
    fun redirectMessage(statusCode: Int, targetHost: String?): String {
        val target = targetHost?.let { "to $it " } ?: ""
        return "HTTP $statusCode: redirect ${target}not followed, so nothing was sent there. Only a redirect on the same host is followed; enter the final address as the webhook URL."
    }

    /** The note on a delivery that arrived after a redirect, so the user can skip the extra request. */
    fun redirectNote(target: String): String =
        "Redirected to $target; enter that address as the webhook URL to skip the extra request"

    const val CLEARTEXT_BLOCKED_MESSAGE =
        "Plain HTTP is blocked. Enable \"Allow plain HTTP webhooks\" in the app for endpoints on a private LAN or VPN, or use HTTPS."

    /**
     * Why a URL must not be posted to, or null when it may. Cleartext is permitted at the
     * platform level (see network_security_config.xml) so the decision lives here, where it
     * is testable: http:// is only allowed after the user opted in (issue #51).
     */
    fun cleartextBlockReason(url: String, allowHttp: Boolean): String? {
        val isHttp = url.trim().startsWith("http://", ignoreCase = true)
        return if (isHttp && !allowHttp) CLEARTEXT_BLOCKED_MESSAGE else null
    }
}
