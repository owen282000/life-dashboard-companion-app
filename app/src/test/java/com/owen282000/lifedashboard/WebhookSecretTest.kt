package com.owen282000.lifedashboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom

/**
 * A generated secret is only worth offering if it is actually strong and actually usable in the
 * places these values get pasted.
 */
class WebhookSecretTest {

    @Test
    fun `a secret is 32 bytes of entropy, hex encoded`() {
        val secret = WebhookSecret.generate()
        assertEquals(64, secret.length)
        assertTrue(secret.all { it in "0123456789abcdef" })
    }

    @Test
    fun `secrets differ every time`() {
        val secrets = (1..100).map { WebhookSecret.generate() }
        assertEquals(100, secrets.distinct().size)
    }

    @Test
    fun `the bytes come from the source given, so the generation is testable`() {
        // Same seed, same secret: proves the randomness is not drawn from somewhere hidden.
        val a = WebhookSecret.generate(SecureRandom.getInstance("SHA1PRNG").apply { setSeed(42L) })
        val b = WebhookSecret.generate(SecureRandom.getInstance("SHA1PRNG").apply { setSeed(42L) })
        assertEquals(a, b)
        assertNotEquals(a, WebhookSecret.generate(SecureRandom.getInstance("SHA1PRNG").apply { setSeed(43L) }))
    }

    @Test
    fun `a secret survives a shell, a dotenv file and YAML without quoting`() {
        // Hex has no +, / or = to escape, which is the reason not to use base64 here.
        val secret = WebhookSecret.generate()
        assertTrue(secret.none { it in "+/=\$'\"`\\ " })
    }
}
