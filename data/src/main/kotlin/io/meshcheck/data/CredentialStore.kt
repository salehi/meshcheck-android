package io.meshcheck.data

import android.content.Context
import android.util.Base64
import io.meshcheck.core.crypto.Ed25519KeyPair
import io.meshcheck.core.crypto.KeystoreEnvelope
import io.meshcheck.core.crypto.ResultSigner
import meshcheck.agent.v1.ResultOutcome

/**
 * Persists the Node's credentials across launches.
 *
 * The two secrets — the Node API key and the Ed25519 private seed — are
 * wrapped by [KeystoreEnvelope] before being written, so the SharedPreferences
 * file on its own never exposes key material. The Node id and Ed25519 public
 * key are not secret and are stored as plain (Base64) strings.
 *
 * The private seed never leaves this class: callers sign through [signResult]
 * rather than ever reading the seed back out.
 */
class CredentialStore(
    context: Context,
    private val envelope: KeystoreEnvelope = KeystoreEnvelope(context),
) {
    private val prefs =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** True once [save] has stored a set of credentials. */
    fun isEnrolled(): Boolean = prefs.contains(KEY_API_KEY)

    /**
     * Stores the credentials produced by enrollment. [keyPair] is generated
     * on-device; only its wrapped private seed and its plain public key are
     * kept — the seed in cleartext is never persisted.
     */
    fun save(nodeId: String, apiKey: String, gatewayUrl: String, keyPair: Ed25519KeyPair) {
        prefs.edit()
            .putString(KEY_NODE_ID, nodeId)
            .putString(KEY_API_KEY, encode(envelope.wrap(apiKey.toByteArray(Charsets.UTF_8))))
            .putString(KEY_GATEWAY_URL, gatewayUrl)
            .putString(KEY_PRIVATE_SEED, encode(envelope.wrap(keyPair.privateSeed)))
            .putString(KEY_PUBLIC_KEY, encode(keyPair.publicKey))
            .apply()
    }

    /** Loads the stored credentials, or null if the device is not enrolled. */
    fun load(): NodeCredentials? {
        val nodeId = prefs.getString(KEY_NODE_ID, null) ?: return null
        val wrappedApiKey = prefs.getString(KEY_API_KEY, null) ?: return null
        val publicKey = prefs.getString(KEY_PUBLIC_KEY, null) ?: return null
        return NodeCredentials(
            nodeId = nodeId,
            apiKey = String(envelope.unwrap(decode(wrappedApiKey)), Charsets.UTF_8),
            ed25519PublicKey = decode(publicKey),
            // Absent only for installs enrolled before the gateway URL was
            // carried in the QR; the agent then falls back to its default.
            gatewayUrl = prefs.getString(KEY_GATEWAY_URL, null),
        )
    }

    /**
     * Signs the canonical form of a `ResultSubmit` with the stored Ed25519
     * key. The private seed is unwrapped only for the duration of the call and
     * zeroed immediately afterwards, so it is never held longer than needed.
     */
    fun signResult(
        taskId: String,
        checkId: String,
        outcome: ResultOutcome,
        measurements: ByteArray,
        startedAt: Long,
        completedAt: Long,
    ): ByteArray {
        val wrappedSeed = prefs.getString(KEY_PRIVATE_SEED, null)
            ?: error("not enrolled — no Ed25519 signing key stored")
        val seed = envelope.unwrap(decode(wrappedSeed))
        try {
            return ResultSigner.sign(
                seed, taskId, checkId, outcome, measurements, startedAt, completedAt,
            )
        } finally {
            seed.fill(0)
        }
    }

    /**
     * Wipes every stored credential and deletes the Keystore envelope key —
     * the device-side half of Unlink. After this the device is unenrolled and
     * the old signing key is unrecoverable.
     */
    fun clear() {
        prefs.edit().clear().apply()
        envelope.deleteKey()
    }

    private fun encode(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)

    private fun decode(text: String): ByteArray = Base64.decode(text, Base64.NO_WRAP)

    private companion object {
        const val PREFS_NAME = "meshcheck.credentials"
        const val KEY_NODE_ID = "node_id"
        const val KEY_API_KEY = "api_key"
        const val KEY_GATEWAY_URL = "gateway_url"
        const val KEY_PRIVATE_SEED = "ed25519_private_seed"
        const val KEY_PUBLIC_KEY = "ed25519_public_key"
    }
}
