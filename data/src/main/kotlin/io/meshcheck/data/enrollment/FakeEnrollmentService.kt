package io.meshcheck.data.enrollment

import java.security.SecureRandom

/**
 * A stand-in [EnrollmentService] used until the platform's enrollment-token
 * redeem endpoint is specified and built.
 *
 * Any non-blank token "succeeds" and mints a local **test** identity — a
 * `node_test_…` id and an `mck_test_…` API key. A blank token fails, so the
 * error path stays exercised in the UI. Nothing here talks to the network.
 */
class FakeEnrollmentService : EnrollmentService {

    override suspend fun redeem(token: String, ed25519PublicKey: ByteArray): EnrollmentResult {
        if (token.isBlank()) {
            return EnrollmentResult.Failure(
                EnrollmentError.INVALID_TOKEN,
                "The QR code did not contain an enrollment token.",
            )
        }
        return EnrollmentResult.Success(
            nodeId = "node_test_" + hex(ed25519PublicKey).take(8),
            apiKey = "mck_test_" + randomHex(16),
        )
    }

    private fun randomHex(byteCount: Int): String =
        hex(ByteArray(byteCount).also { SecureRandom().nextBytes(it) })

    private fun hex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it) }
}
