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
    fun signature(payload: String, secret: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val digest = mac.doFinal(payload.toByteArray(Charsets.UTF_8))
        return "sha256=" + digest.joinToString("") { "%02x".format(it) }
    }

    /**
     * Whether a failed delivery attempt is worth retrying. Network-level failures (no HTTP
     * status) and transient statuses are; client errors like 401 or 404 will not change on
     * retry and only delay the sync.
     */
    fun isRetryable(statusCode: Int?): Boolean {
        if (statusCode == null) return true
        return statusCode == 408 || statusCode == 429 || statusCode in 500..599
    }

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
