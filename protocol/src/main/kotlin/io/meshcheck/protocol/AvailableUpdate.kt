package io.meshcheck.protocol

/**
 * A newer app version the platform wants this node on, learned from an
 * `UpdateAvailable` message after `ClientHello`.
 *
 * On Android the agent cannot swap its own binary — the platform's
 * self-update path (signed manifest, binary swap, restart) does not apply.
 * Instead the app surfaces this as a nudge to update via Google Play or a new
 * APK, so a sideloaded build does not silently fall behind until the protocol
 * version is finally dropped (HTTP 426). The `manifest_url` is therefore
 * intentionally not carried here.
 */
data class AvailableUpdate(
    /** The semver the platform wants this agent on. */
    val targetVersion: String,
    /** The platform considers the update mandatory (vs. merely recommended). */
    val mandatory: Boolean,
)
