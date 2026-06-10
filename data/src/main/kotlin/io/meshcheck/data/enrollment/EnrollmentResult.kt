package io.meshcheck.data.enrollment

/** The outcome of [Enroller.enroll]. */
sealed interface EnrollmentResult {

    /** The QR was accepted and the device is now bound to a Node. */
    data class Success(val nodeId: String, val apiKey: String) : EnrollmentResult

    /** Enrollment did not happen; [message] is safe to show the user. */
    data class Failure(val error: EnrollmentError, val message: String) : EnrollmentResult
}

/** Why an enrollment attempt failed. */
enum class EnrollmentError {
    /** The QR could not be parsed, or its token is malformed. */
    INVALID_TOKEN,

    /** The platform could not be reached. (Reserved; enrollment is local.) */
    NETWORK,

    /** An unexpected error while storing credentials. */
    SERVER,
}
