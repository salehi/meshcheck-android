package io.meshcheck.core.crypto

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.security.KeyPairGeneratorSpec
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.util.Calendar
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.security.auth.x500.X500Principal

/**
 * Encrypts small secrets — the Ed25519 private seed and the Node API key —
 * with a non-exportable key held in the Android Keystore. The wrapped bytes
 * can then sit in plain `SharedPreferences` while the key material itself
 * never leaves the device's hardware-backed keystore.
 *
 * Two tiers, because the Keystore's symmetric (AES) support begins only at
 * API 23:
 *
 *  - **API 23+**   — an AES-256-GCM Keystore key encrypts the secret directly.
 *  - **API 21-22** — an RSA-2048 Keystore key wraps the secret with PKCS#1
 *    padding. The secrets here are at most ~64 bytes, far inside RSA-2048's
 *    245-byte PKCS#1 capacity.
 *
 * The on-disk layout is `[tier byte][payload]`, so [unwrap] dispatches on the
 * tier the bytes were written with — a device upgraded from 22 to 23 can still
 * read what the older OS wrote.
 */
class KeystoreEnvelope(context: Context) {

    private val context: Context = context.applicationContext

    /** Encrypts [plaintext] with the device's Keystore-held envelope key. */
    fun wrap(plaintext: ByteArray): ByteArray =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            aesWrap(plaintext)
        } else {
            rsaWrap(plaintext)
        }

    /** Reverses [wrap]; dispatches on the tier byte the ciphertext carries. */
    fun unwrap(ciphertext: ByteArray): ByteArray {
        require(ciphertext.isNotEmpty()) { "empty ciphertext" }
        val payload = ciphertext.copyOfRange(1, ciphertext.size)
        return when (ciphertext[0]) {
            TIER_AES_GCM -> {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
                    error("AES-tier ciphertext cannot be read on API < 23")
                }
                aesUnwrap(payload)
            }
            TIER_RSA_PKCS1 -> rsaUnwrap(payload)
            else -> error("unknown KeystoreEnvelope tier: ${ciphertext[0]}")
        }
    }

    /** Permanently removes the Keystore envelope key — used by Unlink. */
    fun deleteKey() {
        val keyStore = androidKeyStore()
        if (keyStore.containsAlias(KEY_ALIAS)) keyStore.deleteEntry(KEY_ALIAS)
    }

    // --- AES-GCM tier (API 23+) ----------------------------------------------

    private fun aesWrap(plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(AES_TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, aesKey())
        val iv = cipher.iv
        val encrypted = cipher.doFinal(plaintext)
        // [tier][iv length][iv][ciphertext]
        return ByteArray(2 + iv.size + encrypted.size).also {
            it[0] = TIER_AES_GCM
            it[1] = iv.size.toByte()
            System.arraycopy(iv, 0, it, 2, iv.size)
            System.arraycopy(encrypted, 0, it, 2 + iv.size, encrypted.size)
        }
    }

    private fun aesUnwrap(payload: ByteArray): ByteArray {
        val ivLength = payload[0].toInt()
        val iv = payload.copyOfRange(1, 1 + ivLength)
        val encrypted = payload.copyOfRange(1 + ivLength, payload.size)
        val cipher = Cipher.getInstance(AES_TRANSFORM)
        cipher.init(Cipher.DECRYPT_MODE, aesKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        return cipher.doFinal(encrypted)
    }

    // The Keystore AES API (KeyGenParameterSpec / KeyProperties) is API 23+.
    // Both callers above only run after a Build.VERSION.SDK_INT >= M check.
    @SuppressLint("NewApi")
    private fun aesKey(): SecretKey {
        val keyStore = androidKeyStore()
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)
            ?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            ANDROID_KEYSTORE,
        )
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    // --- RSA-PKCS#1 tier (API 21-22) -----------------------------------------

    private fun rsaWrap(plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(RSA_TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, rsaKeyPair().public)
        val encrypted = cipher.doFinal(plaintext)
        return ByteArray(1 + encrypted.size).also {
            it[0] = TIER_RSA_PKCS1
            System.arraycopy(encrypted, 0, it, 1, encrypted.size)
        }
    }

    private fun rsaUnwrap(payload: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(RSA_TRANSFORM)
        cipher.init(Cipher.DECRYPT_MODE, rsaKeyPair().private)
        return cipher.doFinal(payload)
    }

    // KeyPairGeneratorSpec is deprecated since API 23, but it is the only way
    // to create a Keystore RSA key on API 21-22, which this tier targets.
    @Suppress("DEPRECATION")
    private fun rsaKeyPair(): KeyPair {
        val keyStore = androidKeyStore()
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.PrivateKeyEntry)?.let {
            return KeyPair(it.certificate.publicKey, it.privateKey)
        }

        val notBefore = Calendar.getInstance()
        val notAfter = Calendar.getInstance().apply { add(Calendar.YEAR, 100) }
        val spec = KeyPairGeneratorSpec.Builder(context)
            .setAlias(KEY_ALIAS)
            .setSubject(X500Principal("CN=$KEY_ALIAS"))
            .setSerialNumber(BigInteger.ONE)
            .setStartDate(notBefore.time)
            .setEndDate(notAfter.time)
            .setKeySize(2048)
            .build()
        val generator = KeyPairGenerator.getInstance("RSA", ANDROID_KEYSTORE)
        generator.initialize(spec)
        return generator.generateKeyPair()
    }

    private fun androidKeyStore(): KeyStore =
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "meshcheck.credential-envelope"
        const val AES_TRANSFORM = "AES/GCM/NoPadding"
        const val RSA_TRANSFORM = "RSA/ECB/PKCS1Padding"
        const val GCM_TAG_BITS = 128

        const val TIER_AES_GCM: Byte = 1
        const val TIER_RSA_PKCS1: Byte = 2
    }
}
