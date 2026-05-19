package io.meshcheck.data.enrollment

/**
 * Redeems a QR enrollment token with the MeshCheck platform.
 *
 * The platform-side **enrollment-token redeem** endpoint does not exist yet
 * (see CLAUDE.md § "Platform dependencies"). This interface is the seam: the
 * rest of the app is built against it, and [FakeEnrollmentService] stands in
 * until the real HTTP implementation can be written against a specified
 * endpoint.
 *
 * Implementations must **return** an [EnrollmentResult.Failure] for expected
 * problems (bad token, network down) rather than throwing.
 */
interface EnrollmentService {

    /**
     * Exchanges a single-use [token] (scanned from the dashboard QR) plus the
     * device's [ed25519PublicKey] for a Node identity and its API key. The
     * platform registers the public key as the Node's immutable signing key.
     */
    suspend fun redeem(token: String, ed25519PublicKey: ByteArray): EnrollmentResult
}

/** The outcome of [EnrollmentService.redeem]. */
sealed interface EnrollmentResult {

    /** The token was redeemed; the device is now bound to a Node. */
    data class Success(val nodeId: String, val apiKey: String) : EnrollmentResult

    /** Redemption did not happen; [message] is safe to show the user. */
    data class Failure(val error: EnrollmentError, val message: String) : EnrollmentResult
}

/** Why an enrollment attempt failed. */
enum class EnrollmentError {
    /** Token unknown, malformed, already used, or expired. */
    INVALID_TOKEN,

    /** The platform could not be reached. */
    NETWORK,

    /** The platform was reached but returned an unexpected error. */
    SERVER,
}
