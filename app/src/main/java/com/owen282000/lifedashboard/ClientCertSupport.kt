package com.owen282000.lifedashboard

import android.content.Context
import android.security.KeyChain
import java.io.IOException
import java.net.Socket
import java.security.KeyStore
import java.security.Principal
import java.security.PrivateKey
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509TrustManager

/**
 * Client certificate (mTLS) support backed by the Android system credential store.
 *
 * The user picks a certificate once with KeyChain.choosePrivateKeyAlias; Android remembers
 * that this app may use it, so background syncs can load the key by its alias without any UI.
 * Only the alias is stored by the app. It is device specific and deliberately not part of the
 * config backup.
 */
object ClientCertSupport {

    class Setup(val socketFactory: SSLSocketFactory, val trustManager: X509TrustManager)

    /**
     * Builds an SSL setup presenting the certificate behind [alias], keeping the platform's
     * default trust (system CAs and network_security_config).
     *
     * Blocks on KeyChain, so call it off the main thread. Throws [IOException] when the
     * certificate was removed or access to it was revoked.
     */
    fun sslSetup(context: Context, alias: String): Setup {
        val key = try {
            KeyChain.getPrivateKey(context, alias)
        } catch (e: Exception) {
            throw IOException(unavailableMessage(alias), e)
        }
        val chain = try {
            KeyChain.getCertificateChain(context, alias)
        } catch (e: Exception) {
            throw IOException(unavailableMessage(alias), e)
        }
        if (key == null || chain.isNullOrEmpty()) throw IOException(unavailableMessage(alias))
        return sslSetup(alias, key, chain)
    }

    /** The KeyChain-free part of [sslSetup], so it can be tested on the JVM. */
    internal fun sslSetup(
        alias: String,
        key: PrivateKey,
        chain: Array<X509Certificate>,
        trustManager: X509TrustManager = platformTrustManager()
    ): Setup {
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(arrayOf(FixedKeyManager(alias, key, chain)), arrayOf(trustManager), null)
        return Setup(sslContext.socketFactory, trustManager)
    }

    private fun platformTrustManager(): X509TrustManager {
        val trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        trustManagerFactory.init(null as KeyStore?)
        return trustManagerFactory.trustManagers.filterIsInstance<X509TrustManager>().first()
    }

    fun unavailableMessage(alias: String) =
        "Client certificate '$alias' is unavailable. Choose it again under Advanced settings."

    /** Always offers the one certificate the user picked, whatever the server asks for. */
    internal class FixedKeyManager(
        private val alias: String,
        private val key: PrivateKey,
        private val chain: Array<X509Certificate>
    ) : X509ExtendedKeyManager() {
        override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?) = alias
        override fun chooseEngineClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, engine: SSLEngine?) = alias
        override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?) = arrayOf(alias)
        override fun getCertificateChain(alias: String?) = if (alias == this.alias) chain else null
        override fun getPrivateKey(alias: String?) = if (alias == this.alias) key else null
        override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = null
        override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?): String? = null
    }
}
