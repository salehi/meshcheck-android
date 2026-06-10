package io.meshcheck.data

/**
 * The identity this device holds once enrolled.
 *
 * The Ed25519 *private* seed is deliberately not a field here — it never
 * leaves [CredentialStore], which signs on the caller's behalf. This object
 * carries only what other layers legitimately need: the Node id, the API key
 * for the WebSocket `Authorization` header, the public key for `ClientHello`,
 * and the [gatewayUrl] of the deployment this device enrolled with.
 *
 * [gatewayUrl] is null only for installs enrolled before it was stored; the
 * agent then falls back to its compiled-in default.
 */
class NodeCredentials(
    val nodeId: String,
    val apiKey: String,
    val ed25519PublicKey: ByteArray,
    val gatewayUrl: String?,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is NodeCredentials) return false
        return nodeId == other.nodeId &&
            apiKey == other.apiKey &&
            ed25519PublicKey.contentEquals(other.ed25519PublicKey) &&
            gatewayUrl == other.gatewayUrl
    }

    override fun hashCode(): Int {
        var result = nodeId.hashCode()
        result = 31 * result + apiKey.hashCode()
        result = 31 * result + ed25519PublicKey.contentHashCode()
        result = 31 * result + (gatewayUrl?.hashCode() ?: 0)
        return result
    }
}
