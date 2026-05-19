package io.meshcheck.checks

import meshcheck.agent.v1.ResultOutcome

/**
 * The result of running a check.
 *
 * [measurementsJson] is the exact byte sequence to sign and submit as
 * `ResultSubmit.measurements` — it must be signed and sent verbatim, never
 * re-serialized, or the signature will not verify (see doc/check-types.md).
 */
class CheckResult(
    val outcome: ResultOutcome,
    val measurementsJson: ByteArray,
)
