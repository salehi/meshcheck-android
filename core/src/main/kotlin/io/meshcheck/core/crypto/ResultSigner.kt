package io.meshcheck.core.crypto

import meshcheck.agent.v1.ResultOutcome
import java.io.ByteArrayOutputStream
import java.security.MessageDigest

/**
 * Builds the canonical byte serialization the platform defines for a
 * `ResultSubmit`, and Ed25519-signs the **SHA-256 digest** of it.
 *
 * Canonical form (agent-protocol.md, "Canonical Result Hash"):
 *
 * ```
 * task_id || check_id || outcome(uint32 BE) || measurements
 *         || started_at(int64 BE) || completed_at(int64 BE)
 * ```
 *
 * `task_id` and `check_id` are appended as raw UTF-8 bytes with no separator;
 * `measurements` is appended as the exact JSON bytes that go on the wire. The
 * signature is Ed25519 over **`SHA-256(canonical)`**, not over the raw preimage
 * — this mirrors the platform's `agent/pkg/resultsig`, where `Sign` and `Verify`
 * share one `Hash()`. The platform discards a Result whose signature does not
 * verify — and a discarded Result is unpaid work — so both this serialization
 * and the hash step must match the platform's byte-for-byte.
 */
object ResultSigner {

    /** The canonical bytes that get signed — fields 1..6 of `ResultSubmit`. */
    fun canonicalBytes(
        taskId: String,
        checkId: String,
        outcome: ResultOutcome,
        measurements: ByteArray,
        startedAt: Long,
        completedAt: Long,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(taskId.toByteArray(Charsets.UTF_8))
        out.write(checkId.toByteArray(Charsets.UTF_8))
        out.write(intBigEndian(outcome.value))
        out.write(measurements)
        out.write(longBigEndian(startedAt))
        out.write(longBigEndian(completedAt))
        return out.toByteArray()
    }

    /**
     * SHA-256 of [canonicalBytes] — the digest the platform actually signs and
     * persists (`results.signature_canonical_hash`).
     */
    fun canonicalHash(
        taskId: String,
        checkId: String,
        outcome: ResultOutcome,
        measurements: ByteArray,
        startedAt: Long,
        completedAt: Long,
    ): ByteArray = MessageDigest.getInstance("SHA-256").digest(
        canonicalBytes(taskId, checkId, outcome, measurements, startedAt, completedAt),
    )

    /** Ed25519 signature over [canonicalHash], using the 32-byte private seed. */
    fun sign(
        privateSeed: ByteArray,
        taskId: String,
        checkId: String,
        outcome: ResultOutcome,
        measurements: ByteArray,
        startedAt: Long,
        completedAt: Long,
    ): ByteArray = Ed25519.sign(
        privateSeed,
        canonicalHash(taskId, checkId, outcome, measurements, startedAt, completedAt),
    )

    private fun intBigEndian(value: Int): ByteArray = ByteArray(4) { i ->
        (value ushr (24 - i * 8)).toByte()
    }

    private fun longBigEndian(value: Long): ByteArray = ByteArray(8) { i ->
        (value ushr (56 - i * 8)).toByte()
    }
}
