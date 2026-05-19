package io.meshcheck.protocol

/**
 * The state of the agent's connection to the platform gateway. Observed by the
 * foreground service and surfaced (indirectly) in the contributor UI.
 */
sealed interface ConnectionState {

    /** Not connected and not trying to be. */
    data object Idle : ConnectionState

    /** Dialing the gateway / completing the handshake. */
    data object Connecting : ConnectionState

    /** Handshake done — receiving and running tasks. */
    data class Connected(val nodeId: String) : ConnectionState

    /** The connection dropped; a retry is scheduled in [retryInMillis]. */
    data class Reconnecting(val attempt: Int, val retryInMillis: Long) : ConnectionState

    /** Connecting has stopped and will not retry on its own — see [reason]. */
    data class Stopped(val reason: StopReason) : ConnectionState
}

/** Why the connection stopped and stayed stopped. */
enum class StopReason {

    /** The app asked to stop (the user pressed Stop). */
    REQUESTED,

    /** The API key was rejected (HTTP 401) — the device must re-enroll. */
    UNAUTHORIZED,

    /** The protocol version is no longer supported (HTTP 426) — update the app. */
    OUTDATED,

    /** The platform sent `Shutdown` with a terminal reason (suspended/revoked). */
    SHUTDOWN,
}
