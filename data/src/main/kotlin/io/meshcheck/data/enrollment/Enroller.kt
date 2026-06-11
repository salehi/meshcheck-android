package io.meshcheck.data.enrollment

import io.meshcheck.core.crypto.Ed25519
import io.meshcheck.core.crypto.Ed25519KeyPair
import io.meshcheck.core.diagnostics.AppLog
import io.meshcheck.data.CredentialStore

/**
 * Drives the device side of enrollment.
 *
 * Enrollment is **entirely local**: the dashboard already created the Node when
 * it issued the QR, and the device-enrollment JWT carried in the QR *is* the
 * gateway credential — the device presents it as the `Authorization: Bearer`
 * token, exactly like a Node API key (there is no separate redeem step). So
 * enrollment only has to: parse the QR, read which Node the token is for,
 * generate the on-device Ed25519 signing keypair, and persist all of it
 * together with the deployment's gateway URL.
 *
 * The keypair is generated here and only persisted on success — a parse that
 * fails leaves no key material behind. The public key is registered with the
 * platform later, in the `ClientHello` of the first gateway connection.
 */
class Enroller(
    private val credentialStore: CredentialStore,
) {
    // A keypair generated ahead of the scan by [prewarm], consumed by the next
    // [enroll]. Both the keypair and the Keystore wrapping key are then ready
    // before a QR is decoded, so enrollment is just parse + persist.
    @Volatile
    private var warmKeyPair: Ed25519KeyPair? = null

    /**
     * Generates the on-device signing keypair and creates the Keystore wrapping
     * key ahead of time, off the enrollment critical path. Safe to call
     * repeatedly (e.g. when the scanner opens) — it only does work once until
     * the next [enroll] consumes the result.
     */
    fun prewarm() {
        if (warmKeyPair == null) warmKeyPair = Ed25519.generate()
        credentialStore.prewarm()
    }

    /**
     * Enrolls this device from a [scannedQr] string. On [EnrollmentResult.Success]
     * the credential and gateway URL are already saved to [credentialStore]
     * when this returns.
     */
    fun enroll(scannedQr: String): EnrollmentResult {
        val payload = EnrollmentQr.parse(scannedQr)
        if (payload == null) {
            AppLog.w(TAG, "Scanned QR is not a recognizable MeshCheck enrollment code")
            return EnrollmentResult.Failure(
                EnrollmentError.INVALID_TOKEN,
                "This QR code isn't a MeshCheck enrollment code. On your dashboard, " +
                    "choose “Add an Android device” and scan the code it shows.",
            )
        }

        val nodeId = nodeIdFromDeviceToken(payload.token).orEmpty()
        AppLog.i(TAG, "Enrolling node ${nodeId.ifBlank { "(id unknown)" }}; gateway ${payload.gatewayUrl}")

        val keyPair = warmKeyPair ?: Ed25519.generate()
        // The bearer credential is the JWT itself; it is stored where the API
        // key would have gone and used verbatim in the WebSocket Authorization.
        credentialStore.save(nodeId, payload.token, payload.gatewayUrl, keyPair)
        warmKeyPair = null
        AppLog.i(TAG, "Enrolled; signing key generated and credential stored")

        return EnrollmentResult.Success(nodeId = nodeId, apiKey = payload.token)
    }

    private companion object {
        const val TAG = "Enroll"
    }
}
