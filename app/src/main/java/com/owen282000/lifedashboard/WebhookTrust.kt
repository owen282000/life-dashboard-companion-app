package com.owen282000.lifedashboard

import android.annotation.SuppressLint
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.internal.tls.OkHostnameVerifier
import java.io.IOException
import java.net.InetSocketAddress
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.Date
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.KeyManager
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSession
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * What a webhook host is trusted with beyond the system CAs (issue #160). Both are off by
 * default and set per host, never for the whole app:
 *
 * - [userCas]: the CA certificates installed on the phone count for this host too, for a server
 *   whose certificate comes from a private CA. Android ignores them by default for an app that
 *   targets API 24 or later, and network_security_config.xml cannot name a host the user types in.
 * - [pinSha256]: the SHA-256 of the one certificate the user confirmed for this host, for a
 *   self-signed server. Only exactly that certificate is accepted on top of normal validation.
 *
 * There is no "ignore TLS errors": a trust manager that accepts every certificate lets anyone on
 * the path read the health data, and Google Play flags one in the app whether or not a setting
 * turns it on. Every trust manager here still throws when nothing it trusts matches.
 */
@Serializable
data class ServerTrust(
    @SerialName("user_cas") val userCas: Boolean = false,
    @SerialName("pin_sha256") val pinSha256: String? = null
) {
    val isDefault: Boolean get() = !userCas && pinSha256 == null
}

/** The certificate a server presented, as the confirmation dialog shows it. */
data class ServerCertificate(
    val subject: String,
    val issuer: String,
    val notAfter: Date,
    val sha256: String,
    val selfSigned: Boolean
)

object WebhookTrust {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * The key trust is stored under: `host:port` of an https URL, the host in lower case and the
     * port written out, or null for anything else (plain http has no certificate to trust).
     */
    fun hostKey(url: String): String? {
        val uri = runCatching { java.net.URI(url.trim()) }.getOrNull() ?: return null
        if (!uri.scheme.equals("https", ignoreCase = true)) return null
        val host = uri.host?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
        val port = if (uri.port == -1) 443 else uri.port
        return "$host:$port"
    }

    /** The https hosts among [urls], each once, in the order they first appear. */
    fun hostKeys(urls: List<String>): List<String> = urls.mapNotNull(::hostKey).distinct()

    /** A host key as the settings show it: the port only when it is not 443. */
    fun displayName(hostKey: String): String = hostKey.removeSuffix(":443")

    fun encode(trust: Map<String, ServerTrust>): String =
        json.encodeToString(trust.filterValues { !it.isDefault })

    fun decode(stored: String?): Map<String, ServerTrust> =
        stored?.let { runCatching { json.decodeFromString<Map<String, ServerTrust>>(it) }.getOrNull() } ?: emptyMap()

    /** The SHA-256 of a certificate's DER encoding, as upper-case hex pairs with colons. */
    fun sha256(certificate: X509Certificate): String =
        MessageDigest.getInstance("SHA-256").digest(certificate.encoded)
            .joinToString(":") { "%02X".format(it) }

    /** The platform's own trust: the system CAs, as network_security_config.xml sets it. */
    fun platformTrustManager(): X509TrustManager = trustManagerFor(null)

    internal fun trustManagerFor(keyStore: KeyStore?): X509TrustManager {
        val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        factory.init(keyStore)
        return factory.trustManagers.filterIsInstance<X509TrustManager>().first()
    }

    /**
     * Android's CA store: the system CAs and the ones the user installed under Settings, Security,
     * Encryption and credentials. Read as a key store of its own, so the user CAs count only where
     * [trustManager] puts them, not for every connection of the app.
     */
    private fun androidCaStore(): KeyStore = KeyStore.getInstance("AndroidCAStore").apply { load(null) }

    /**
     * The trust manager for one host: the platform's, or one that also trusts the user CAs, with
     * the pinned certificate accepted on top. [userCaStore] is a parameter so a test can stand in
     * for Android's store.
     */
    fun trustManager(
        trust: ServerTrust?,
        platform: () -> X509TrustManager = ::platformTrustManager,
        userCaStore: () -> KeyStore = ::androidCaStore
    ): X509TrustManager {
        val base = if (trust?.userCas == true) trustManagerFor(userCaStore()) else platform()
        val pin = trust?.pinSha256 ?: return base
        return PinningTrustManager(base, pin)
    }

    /**
     * Normal validation first; when that fails, the one certificate the user confirmed. Anything
     * else still fails, with a message that says what changed, so a renewed self-signed
     * certificate asks to be confirmed again instead of being accepted.
     */
    // Not the insecure kind lint warns about: it calls the platform's validation first and
    // throws unless the one confirmed certificate is presented (WebhookTrustTest).
    @SuppressLint("CustomX509TrustManager")
    class PinningTrustManager(private val delegate: X509TrustManager, private val pinSha256: String) : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) =
            delegate.checkClientTrusted(chain, authType)

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            try {
                delegate.checkServerTrusted(chain, authType)
            } catch (e: CertificateException) {
                val leaf = chain?.firstOrNull() ?: throw e
                if (!leaf.matchesPin()) throw CertificateException(pinMismatchMessage(sha256(leaf)), e)
                // The confirmed certificate itself; its validity period is not checked, as the
                // user confirmed this exact certificate rather than a CA's word for it.
            }
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = delegate.acceptedIssuers

        private fun X509Certificate.matchesPin() = sha256(this).equals(pinSha256, ignoreCase = true)

        /** The host name check for this host: the normal one, or the confirmed certificate. */
        fun hostnameVerifier(): HostnameVerifier = HostnameVerifier { host, session ->
            OkHostnameVerifier.verify(host, session) || session.leaf()?.matchesPin() == true
        }

        private fun SSLSession.leaf(): X509Certificate? =
            runCatching { peerCertificates.firstOrNull() as? X509Certificate }.getOrNull()
    }

    fun pinMismatchMessage(presented: String) =
        "The server's certificate is not the one you confirmed (it now has SHA-256 $presented). Confirm it again under Advanced, Server certificates, if you renewed it."

    /**
     * Applies [trust] for one host to [builder], with the client certificate's key managers when
     * there is one. Leaves the builder alone when there is nothing to change.
     */
    fun apply(
        builder: OkHttpClient.Builder,
        trust: ServerTrust?,
        keyManagers: Array<KeyManager>? = null,
        trustManager: X509TrustManager = trustManager(trust)
    ): OkHttpClient.Builder {
        if ((trust == null || trust.isDefault) && keyManagers == null) return builder
        val context = SSLContext.getInstance("TLS")
        context.init(keyManagers, arrayOf(trustManager), null)
        builder.sslSocketFactory(context.socketFactory, trustManager)
        if (trustManager is PinningTrustManager) builder.hostnameVerifier(trustManager.hostnameVerifier())
        return builder
    }

    /**
     * The certificate the server at [hostKey] presents, read with a handshake that never completes:
     * the trust manager records the chain and refuses it, so nothing is sent. For the dialog that
     * asks the user to confirm it. Blocks, so call it off the main thread.
     */
    fun fetchCertificate(hostKey: String, timeoutMillis: Int = 10_000): ServerCertificate {
        val host = hostKey.substringBeforeLast(':')
        val port = hostKey.substringAfterLast(':').toIntOrNull() ?: 443
        val recorder = RecordingTrustManager()
        val context = SSLContext.getInstance("TLS")
        context.init(null, arrayOf(recorder), null)
        val socket = context.socketFactory.createSocket() as SSLSocket
        socket.use {
            it.soTimeout = timeoutMillis
            if (host.any(Char::isLetter)) {
                it.sslParameters = it.sslParameters.apply { serverNames = listOf(SNIHostName(host)) }
            }
            it.connect(InetSocketAddress(host, port), timeoutMillis)
            runCatching { it.startHandshake() }
        }
        val leaf = recorder.chain?.firstOrNull() ?: throw IOException("$host did not present a certificate")
        return ServerCertificate(
            subject = leaf.subjectX500Principal.name,
            issuer = leaf.issuerX500Principal.name,
            notAfter = leaf.notAfter,
            sha256 = sha256(leaf),
            selfSigned = leaf.subjectX500Principal == leaf.issuerX500Principal
        )
    }

    /** Records the server's chain and refuses it, so the handshake stops before any data. */
    // Trusts nothing: both checks throw, so the handshake ends before a byte of data.
    @SuppressLint("CustomX509TrustManager")
    private class RecordingTrustManager : X509TrustManager {
        @Volatile var chain: Array<out X509Certificate>? = null

        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) =
            throw CertificateException("Only reads the server's certificate")

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            this.chain = chain
            throw CertificateException("Read for confirmation, not trusted")
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }
}
