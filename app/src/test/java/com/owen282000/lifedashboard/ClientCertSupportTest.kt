package com.owen282000.lifedashboard

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import javax.security.auth.x500.X500Principal
import kotlin.concurrent.thread

/**
 * Client certificate (mTLS). KeyChain itself needs a device, so these tests cover what sits on
 * top of it: the key manager that always presents the picked certificate, and a real TLS
 * handshake against a server that demands a client certificate.
 *
 * client-cert-test.p12 (password "testpass") holds two self-signed test-only EC keys:
 * "client" and "server" (CN=localhost, SAN 127.0.0.1).
 */
class ClientCertSupportTest {

    private val keyStore = KeyStore.getInstance("PKCS12").apply {
        ClientCertSupportTest::class.java.classLoader!!.getResourceAsStream("client-cert-test.p12").use { load(it, PASSWORD) }
    }

    private fun key(alias: String) = keyStore.getKey(alias, PASSWORD) as PrivateKey
    private fun chain(alias: String) = keyStore.getCertificateChain(alias).map { it as X509Certificate }.toTypedArray()
    private fun cert(alias: String) = keyStore.getCertificate(alias) as X509Certificate

    /** A trust manager that trusts exactly one self-signed certificate. */
    private fun trusting(cert: X509Certificate): X509TrustManager {
        val store = KeyStore.getInstance("PKCS12").apply {
            load(null, null)
            setCertificateEntry("trusted", cert)
        }
        val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        factory.init(store)
        return factory.trustManagers.filterIsInstance<X509TrustManager>().first()
    }

    private fun keyManager() = ClientCertSupport.FixedKeyManager("my-cert", key("client"), chain("client"))

    @Test
    fun `the picked certificate is offered whatever the server asks for`() {
        val km = keyManager()
        val otherIssuer = arrayOf(X500Principal("CN=Some Other CA"))
        assertEquals("my-cert", km.chooseClientAlias(arrayOf("RSA"), otherIssuer, null))
        assertEquals("my-cert", km.chooseEngineClientAlias(arrayOf("EC"), null, null))
        assertArrayEquals(arrayOf("my-cert"), km.getClientAliases("RSA", otherIssuer))
    }

    @Test
    fun `the key and chain are only handed out under the picked alias`() {
        val key = key("client")
        val chain = chain("client")
        val km = ClientCertSupport.FixedKeyManager("my-cert", key, chain)
        assertSame(key, km.getPrivateKey("my-cert"))
        assertSame(chain, km.getCertificateChain("my-cert"))
        assertNull(km.getPrivateKey("other"))
        assertNull(km.getCertificateChain(null))
    }

    @Test
    fun `it never acts as a server`() {
        val km = keyManager()
        assertNull(km.getServerAliases("EC", null))
        assertNull(km.chooseServerAlias("EC", null, null))
    }

    @Test
    fun `a server that requires a client certificate receives the picked one`() {
        val serverContext = SSLContext.getInstance("TLS").apply {
            val serverOnly = KeyStore.getInstance("PKCS12").apply {
                load(null, null)
                setKeyEntry("server", key("server"), PASSWORD, chain("server"))
            }
            val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
            kmf.init(serverOnly, PASSWORD)
            init(kmf.keyManagers, arrayOf(trusting(cert("client"))), null)
        }
        val loopback = InetAddress.getLoopbackAddress()
        val server = serverContext.serverSocketFactory.createServerSocket(0, 1, loopback) as SSLServerSocket
        server.needClientAuth = true

        val presented = CompletableFuture<X509Certificate>()
        thread(isDaemon = true) {
            try {
                (server.accept() as SSLSocket).use { socket ->
                    socket.startHandshake()
                    presented.complete(socket.session.peerCertificates.first() as X509Certificate)
                    socket.outputStream.write(1)
                    socket.outputStream.flush()
                }
            } catch (e: Exception) {
                presented.completeExceptionally(e)
            }
        }

        server.use {
            val setup = ClientCertSupport.sslSetup("my-cert", key("client"), chain("client"), trusting(cert("server")))
            (setup.socketFactory.createSocket(loopback, server.localPort) as SSLSocket).use { client ->
                client.startHandshake()
                assertEquals(1, client.inputStream.read())
            }
            assertEquals(cert("client"), presented.get(10, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `the unavailable message names the alias and where to fix it`() {
        val message = ClientCertSupport.unavailableMessage("home-cert")
        assertTrue(message.contains("'home-cert'"))
        assertTrue(message.contains("Advanced settings"))
    }

    private companion object {
        val PASSWORD = "testpass".toCharArray()
    }
}
