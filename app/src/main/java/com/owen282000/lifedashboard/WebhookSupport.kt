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
     * The custom headers one URL gets. A section's headers were typed for the URLs the user
     * entered by hand; a URL that QR pairing added is in [urlsWithoutHeaders] and gets none,
     * so a pairing link to a foreign host, even one the user confirmed, never receives the
     * API keys configured for the others.
     */
    fun headersFor(url: String, headers: Map<String, String>, urlsWithoutHeaders: Set<String>): Map<String, String> =
        if (url in urlsWithoutHeaders) emptyMap() else headers

    /** Redirects one delivery follows at most, all on the same host. */
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
