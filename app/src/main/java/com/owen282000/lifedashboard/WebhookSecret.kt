package com.owen282000.lifedashboard

import java.security.SecureRandom

/**
 * Generates an HMAC signing secret.
 *
 * The field is optional and starts empty, which in practice means people either leave signing
 * off or type something short and memorable. A generated secret removes that choice: 32 bytes
 * from [SecureRandom], hex encoded, is 256 bits of entropy, matching the SHA-256 the signature
 * uses. A hand-picked passphrase is typically under 40 bits.
 *
 * Hex rather than base64 so the value survives being pasted into a shell, an environment file
 * or a YAML config without quoting or escaping, which is where these secrets end up.
 */
object WebhookSecret {

    /** Bytes of entropy. 32 matches the output size of SHA-256; more adds nothing. */
    private const val SECRET_BYTES = 32

    fun generate(random: SecureRandom = SecureRandom()): String {
        val bytes = ByteArray(SECRET_BYTES)
        random.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
