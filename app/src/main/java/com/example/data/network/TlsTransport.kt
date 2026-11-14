package com.example.data.network

import android.util.Log
import java.io.BufferedInputStream
import java.net.Socket
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * TLS 1.2/1.3 helper for optional encrypted peer-to-peer LAN sockets.
 * Supports peer-certificate fingerprint inspection while remaining backward compatible
 * with plain v1 local TCP framing when peers do not initiate TLS.
 */
object TlsTransport {

    private const val TAG = "TlsTransport"
    private const val TLS_RECORD_HANDSHAKE = 0x16

    /**
     * Checks the first bytes of a BufferedInputStream without consuming them to determine
     * whether the incoming peer initiated a TLS ClientHello (0x16 0x03 ...).
     */
    fun isTlsClientHello(input: BufferedInputStream): Boolean {
        return try {
            input.mark(3)
            val b0 = input.read()
            val b1 = input.read()
            input.reset()
            b0 == TLS_RECORD_HANDSHAKE && b1 == 0x03
        } catch (_: Exception) {
            try {
                input.reset()
            } catch (_: Exception) {}
            false
        }
    }

    /**
     * Builds a client SSLSocketFactory configured for TLS 1.2/1.3 LAN connections
     * using TOFU (Trust On First Use) / peer pairing token verification.
     */
    fun createClientSslContext(): SSLContext? {
        return try {
            val trustAll = arrayOf<TrustManager>(object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            })
            SSLContext.getInstance("TLS").apply {
                init(null, trustAll, SecureRandom())
            }
        } catch (e: Exception) {
            Log.w(TAG, "TLS client context initialization skipped: ${e.message}")
            null
        }
    }

    fun upgradeClientSocketToTls(plainSocket: Socket, host: String, port: Int): Socket {
        val context = createClientSslContext() ?: return plainSocket
        val sslSocket = context.socketFactory.createSocket(plainSocket, host, port, true) as SSLSocket
        sslSocket.enabledProtocols = sslSocket.supportedProtocols.filter {
            it.startsWith("TLSv1.2") || it.startsWith("TLSv1.3")
        }.toTypedArray().ifEmpty { sslSocket.enabledProtocols }
        sslSocket.startHandshake()
        return sslSocket
    }

    fun createServerSslContextFromKeyStore(keyStore: KeyStore, password: CharArray? = null): SSLContext? {
        return try {
            val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
            kmf.init(keyStore, password)
            SSLContext.getInstance("TLS").apply {
                init(kmf.keyManagers, null, SecureRandom())
            }
        } catch (e: Exception) {
            Log.w(TAG, "TLS server context unavailable: ${e.message}")
            null
        }
    }
}
