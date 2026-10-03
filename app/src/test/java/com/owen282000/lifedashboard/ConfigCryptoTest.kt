package com.owen282000.lifedashboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Password-protected settings exports. An export carries webhook credentials and travels
 * through a share sheet, so the file alone must not be enough to take over someone's endpoints.
 */
class ConfigCryptoTest {

    private val secret = """{"health":{"signing_secret":"hmac-secret"}}"""
    private val password = "correct horse battery staple"

    @Test
    fun decryptsWhatItEncrypted() {
        val envelope = ConfigCrypto.encrypt(secret, password)
        assertEquals(secret, ConfigCrypto.decrypt(envelope, password))
    }

    @Test
    fun plaintextDoesNotAppearInTheEnvelope() {
        val envelope = ConfigCrypto.encrypt(secret, password)
        assertFalse("the secret leaked into the file", envelope.contains("hmac-secret"))
        assertFalse(envelope.contains("signing_secret"))
    }

    @Test
    fun wrongPasswordIsRejectedRatherThanReturningGarbage() {
        val envelope = ConfigCrypto.encrypt(secret, password)
        assertThrows(ConfigCrypto.WrongPasswordException::class.java) {
            ConfigCrypto.decrypt(envelope, "wrong password")
        }
    }

    @Test
    fun tamperedCiphertextIsRejected() {
        val envelope = ConfigCrypto.encrypt(secret, password)
        // Flip a character inside the base64 ciphertext; GCM's tag must catch it.
        val ciphertext = Regex("\"ciphertext\": \"([^\"]+)\"").find(envelope)!!.groupValues[1]
        val flipped = ciphertext.replaceRange(0, 1, if (ciphertext[0] == 'A') "B" else "A")

        assertThrows(ConfigCrypto.WrongPasswordException::class.java) {
            ConfigCrypto.decrypt(envelope.replace(ciphertext, flipped), password)
        }
    }

    @Test
    fun eachExportUsesAFreshSaltAndIv() {
        val first = ConfigCrypto.encrypt(secret, password)
        val second = ConfigCrypto.encrypt(secret, password)

        assertNotEquals("same plaintext must not produce the same file", first, second)
        assertEquals(secret, ConfigCrypto.decrypt(first, password))
        assertEquals(secret, ConfigCrypto.decrypt(second, password))
    }

    @Test
    fun isEncryptedDistinguishesTheTwoExportKinds() {
        assertTrue(ConfigCrypto.isEncrypted(ConfigCrypto.encrypt(secret, password)))
        assertFalse(ConfigCrypto.isEncrypted(ConfigBackup().encode()))
    }

    @Test
    fun aPlainFilePassedAsEncryptedFailsClearly() {
        assertThrows(ConfigCrypto.WrongPasswordException::class.java) {
            ConfigCrypto.decrypt(ConfigBackup().encode(), password)
        }
    }

    @Test
    fun envelopeDeclaresItsParametersSoFutureBuildsCanRead() {
        val envelope = ConfigCrypto.encrypt(secret, password)
        assertTrue(envelope.contains(ConfigCrypto.ENVELOPE_TYPE))
        assertTrue(envelope.contains("\"iterations\": ${ConfigCrypto.ITERATIONS}"))
        assertTrue(envelope.contains("\"salt\""))
        assertTrue(envelope.contains("\"iv\""))
    }

    @Test
    fun anIterationCountOutsideTheAcceptedRangeIsRefusedBeforeAnyKeyIsDerived() {
        val envelope = ConfigCrypto.encrypt(secret, password)
        val written = "\"iterations\": ${ConfigCrypto.ITERATIONS}"
        assertTrue(ConfigCrypto.ITERATIONS in ConfigCrypto.ACCEPTED_ITERATIONS)

        // A crafted file asking for two billion rounds would keep the import busy for hours;
        // one asking for ten would make the password cheap to guess. Both are refused.
        for (iterations in listOf("2000000000", "2000001", "99999", "10", "0", "-1", "lots")) {
            assertThrows(iterations, ConfigCrypto.UnsupportedEnvelopeException::class.java) {
                ConfigCrypto.decrypt(envelope.replace(written, "\"iterations\": $iterations"), password)
            }
        }
    }

    @Test
    fun anExportPasswordNeedsEightCharactersAndTheSameRepeat() {
        assertEquals(ConfigCrypto.PasswordProblem.TOO_SHORT, ConfigCrypto.passwordProblem("", ""))
        assertEquals(ConfigCrypto.PasswordProblem.TOO_SHORT, ConfigCrypto.passwordProblem("seven77", "seven77"))
        assertEquals(ConfigCrypto.PasswordProblem.MISMATCH, ConfigCrypto.passwordProblem("eight888", "eight88"))
        assertEquals(ConfigCrypto.PasswordProblem.MISMATCH, ConfigCrypto.passwordProblem("four random words", ""))
        assertEquals(null, ConfigCrypto.passwordProblem("eight888", "eight888"))
        // Counted in characters, not UTF-16 units: four emoji are four characters, not eight.
        assertEquals(ConfigCrypto.PasswordProblem.TOO_SHORT, ConfigCrypto.passwordProblem("🔐🔐🔐🔐", "🔐🔐🔐🔐"))
    }

    @Test
    fun handlesUnicodeAndLargeConfigs() {
        val tricky = """{"note":"emoji 🔐 en accenten éàü","big":"${"x".repeat(50_000)}"}"""
        val envelope = ConfigCrypto.encrypt(tricky, "wachtwoord")
        assertEquals(tricky, ConfigCrypto.decrypt(envelope, "wachtwoord"))
    }

    @Test
    fun encryptsAFullBackupEndToEnd() {
        val backup = ConfigBackup(
            health = SectionConfig(
                webhookUrls = listOf("https://example.com/h"),
                signingSecret = "s3cret"
            )
        )
        val envelope = ConfigCrypto.encrypt(backup.encode(), password)
        assertEquals(backup, ConfigBackup.decode(ConfigCrypto.decrypt(envelope, password)))
    }
}
