package com.example.data.network

import android.content.Context
import android.util.Log
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.Socket
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.Certificate
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

data class LocalTlsIdentity(
    val sslContext: SSLContext,
    val certificate: X509Certificate,
    val certFingerprintSha256: String
)

/**
 * TLS 1.2/1.3 helper for encrypted peer-to-peer LAN sockets (protocol v2).
 *
 * Generates and persists a local EC P-256 self-signed X.509 identity without external
 * dependencies, supports SHA-256 certificate fingerprint pinning for paired peers, and
 * inspects incoming socket prefixes (`0x16 0x03`) so v1 JSON frames and v2 TLS streams share port 53317 cleanly.
 */
object TlsTransport {

    private const val TAG = "TlsTransport"
    private const val TLS_RECORD_HANDSHAKE = 0x16
    private const val CERT_FILE_NAME = "localdrop_tls_cert.der"
    private const val KEY_FILE_NAME = "localdrop_tls_key.pk8"

    // Standard OID encodings (ASN.1 DER value bodies)
    private val OID_ECDSA_WITH_SHA256 = byteArrayOf(0x2A, 0x86.toByte(), 0x48, 0xCE.toByte(), 0x3D, 0x04, 0x03, 0x02) // 1.2.840.10045.4.3.2
    private val OID_COMMON_NAME = byteArrayOf(0x55, 0x04, 0x03) // 2.5.4.3
    private val OID_BASIC_CONSTRAINTS = byteArrayOf(0x55, 0x1D, 0x13) // 2.5.29.19
    private val OID_KEY_USAGE = byteArrayOf(0x55, 0x1D, 0x0F) // 2.5.29.15
    private val OID_EXT_KEY_USAGE = byteArrayOf(0x55, 0x1D, 0x25) // 2.5.29.37
    private val OID_SERVER_AUTH = byteArrayOf(0x2B, 0x06, 0x01, 0x05, 0x05, 0x07, 0x03, 0x01) // 1.3.6.1.5.5.7.3.1
    private val OID_CLIENT_AUTH = byteArrayOf(0x2B, 0x06, 0x01, 0x05, 0x05, 0x07, 0x03, 0x02) // 1.3.6.1.5.5.7.3.2

    @Volatile
    private var cachedIdentity: LocalTlsIdentity? = null

    /**
     * Checks the first bytes of a BufferedInputStream without consuming them to determine
     * whether the incoming peer initiated a TLS ClientHello (`0x16 0x03 ...`).
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

    fun isValidCertFingerprint(fingerprint: String?): Boolean {
        if (fingerprint.isNullOrBlank()) return false
        return fingerprint.trim().matches(Regex("^[a-fA-F0-9]{64}$"))
    }

    fun computeCertFingerprintSha256(cert: Certificate): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(cert.encoded)
        return digest.joinToString("") { "%02x".format(it) }
    }

    /**
     * Loads or generates the local peer's persistent EC P-256 X.509 identity stored in app-private storage.
     */
    @Synchronized
    fun getOrCreateLocalTlsIdentity(
        context: Context,
        commonName: String = "LocalDrop Peer"
    ): LocalTlsIdentity? {
        cachedIdentity?.let { existing ->
            try {
                existing.certificate.checkValidity()
                return existing
            } catch (_: Exception) {
                cachedIdentity = null
            }
        }

        return try {
            val tlsDir = File(context.filesDir, "tls").apply { if (!exists()) mkdirs() }
            val certFile = File(tlsDir, CERT_FILE_NAME)
            val keyFile = File(tlsDir, KEY_FILE_NAME)

            val loaded = if (certFile.exists() && keyFile.exists()) {
                loadIdentityFromFiles(certFile, keyFile)
            } else {
                null
            }

            val identity = loaded ?: createAndPersistIdentity(certFile, keyFile, commonName)
            cachedIdentity = identity
            identity
        } catch (e: Exception) {
            Log.w(TAG, "Failed initializing local TLS identity: ${e.message}")
            null
        }
    }

    /**
     * Generates a fresh ephemeral in-memory TLS identity (useful for isolated unit tests or rotation).
     */
    fun createEphemeralTlsIdentity(commonName: String = "LocalDrop Peer"): LocalTlsIdentity {
        val keyPair = generateEcKeyPair()
        val cert = buildSelfSignedX509Certificate(keyPair, commonName)
        val sslContext = buildServerSslContext(keyPair.private, cert)
        val fp = computeCertFingerprintSha256(cert)
        return LocalTlsIdentity(
            sslContext = sslContext,
            certificate = cert,
            certFingerprintSha256 = fp
        )
    }

    private fun loadIdentityFromFiles(certFile: File, keyFile: File): LocalTlsIdentity? {
        return try {
            val certBytes = certFile.readBytes()
            val keyBytes = keyFile.readBytes()
            val cert = CertificateFactory.getInstance("X.509")
                .generateCertificate(ByteArrayInputStream(certBytes)) as X509Certificate
            cert.checkValidity()
            val keySpec = PKCS8EncodedKeySpec(keyBytes)
            val privateKey = KeyFactory.getInstance("EC").generatePrivate(keySpec)
            val sslContext = buildServerSslContext(privateKey, cert)
            LocalTlsIdentity(
                sslContext = sslContext,
                certificate = cert,
                certFingerprintSha256 = computeCertFingerprintSha256(cert)
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun createAndPersistIdentity(
        certFile: File,
        keyFile: File,
        commonName: String
    ): LocalTlsIdentity {
        // Persist exact generated keypair so reload produces identical fingerprint
        val keyPair = generateEcKeyPair()
        val cert = buildSelfSignedX509Certificate(keyPair, commonName)
        val sslContext = buildServerSslContext(keyPair.private, cert)
        val fp = computeCertFingerprintSha256(cert)
        try {
            certFile.writeBytes(cert.encoded)
            keyFile.writeBytes(keyPair.private.encoded)
        } catch (e: Exception) {
            Log.w(TAG, "Unable to persist TLS identity to filesDir: ${e.message}")
        }
        return LocalTlsIdentity(
            sslContext = sslContext,
            certificate = cert,
            certFingerprintSha256 = fp
        )
    }

    private fun generateEcKeyPair(): KeyPair {
        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(ECGenParameterSpec("secp256r1"), SecureRandom())
        return kpg.generateKeyPair()
    }

    private fun buildServerSslContext(privateKey: PrivateKey, certificate: X509Certificate): SSLContext {
        val ephemeralPwd = ByteArray(16).apply { SecureRandom().nextBytes(this) }
            .joinToString("") { "%02x".format(it) }.toCharArray()
        val keyStore = KeyStore.getInstance("PKCS12").apply {
            load(null, null)
            setKeyEntry("localdrop", privateKey, ephemeralPwd, arrayOf(certificate))
        }
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
            init(keyStore, ephemeralPwd)
        }
        return SSLContext.getInstance("TLS").apply {
            init(kmf.keyManagers, null, SecureRandom())
        }
    }

    /**
     * Builds a client SSLContext configured for TLS 1.2/1.3 LAN connections.
     *
     * When [expectedFingerprintSha256] is provided (paired or QR-pinned peer), any server certificate
     * whose SHA-256 fingerprint does not match triggers a [CertificateException] during handshake.
     * When null (TOFU initial discovery), validates certificate dates and non-empty public key.
     */
    @JvmOverloads
    fun createClientSslContext(expectedFingerprintSha256: String? = null): SSLContext? {
        val pinned = expectedFingerprintSha256?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() }
        return try {
            val trustManager = arrayOf<TrustManager>(object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                    if (chain.isNullOrEmpty()) throw CertificateException("Empty client certificate chain")
                }

                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                    if (chain.isNullOrEmpty()) throw CertificateException("Empty server certificate chain")
                    val leaf = chain[0]
                    leaf.checkValidity()
                    if (pinned != null) {
                        val actual = computeCertFingerprintSha256(leaf)
                        if (!actual.equals(pinned, ignoreCase = true)) {
                            throw CertificateException(
                                "TLS peer certificate fingerprint mismatch (expected=$pinned, actual=$actual)"
                            )
                        }
                    }
                }

                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            })
            SSLContext.getInstance("TLS").apply {
                init(null, trustManager, SecureRandom())
            }
        } catch (e: Exception) {
            Log.w(TAG, "TLS client context initialization skipped: ${e.message}")
            null
        }
    }

    @JvmOverloads
    fun upgradeClientSocketToTls(
        plainSocket: Socket,
        host: String,
        port: Int,
        expectedFingerprintSha256: String? = null
    ): Socket {
        val context = createClientSslContext(expectedFingerprintSha256)
            ?: throw IllegalStateException("TLS SSLContext unavailable")
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

    // -------------------------------------------------------------------------
    // Minimal Pure-Kotlin ASN.1 DER Self-Signed X.509 v3 Certificate Generator
    // -------------------------------------------------------------------------

    private fun buildSelfSignedX509Certificate(keyPair: KeyPair, commonName: String): X509Certificate {
        val now = System.currentTimeMillis()
        val notBefore = now - 86_400_000L // 1 day clock skew allowance
        // Cap below 2050-01-01 so UTCTime encoding stays RFC 5280 compliant everywhere
        val notAfter = minOf(now + 10L * 365L * 86_400_000L, 2_524_521_600_000L)

        val serialBytes = ByteArray(16).apply {
            SecureRandom().nextBytes(this)
            this[0] = ((this[0].toInt() and 0x7F) or 0x01).toByte()
        }

        val versionField = derTag(0xA0, derTag(0x02, byteArrayOf(0x02))) // v3 = 2
        val serialField = derInteger(serialBytes)
        val sigAlgSeq = derSequence(derOid(OID_ECDSA_WITH_SHA256))
        val nameSeq = derSequence(
            derSet(
                derSequence(
                    derOid(OID_COMMON_NAME),
                    derUtf8String(commonName.ifBlank { "LocalDrop Peer" }.take(64))
                )
            )
        )
        val validitySeq = derSequence(derUtcTime(notBefore), derUtcTime(notAfter))
        val spki = keyPair.public.encoded

        // Extensions: basicConstraints (CA:FALSE, critical), keyUsage (digitalSignature | keyEncipherment, critical), extKeyUsage (serverAuth + clientAuth)
        val basicConstraintsExt = derSequence(
            derOid(OID_BASIC_CONSTRAINTS),
            derTag(0x01, byteArrayOf(0xFF.toByte())), // critical = TRUE
            derTag(0x04, derSequence()) // CA=FALSE default
        )
        val keyUsageExt = derSequence(
            derOid(OID_KEY_USAGE),
            derTag(0x01, byteArrayOf(0xFF.toByte())), // critical = TRUE
            derTag(0x04, derTag(0x03, byteArrayOf(0x05, 0xA0.toByte()))) // BIT STRING: bits 0 (digitalSignature) + 2 (keyEncipherment)
        )
        val extKeyUsageExt = derSequence(
            derOid(OID_EXT_KEY_USAGE),
            derTag(0x04, derSequence(derOid(OID_SERVER_AUTH), derOid(OID_CLIENT_AUTH)))
        )
        val extensionsField = derTag(0xA3, derSequence(basicConstraintsExt, keyUsageExt, extKeyUsageExt))

        val tbsCertificate = derSequence(
            versionField,
            serialField,
            sigAlgSeq,
            nameSeq,
            validitySeq,
            nameSeq,
            spki,
            extensionsField
        )

        val signatureBytes = Signature.getInstance("SHA256withECDSA").run {
            initSign(keyPair.private)
            update(tbsCertificate)
            sign()
        }

        val fullCertDer = derSequence(
            tbsCertificate,
            sigAlgSeq,
            derBitString(signatureBytes)
        )

        return CertificateFactory.getInstance("X.509")
            .generateCertificate(ByteArrayInputStream(fullCertDer)) as X509Certificate
    }

    private fun derTag(tag: Int, content: ByteArray): ByteArray {
        val len = content.size
        val out = ByteArrayOutputStream(len + 6)
        out.write(tag)
        when {
            len < 128 -> out.write(len)
            len <= 0xFF -> {
                out.write(0x81)
                out.write(len)
            }
            len <= 0xFFFF -> {
                out.write(0x82)
                out.write((len ushr 8) and 0xFF)
                out.write(len and 0xFF)
            }
            else -> {
                out.write(0x83)
                out.write((len ushr 16) and 0xFF)
                out.write((len ushr 8) and 0xFF)
                out.write(len and 0xFF)
            }
        }
        out.write(content)
        return out.toByteArray()
    }

    private fun derSequence(vararg items: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        items.forEach { out.write(it) }
        return derTag(0x30, out.toByteArray())
    }

    private fun derSet(vararg items: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        items.forEach { out.write(it) }
        return derTag(0x31, out.toByteArray())
    }

    private fun derOid(encodedBody: ByteArray): ByteArray = derTag(0x06, encodedBody)

    private fun derUtf8String(text: String): ByteArray = derTag(0x0C, text.toByteArray(Charsets.UTF_8))

    private fun derBitString(bytes: ByteArray): ByteArray {
        val out = ByteArray(bytes.size + 1)
        out[0] = 0x00 // 0 unused bits
        System.arraycopy(bytes, 0, out, 1, bytes.size)
        return derTag(0x03, out)
    }

    private fun derInteger(unsignedBigEndian: ByteArray): ByteArray {
        var idx = 0
        while (idx < unsignedBigEndian.size - 1 && unsignedBigEndian[idx] == 0.toByte()) {
            idx++
        }
        val slice = unsignedBigEndian.copyOfRange(idx, unsignedBigEndian.size)
        val content = if ((slice[0].toInt() and 0x80) != 0) {
            byteArrayOf(0x00) + slice
        } else {
            slice
        }
        return derTag(0x02, content)
    }

    private fun derUtcTime(epochMs: Long): ByteArray {
        val fmt = SimpleDateFormat("yyMMddHHmmss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        return derTag(0x17, fmt.format(Date(epochMs)).toByteArray(Charsets.US_ASCII))
    }
}
