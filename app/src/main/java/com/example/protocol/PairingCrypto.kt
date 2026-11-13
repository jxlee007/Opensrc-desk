package com.example.protocol

import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Transport-agnostic cryptographic primitives for Extended Desktop v2 handshake:
 * - Intentional removal of unsafe plaintext-token v1 (MIN_PROTOCOL_VERSION = 2)
 * - Ephemeral ECDH P-256 + 6-digit SAS code matching for first-time pairing
 * - Constant-time mutual HMAC-SHA-256 challenge-response (token never crosses wire)
 * - Authenticated AES-256-CBC + HMAC-SHA-256 initial token sealing (API 21+ compatible)
 */
object PairingCrypto {

    const val PROTOCOL_VERSION = 2
    const val MIN_PROTOCOL_VERSION = 2

    const val PRE_AUTH_IO_TIMEOUT_MS = 3_000L
    const val COMMIT_WINDOW_MS = 3_000L
    const val DEFAULT_SAS_TIMEOUT_MS = 120_000L
    const val MIN_SAS_TIMEOUT_MS = 10_000L
    const val MAX_SAS_TIMEOUT_MS = 120_000L
    const val STREAM_SO_TIMEOUT_MS = 12_000L
    const val VIDEO_ACCEPT_TIMEOUT_MS = 5_000L

    fun clampSasTimeoutMs(rawMs: Long): Long = rawMs.coerceIn(MIN_SAS_TIMEOUT_MS, MAX_SAS_TIMEOUT_MS)

    private val secureRandom = SecureRandom()
    private const val B64_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

    sealed interface ProtocolNegotiation {
        data class Agreed(val version: Int) : ProtocolNegotiation
        data class Incompatible(val reason: String) : ProtocolNegotiation
    }

    fun generateNonce(): String {
        val bytes = ByteArray(32)
        secureRandom.nextBytes(bytes)
        return encodeBase64Url(bytes)
    }

    fun generateToken(): String = generateNonce()

    fun generateEcdhKeyPair(): KeyPair {
        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(ECGenParameterSpec("secp256r1"), secureRandom)
        return kpg.generateKeyPair()
    }

    fun encodePublicKey(keyPair: KeyPair): String = encodeBase64Url(keyPair.public.encoded)

    fun deriveEcdhSharedSecret(localPrivate: PrivateKey, peerPublicB64: String): ByteArray {
        val pubBytes = decodeBase64Url(peerPublicB64)
        val pubKey = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(pubBytes))
        val ka = KeyAgreement.getInstance("ECDH")
        ka.init(localPrivate)
        ka.doPhase(pubKey, true)
        return ka.generateSecret()
    }

    fun negotiateForDisplay(
        reqProtocolVersion: Int,
        reqMinSupported: Int,
        hostName: String
    ): ProtocolNegotiation {
        if (reqProtocolVersion < MIN_PROTOCOL_VERSION) {
            return ProtocolNegotiation.Incompatible(
                "Host ($hostName) is running outdated protocol v$reqProtocolVersion — update Extended Desktop on the Host."
            )
        }
        if (reqMinSupported > PROTOCOL_VERSION) {
            return ProtocolNegotiation.Incompatible(
                "This Display is running an older protocol (v$PROTOCOL_VERSION) than Host ($hostName) requires (v$reqMinSupported+) — update Extended Desktop on this Display."
            )
        }
        return ProtocolNegotiation.Agreed(minOf(reqProtocolVersion, PROTOCOL_VERSION))
    }

    fun negotiateForHost(
        negotiatedVersion: Int?,
        displayName: String,
        rejectionReason: String?
    ): ProtocolNegotiation {
        if (!rejectionReason.isNullOrBlank()) {
            return ProtocolNegotiation.Incompatible(rejectionReason)
        }
        if (negotiatedVersion == null || negotiatedVersion < MIN_PROTOCOL_VERSION) {
            val found = negotiatedVersion ?: 1
            return ProtocolNegotiation.Incompatible(
                "Display ($displayName) is running outdated protocol v$found — update Extended Desktop on the Display."
            )
        }
        if (negotiatedVersion > PROTOCOL_VERSION) {
            return ProtocolNegotiation.Incompatible(
                "This Host requires an update to match Display ($displayName) protocol v$negotiatedVersion."
            )
        }
        return ProtocolNegotiation.Agreed(negotiatedVersion)
    }

    /**
     * Derives a 6-digit Short Authentication String (SAS) formatted as "XXX-XXX"
     * bound to the ECDH shared secret, both nonces, protocol version, and both public keys.
     * Any MITM substituting either ECDH public key yields a mismatched code on Host vs Display.
     */
    fun deriveSasCode(
        ecdhSharedSecret: ByteArray,
        negotiatedVersion: Int,
        hostNonce: String,
        displayNonce: String,
        hostPubKeyB64: String,
        displayPubKeyB64: String
    ): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update("SAS_CONFIRM|$negotiatedVersion|$hostNonce|$displayNonce|$hostPubKeyB64|$displayPubKeyB64|".toByteArray(Charsets.UTF_8))
        digest.update(ecdhSharedSecret)
        val hash = digest.digest()
        val rawInt = ((hash[0].toInt() and 0x7F) shl 24) or
            ((hash[1].toInt() and 0xFF) shl 16) or
            ((hash[2].toInt() and 0xFF) shl 8) or
            (hash[3].toInt() and 0xFF)
        val sixDigit = "%06d".format(rawInt % 1_000_000)
        return "${sixDigit.substring(0, 3)}-${sixDigit.substring(3, 6)}"
    }

    fun computeDisplayProofHmac(
        sharedToken: String,
        negotiatedVersion: Int,
        hostNonce: String,
        displayNonce: String,
        hostId: String,
        displayPeerId: String
    ): String {
        val message = "DISPLAY_PROOF|$negotiatedVersion|$hostNonce|$displayNonce|$hostId|$displayPeerId"
        return hmacSha256(sharedToken.toByteArray(Charsets.UTF_8), message.toByteArray(Charsets.UTF_8))
    }

    fun verifyDisplayProofHmac(
        sharedToken: String,
        candidateHmac: String?,
        negotiatedVersion: Int,
        hostNonce: String,
        displayNonce: String,
        hostId: String,
        displayPeerId: String
    ): Boolean {
        if (candidateHmac.isNullOrBlank()) return false
        val expected = computeDisplayProofHmac(
            sharedToken = sharedToken,
            negotiatedVersion = negotiatedVersion,
            hostNonce = hostNonce,
            displayNonce = displayNonce,
            hostId = hostId,
            displayPeerId = displayPeerId
        )
        return constantTimeEquals(expected, candidateHmac)
    }

    fun computeHostProofHmac(
        sharedToken: String,
        negotiatedVersion: Int,
        displayNonce: String,
        hostNonce: String,
        hostId: String,
        displayPeerId: String
    ): String {
        val message = "HOST_PROOF|$negotiatedVersion|$displayNonce|$hostNonce|$hostId|$displayPeerId"
        return hmacSha256(sharedToken.toByteArray(Charsets.UTF_8), message.toByteArray(Charsets.UTF_8))
    }

    fun verifyHostProofHmac(
        sharedToken: String,
        candidateHmac: String?,
        negotiatedVersion: Int,
        displayNonce: String,
        hostNonce: String,
        hostId: String,
        displayPeerId: String
    ): Boolean {
        if (candidateHmac.isNullOrBlank()) return false
        val expected = computeHostProofHmac(
            sharedToken = sharedToken,
            negotiatedVersion = negotiatedVersion,
            displayNonce = displayNonce,
            hostNonce = hostNonce,
            hostId = hostId,
            displayPeerId = displayPeerId
        )
        return constantTimeEquals(expected, candidateHmac)
    }

    data class SealedToken(val ciphertextB64: String, val macTagB64: String)

    /**
     * Encrypts and authenticates an initial pairing token under keys derived from the ECDH secret
     * and handshake nonces using AES-256-CBC + EtM HMAC-SHA-256 (works reliably on API 21+).
     */
    fun sealInitialToken(
        ecdhSharedSecret: ByteArray,
        hostNonce: String,
        displayNonce: String,
        rawToken: String
    ): SealedToken {
        val (encKey, macKey) = deriveSessionKeys(ecdhSharedSecret, hostNonce, displayNonce)
        val iv = ByteArray(16).also { secureRandom.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(encKey, "AES"), IvParameterSpec(iv))
        val encrypted = cipher.doFinal(rawToken.toByteArray(Charsets.UTF_8))
        val combined = ByteArray(16 + encrypted.size)
        System.arraycopy(iv, 0, combined, 0, 16)
        System.arraycopy(encrypted, 0, combined, 16, encrypted.size)
        val tag = hmacSha256(macKey, combined)
        return SealedToken(encodeBase64Url(combined), tag)
    }

    fun unsealInitialToken(
        ecdhSharedSecret: ByteArray,
        hostNonce: String,
        displayNonce: String,
        sealedCipherB64: String?,
        macTagB64: String?
    ): String? {
        if (sealedCipherB64.isNullOrBlank() || macTagB64.isNullOrBlank()) return null
        return try {
            val combined = decodeBase64Url(sealedCipherB64)
            if (combined.size <= 16) return null
            val (encKey, macKey) = deriveSessionKeys(ecdhSharedSecret, hostNonce, displayNonce)
            val expectedTag = hmacSha256(macKey, combined)
            if (!constantTimeEquals(expectedTag, macTagB64)) return null
            val iv = combined.copyOfRange(0, 16)
            val ciphertext = combined.copyOfRange(16, combined.size)
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(encKey, "AES"), IvParameterSpec(iv))
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        } catch (_: Throwable) {
            null
        }
    }

    fun constantTimeEquals(a: String, b: String): Boolean {
        return MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))
    }

    private fun deriveSessionKeys(
        ecdhSecret: ByteArray,
        hostNonce: String,
        displayNonce: String
    ): Pair<ByteArray, ByteArray> {
        val encDigest = MessageDigest.getInstance("SHA-256").apply {
            update("EXTDESK_ENC|$hostNonce|$displayNonce|".toByteArray(Charsets.UTF_8))
            update(ecdhSecret)
        }.digest()
        val macDigest = MessageDigest.getInstance("SHA-256").apply {
            update("EXTDESK_MAC|$displayNonce|$hostNonce|".toByteArray(Charsets.UTF_8))
            update(ecdhSecret)
        }.digest()
        return encDigest to macDigest
    }

    private fun hmacSha256(keyBytes: ByteArray, messageBytes: ByteArray): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(keyBytes, "HmacSHA256"))
        return encodeBase64Url(mac.doFinal(messageBytes))
    }

    private fun encodeBase64Url(input: ByteArray): String {
        val out = StringBuilder((input.size * 4 + 2) / 3)
        var i = 0
        while (i < input.size) {
            val b0 = input[i++].toInt() and 0xFF
            val hasB1 = i < input.size
            val b1 = if (hasB1) input[i++].toInt() and 0xFF else 0
            val hasB2 = i < input.size
            val b2 = if (hasB2) input[i++].toInt() and 0xFF else 0

            out.append(B64_ALPHABET[b0 ushr 2])
            out.append(B64_ALPHABET[((b0 and 0x03) shl 4) or (b1 ushr 4)])
            if (hasB1) {
                out.append(B64_ALPHABET[((b1 and 0x0F) shl 2) or (b2 ushr 6)])
            }
            if (hasB2) {
                out.append(B64_ALPHABET[b2 and 0x3F])
            }
        }
        return out.toString()
    }

    private fun decodeBase64Url(input: String): ByteArray {
        val clean = input.trimEnd('=')
        val rev = IntArray(128) { -1 }
        for (idx in B64_ALPHABET.indices) {
            rev[B64_ALPHABET[idx].code] = idx
        }
        val outLen = (clean.length * 3) / 4
        val out = ByteArray(outLen)
        var outIdx = 0
        var i = 0
        while (i < clean.length) {
            val c0 = rev.getOrElse(clean[i++].code) { -1 }
            val c1 = if (i < clean.length) rev.getOrElse(clean[i++].code) { -1 } else -1
            val c2 = if (i < clean.length) rev.getOrElse(clean[i++].code) { -1 } else -1
            val c3 = if (i < clean.length) rev.getOrElse(clean[i++].code) { -1 } else -1
            if (c0 < 0 || c1 < 0) throw IllegalArgumentException("Invalid base64url")
            if (outIdx < out.size) out[outIdx++] = ((c0 shl 2) or (c1 ushr 4)).toByte()
            if (c2 >= 0 && outIdx < out.size) out[outIdx++] = (((c1 and 0x0F) shl 4) or (c2 ushr 2)).toByte()
            if (c3 >= 0 && outIdx < out.size) out[outIdx++] = (((c2 and 0x03) shl 6) or c3).toByte()
        }
        return if (outIdx == out.size) out else out.copyOf(outIdx)
    }
}
