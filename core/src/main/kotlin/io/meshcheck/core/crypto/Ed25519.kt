package io.meshcheck.core.crypto

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.security.SecureRandom

/** A raw Ed25519 keypair: the 32-byte private seed and the 32-byte public key. */
class Ed25519KeyPair(
    val privateSeed: ByteArray,
    val publicKey: ByteArray,
)

/**
 * Ed25519 keypair generation and signing.
 *
 * Uses the BouncyCastle low-level crypto API (`org.bouncycastle.crypto.*`)
 * directly — no JCE `Provider` is registered, so this never collides with the
 * "BC" provider Android backs with its own repackaged BouncyCastle. Works on
 * API 21+.
 *
 * The keypair is generated in software because the Android Keystore only
 * supports Ed25519 from API 33; the private seed is instead protected at rest
 * by [KeystoreEnvelope].
 */
object Ed25519 {
    const val SEED_SIZE = 32
    const val PUBLIC_KEY_SIZE = 32
    const val SIGNATURE_SIZE = 64

    /** Generates a fresh keypair from [random]. */
    fun generate(random: SecureRandom = SecureRandom()): Ed25519KeyPair {
        val privateKey = Ed25519PrivateKeyParameters(random)
        val publicKey = privateKey.generatePublicKey()
        return Ed25519KeyPair(privateKey.encoded, publicKey.encoded)
    }

    /** Returns the 64-byte Ed25519 signature of [message] under [privateSeed]. */
    fun sign(privateSeed: ByteArray, message: ByteArray): ByteArray {
        require(privateSeed.size == SEED_SIZE) {
            "Ed25519 private seed must be $SEED_SIZE bytes, was ${privateSeed.size}"
        }
        val signer = Ed25519Signer()
        signer.init(true, Ed25519PrivateKeyParameters(privateSeed, 0))
        signer.update(message, 0, message.size)
        return signer.generateSignature()
    }

    /** Verifies [signature] over [message] against [publicKey]. */
    fun verify(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean {
        require(publicKey.size == PUBLIC_KEY_SIZE) {
            "Ed25519 public key must be $PUBLIC_KEY_SIZE bytes, was ${publicKey.size}"
        }
        val verifier = Ed25519Signer()
        verifier.init(false, Ed25519PublicKeyParameters(publicKey, 0))
        verifier.update(message, 0, message.size)
        return verifier.verifySignature(signature)
    }
}
