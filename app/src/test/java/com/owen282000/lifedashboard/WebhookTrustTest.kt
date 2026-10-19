package com.owen282000.lifedashboard

import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.X509TrustManager

/**
 * Server trust per webhook host (issue #160), against real HTTPS servers: a certificate from a
 * private CA is refused until the user CAs count for its host, a self-signed one until its
 * fingerprint is confirmed, and a confirmed fingerprint accepts that certificate and no other.
 */
class WebhookTrustTest {

    private val server = MockWebServer()
    private val other = MockWebServer()

    @After
    fun stop() {
        server.close()
        other.close()
    }

    private val privateCa = HeldCertificate.Builder().certificateAuthority(0).commonName("Home CA").build()

    private fun signedBy(ca: HeldCertificate, vararg names: String) = HeldCertificate.Builder()
        .signedBy(ca).apply { names.forEach { addSubjectAlternativeName(it) } }.build()

    private fun selfSigned(vararg names: String) = HeldCertificate.Builder()
        .commonName("nas").apply { names.forEach { addSubjectAlternativeName(it) } }.build()

    private fun MockWebServer.serve(certificate: HeldCertificate, vararg chain: X509Certificate) {
        useHttps(HandshakeCertificates.Builder().heldCertificate(certificate, *chain).build().sslSocketFactory())
        // One address, so a refused handshake is the failure a test sees, not a second try on ::1.
        start(InetAddress.getByName("127.0.0.1"), 0)
    }

    /** Android's CA store as the test sees it: the private CA installed by the user. */
    private fun userStore(): KeyStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
        load(null)
        setCertificateEntry("user:home-ca", privateCa.certificate)
    }

    private fun manager(url: String, trust: Map<String, ServerTrust>) = WebhookManager(
        destinations = destinationsOf(listOf(url)),
        serverTrustOverride = trust,
        trustManagerFor = { WebhookTrust.trustManager(it, userCaStore = ::userStore) }
    )

    private val MockWebServer.https get() = url("/hook").newBuilder().host("127.0.0.1").build().toString()
    private val MockWebServer.key get() = WebhookTrust.hostKey(https)!!

    // MARK: - Hosts and storage

    @Test
    fun aHostKeyIsTheHttpsHostAndItsPort() {
        assertEquals("ha.example:443", WebhookTrust.hostKey("https://HA.example/api/webhook/x"))
        assertEquals("10.0.0.2:8123", WebhookTrust.hostKey("https://10.0.0.2:8123/hook"))
        assertNull(WebhookTrust.hostKey("http://ha.example/hook"))
        assertNull(WebhookTrust.hostKey("not a url"))
        assertEquals(
            listOf("a.example:443", "b.example:8443"),
            WebhookTrust.hostKeys(listOf("https://a.example/1", "https://a.example/2", "https://b.example:8443/", "http://c.example/"))
        )
        assertEquals("a.example", WebhookTrust.displayName("a.example:443"))
        assertEquals("b.example:8443", WebhookTrust.displayName("b.example:8443"))
    }

    @Test
    fun onlyHostsWithTrustAreStored() {
        val stored = WebhookTrust.encode(
            mapOf("a:443" to ServerTrust(userCas = true), "b:443" to ServerTrust(), "c:443" to ServerTrust(pinSha256 = "AB:CD"))
        )
        assertEquals(mapOf("a:443" to ServerTrust(userCas = true), "c:443" to ServerTrust(pinSha256 = "AB:CD")), WebhookTrust.decode(stored))
        assertEquals(emptyMap<String, ServerTrust>(), WebhookTrust.decode("not json"))
        assertEquals(emptyMap<String, ServerTrust>(), WebhookTrust.decode(null))
    }

    // MARK: - A private CA

    @Test
    fun aPrivateCaIsRefusedUntilTheUserCasCountForItsHost() = runTest {
        server.serve(signedBy(privateCa, "localhost", "127.0.0.1"), privateCa.certificate)
        server.enqueue(MockResponse.Builder().code(200).build())

        assertTrue("no trust: refused", manager(server.https, emptyMap()).postData("{}").isFailure)
        assertEquals(0, server.requestCount)

        val trusted = manager(server.https, mapOf(server.key to ServerTrust(userCas = true))).postData("{}")
        assertTrue(trusted.exceptionOrNull()?.toString(), trusted.isSuccess)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun theUserCasCountOnlyForTheHostTheyWereSwitchedOnFor() = runTest {
        server.serve(signedBy(privateCa, "localhost", "127.0.0.1"), privateCa.certificate)
        other.serve(signedBy(privateCa, "localhost", "127.0.0.1"), privateCa.certificate)
        other.enqueue(MockResponse.Builder().code(200).build())

        // Trust is keyed by host and port, so the other server is still checked against the system CAs.
        val result = manager(other.https, mapOf(server.key to ServerTrust(userCas = true))).postData("{}")
        assertTrue(result.isFailure)
        assertEquals(0, other.requestCount)
    }

    // MARK: - A confirmed certificate

    @Test
    fun aSelfSignedCertificateIsAcceptedOnceItsFingerprintIsConfirmed() = runTest {
        val nas = selfSigned("localhost", "127.0.0.1")
        server.serve(nas)
        server.enqueue(MockResponse.Builder().code(200).build())

        assertTrue(manager(server.https, emptyMap()).postData("{}").isFailure)
        val pinned = manager(server.https, mapOf(server.key to ServerTrust(pinSha256 = WebhookTrust.sha256(nas.certificate))))
        val result = pinned.postData("{}")
        assertTrue(result.exceptionOrNull()?.toString(), result.isSuccess)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun aConfirmedCertificateAlsoCoversAHostNameItDoesNotCarry() = runTest {
        // A NAS with a certificate for nas.lan, reached by another name or its address.
        val nas = selfSigned("nas.lan")
        server.serve(nas)
        server.enqueue(MockResponse.Builder().code(200).build())

        val result = manager(server.https, mapOf(server.key to ServerTrust(pinSha256 = WebhookTrust.sha256(nas.certificate)))).postData("{}")
        assertTrue(result.exceptionOrNull()?.toString(), result.isSuccess)
    }

    @Test
    fun anotherCertificateThanTheConfirmedOneIsRefusedAndSaysSo() = runTest {
        server.serve(selfSigned("localhost", "127.0.0.1"))
        val confirmedEarlier = selfSigned("localhost", "127.0.0.1")

        val result = manager(server.https, mapOf(server.key to ServerTrust(pinSha256 = WebhookTrust.sha256(confirmedEarlier.certificate))))
            .postData("{}")
        assertTrue(result.isFailure)
        assertEquals(0, server.requestCount)
        assertTrue(result.exceptionOrNull()?.toString(), generateSequence(result.exceptionOrNull()) { it.cause }.any { it.message?.contains("not the one you confirmed") == true })
    }

    @Test
    fun thePinningTrustManagerStillValidatesAndRefusesEverythingElse() {
        val good = selfSigned("a").certificate
        val bad = selfSigned("b").certificate
        val refusesAll = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = throw CertificateException("untrusted")
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val pinning = WebhookTrust.PinningTrustManager(refusesAll, WebhookTrust.sha256(good).lowercase())
        pinning.checkServerTrusted(arrayOf(good), "RSA")
        assertThrows(CertificateException::class.java) { pinning.checkServerTrusted(arrayOf(bad), "RSA") }
        assertThrows(CertificateException::class.java) { pinning.checkServerTrusted(emptyArray(), "RSA") }
    }

    // MARK: - Reading a certificate for confirmation

    @Test
    fun readingTheCertificateSendsNothingAndShowsItsFingerprint() {
        val nas = selfSigned("localhost", "127.0.0.1")
        server.serve(nas)

        val read = WebhookTrust.fetchCertificate(server.key)

        assertEquals(WebhookTrust.sha256(nas.certificate), read.sha256)
        assertTrue(read.selfSigned)
        assertTrue(read.subject, read.subject.contains("nas"))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun aCertificateFromACaIsNotShownAsSelfSigned() {
        server.serve(signedBy(privateCa, "localhost", "127.0.0.1"), privateCa.certificate)
        val read = WebhookTrust.fetchCertificate(server.key)
        assertFalse(read.selfSigned)
        assertTrue(read.issuer, read.issuer.contains("Home CA"))
    }

    @Test
    fun theFingerprintIsUpperCaseHexPairs() {
        val fingerprint = WebhookTrust.sha256(selfSigned("a").certificate)
        assertTrue(fingerprint, Regex("([0-9A-F]{2}:){31}[0-9A-F]{2}").matches(fingerprint))
    }
}
