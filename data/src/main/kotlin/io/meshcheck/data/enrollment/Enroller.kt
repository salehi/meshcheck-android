package io.meshcheck.data.enrollment

import io.meshcheck.core.crypto.Ed25519
import io.meshcheck.data.CredentialStore

/**
 * Drives the device side of enrollment: generate the on-device Ed25519
 * keypair, redeem the scanned token, and persist the resulting credentials.
 *
 * The keypair is generated here and only persisted on success — an attempt
 * that fails leaves no key material behind.
 */
class Enroller(
    private val service: EnrollmentService,
    private val credentialStore: CredentialStore,
) {
    /**
     * Enrolls this device with [token]. On [EnrollmentResult.Success] the
     * credentials are already saved to [credentialStore] when this returns.
     */
    suspend fun enroll(token: String): EnrollmentResult {
        val keyPair = Ed25519.generate()
        val result = service.redeem(token, keyPair.publicKey)
        if (result is EnrollmentResult.Success) {
            credentialStore.save(result.nodeId, result.apiKey, keyPair)
        }
        return result
    }
}
