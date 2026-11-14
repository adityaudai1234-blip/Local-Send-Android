package com.example.data.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class SecurityKeyStore {

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val MASTER_KEY_ALIAS = "LocalDropMasterKey"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_LENGTH = 128
        private const val GCM_IV_LENGTH = 12
    }

    private var keyStore: KeyStore? = null
    private var inMemoryFallbackKey: SecretKey? = null
    private val secureRandom = SecureRandom()

    val isUsingAndroidKeyStore: Boolean
        get() = keyStore != null

    init {
        try {
            val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            keyStore = ks
            ensureMasterKey(ks)
        } catch (_: Exception) {
            // Fallback for non-Android JVM unit test environments
            keyStore = null
            val bytes = ByteArray(32)
            secureRandom.nextBytes(bytes)
            inMemoryFallbackKey = SecretKeySpec(bytes, "AES")
        }
    }

    private fun ensureMasterKey(ks: KeyStore) {
        if (!ks.containsAlias(MASTER_KEY_ALIAS)) {
            val keyGenerator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES,
                ANDROID_KEYSTORE
            )
            val spec = KeyGenParameterSpec.Builder(
                MASTER_KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build()

            keyGenerator.init(spec)
            keyGenerator.generateKey()
        }
    }

    private fun getMasterKey(): SecretKey {
        val ks = keyStore
        if (ks != null) {
            ensureMasterKey(ks)
            return ks.getKey(MASTER_KEY_ALIAS, null) as SecretKey
        }
        return inMemoryFallbackKey ?: run {
            val bytes = ByteArray(32)
            secureRandom.nextBytes(bytes)
            SecretKeySpec(bytes, "AES").also { inMemoryFallbackKey = it }
        }
    }

    fun encryptToken(plainToken: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getMasterKey())
        val iv = cipher.iv
        val encryptedBytes = cipher.doFinal(plainToken.toByteArray(Charsets.UTF_8))

        val combined = ByteArray(iv.size + encryptedBytes.size)
        System.arraycopy(iv, 0, combined, 0, iv.size)
        System.arraycopy(encryptedBytes, 0, combined, iv.size, encryptedBytes.size)

        return Base64.encodeToString(combined, Base64.NO_WRAP)
    }

    fun decryptToken(encryptedBase64: String): String? {
        return try {
            val combined = Base64.decode(encryptedBase64, Base64.NO_WRAP)
            if (combined.size < GCM_IV_LENGTH) return null

            val iv = combined.copyOfRange(0, GCM_IV_LENGTH)
            val cipherText = combined.copyOfRange(GCM_IV_LENGTH, combined.size)

            val cipher = Cipher.getInstance(TRANSFORMATION)
            val spec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
            cipher.init(Cipher.DECRYPT_MODE, getMasterKey(), spec)

            val decryptedBytes = cipher.doFinal(cipherText)
            String(decryptedBytes, Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }

    fun clearAllKeys() {
        try {
            val ks = keyStore
            if (ks != null && ks.containsAlias(MASTER_KEY_ALIAS)) {
                ks.deleteEntry(MASTER_KEY_ALIAS)
                ensureMasterKey(ks)
            }
        } catch (_: Exception) {}
        inMemoryFallbackKey = null
    }

    fun generateSecureToken(byteLength: Int = 24): String {
        val bytes = ByteArray(byteLength)
        secureRandom.nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }
}
