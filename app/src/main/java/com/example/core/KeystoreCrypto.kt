package com.example.core

import android.content.Context
import android.os.Build
import android.security.KeyPairGeneratorSpec
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.SecureRandom
import java.util.Calendar
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.security.auth.x500.X500Principal

class KeystoreCrypto(
    private val context: Context,
    private val vaultOverride: TokenVault? = null
) {

    enum class KeystoreHealth {
        KEYSTORE_AES_GCM_API23,
        KEYSTORE_RSA_WRAPPED_API21,
        LEGACY_ROM_FALLBACK_WARNING
    }

    interface TokenVault {
        fun encrypt(rawToken: String): String
        fun decrypt(storedValue: String): String?
        fun rotate()
        val activeHealth: KeystoreHealth
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val secureRandom = SecureRandom()

    private val initialHealth = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        KeystoreHealth.KEYSTORE_AES_GCM_API23
    } else {
        KeystoreHealth.KEYSTORE_RSA_WRAPPED_API21
    }

    private val _healthStatus = MutableStateFlow(initialHealth)
    val healthStatusFlow: StateFlow<KeystoreHealth> = _healthStatus.asStateFlow()

    var healthStatus: KeystoreHealth
        get() = _healthStatus.value
        private set(value) {
            _healthStatus.value = value
        }

    fun isSessionOnlyDegraded(): Boolean =
        _healthStatus.value == KeystoreHealth.LEGACY_ROM_FALLBACK_WARNING

    fun isAvailable(): Boolean {
        vaultOverride?.let { vault ->
            return try {
                val sample = vault.encrypt("ks-health-probe")
                val restored = vault.decrypt(sample)
                val ok = restored == "ks-health-probe" &&
                    vault.activeHealth != KeystoreHealth.LEGACY_ROM_FALLBACK_WARNING
                healthStatus = if (ok) vault.activeHealth else KeystoreHealth.LEGACY_ROM_FALLBACK_WARNING
                ok
            } catch (_: Throwable) {
                healthStatus = KeystoreHealth.LEGACY_ROM_FALLBACK_WARNING
                false
            }
        }
        return try {
            getOrCreateMasterAesKey()
            true
        } catch (_: Throwable) {
            healthStatus = KeystoreHealth.LEGACY_ROM_FALLBACK_WARNING
            false
        }
    }

    fun generateSecureToken(): String {
        val bytes = ByteArray(32)
        secureRandom.nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    /**
     * Fail-closed Keystore encryption: returns null if encryption fails on the device ROM.
     * Never writes plaintext or Base64-obfuscated fallback tokens.
     */
    fun encryptToken(rawToken: String): String? {
        vaultOverride?.let { vault ->
            return try {
                val out = vault.encrypt(rawToken)
                healthStatus = vault.activeHealth
                out
            } catch (_: Throwable) {
                healthStatus = KeystoreHealth.LEGACY_ROM_FALLBACK_WARNING
                null
            }
        }
        return try {
            val key = getOrCreateMasterAesKey()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val iv = cipher.iv
            val encrypted = cipher.doFinal(rawToken.toByteArray(Charsets.UTF_8))
            val combined = ByteArray(1 + iv.size + encrypted.size)
            combined[0] = iv.size.toByte()
            System.arraycopy(iv, 0, combined, 1, iv.size)
            System.arraycopy(encrypted, 0, combined, 1 + iv.size, encrypted.size)
            Base64.encodeToString(combined, Base64.NO_WRAP)
        } catch (_: Throwable) {
            healthStatus = KeystoreHealth.LEGACY_ROM_FALLBACK_WARNING
            null
        }
    }

    /**
     * Fail-closed Keystore decryption: rejects unencrypted/legacy-obfuscated blobs and returns null on error.
     */
    fun decryptToken(storedValue: String): String? {
        if (storedValue.isBlank()) {
            return null
        }
        vaultOverride?.let { vault ->
            return try {
                vault.decrypt(storedValue)
            } catch (_: Throwable) {
                null
            }
        }
        return try {
            val combined = Base64.decode(storedValue, Base64.NO_WRAP)
            if (combined.size < 2) return null
            val ivLen = combined[0].toInt() and 0xFF
            if (ivLen <= 0 || 1 + ivLen >= combined.size) return null
            val iv = combined.copyOfRange(1, 1 + ivLen)
            val ciphertext = combined.copyOfRange(1 + ivLen, combined.size)
            val key = getOrCreateMasterAesKey()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        } catch (_: Throwable) {
            null
        }
    }

    fun rotateWrappingKeysOnRevoke() {
        vaultOverride?.let { vault ->
            runCatching { vault.rotate() }
            return
        }
        try {
            prefs.edit().remove(KEY_WRAPPED_AES_API21).apply()
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            if (keyStore.containsAlias(ALIAS_AES_23)) {
                keyStore.deleteEntry(ALIAS_AES_23)
            }
            if (keyStore.containsAlias(ALIAS_RSA_21)) {
                keyStore.deleteEntry(ALIAS_RSA_21)
            }
        } catch (_: Throwable) {
            healthStatus = KeystoreHealth.LEGACY_ROM_FALLBACK_WARNING
        }
    }

    private fun getOrCreateMasterAesKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            healthStatus = KeystoreHealth.KEYSTORE_AES_GCM_API23
            val existing = keyStore.getKey(ALIAS_AES_23, null) as? SecretKey
            if (existing != null) return existing
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            generator.init(
                KeyGenParameterSpec.Builder(
                    ALIAS_AES_23,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
            )
            return generator.generateKey()
        } else {
            // API 21-22 path: Keystore RSA-wrapped 256-bit AES key (requires real-device ROM validation)
            healthStatus = KeystoreHealth.KEYSTORE_RSA_WRAPPED_API21
            if (!keyStore.containsAlias(ALIAS_RSA_21)) {
                generateApi21RsaKeyPair()
            }
            val wrappedBase64 = prefs.getString(KEY_WRAPPED_AES_API21, null)
            val privateKey = keyStore.getKey(ALIAS_RSA_21, null)
            val certificate = keyStore.getCertificate(ALIAS_RSA_21)
            val publicKey = certificate.publicKey
            if (wrappedBase64 != null && privateKey != null) {
                val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
                cipher.init(Cipher.DECRYPT_MODE, privateKey)
                val rawAes = cipher.doFinal(Base64.decode(wrappedBase64, Base64.NO_WRAP))
                return SecretKeySpec(rawAes, "AES")
            }
            val rawAes = ByteArray(32).also { secureRandom.nextBytes(it) }
            val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
            cipher.init(Cipher.ENCRYPT_MODE, publicKey)
            val wrapped = cipher.doFinal(rawAes)
            prefs.edit().putString(
                KEY_WRAPPED_AES_API21,
                Base64.encodeToString(wrapped, Base64.NO_WRAP)
            ).apply()
            return SecretKeySpec(rawAes, "AES")
        }
    }

    @Suppress("DEPRECATION")
    private fun generateApi21RsaKeyPair() {
        val start = Calendar.getInstance()
        val end = Calendar.getInstance().apply { add(Calendar.YEAR, 20) }
        val spec = KeyPairGeneratorSpec.Builder(context)
            .setAlias(ALIAS_RSA_21)
            .setSubject(X500Principal("CN=ExtendedDesktopTrust"))
            .setSerialNumber(BigInteger.ONE)
            .setStartDate(start.time)
            .setEndDate(end.time)
            .build()
        val kpg = KeyPairGenerator.getInstance("RSA", ANDROID_KEYSTORE)
        kpg.initialize(spec)
        kpg.generateKeyPair()
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val ALIAS_AES_23 = "extdesk_trust_aes_v1"
        private const val ALIAS_RSA_21 = "extdesk_trust_rsa_api21"
        private const val PREFS_NAME = "extdesk_keystore_meta"
        private const val KEY_WRAPPED_AES_API21 = "wrapped_aes_key"
    }
}
