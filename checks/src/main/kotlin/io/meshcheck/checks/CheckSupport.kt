package io.meshcheck.checks

import meshcheck.agent.v1.ResultOutcome
import org.json.JSONException
import org.json.JSONObject

internal const val DEFAULT_TIMEOUT_SECONDS = 10
internal const val MIN_TIMEOUT_SECONDS = 1
internal const val MAX_TIMEOUT_SECONDS = 60

/** Slack the coroutine-level timeout allows beyond the I/O-level timeout. */
internal const val TIMEOUT_GRACE_MS = 2_000L

/**
 * Decodes a `TaskAssignment`'s parameters JSON. An empty payload means "use
 * defaults" and yields an empty object; unparseable JSON yields `null`, which
 * callers map to an INCONCLUSIVE result.
 */
internal fun parseParameters(parametersJson: ByteArray): JSONObject? = try {
    if (parametersJson.isEmpty()) {
        JSONObject()
    } else {
        JSONObject(String(parametersJson, Charsets.UTF_8))
    }
} catch (e: JSONException) {
    null
}

/** The `timeout_seconds` parameter shared by every check — 1..60, default 10. */
internal fun JSONObject.timeoutSeconds(): Int =
    optInt("timeout_seconds", DEFAULT_TIMEOUT_SECONDS)
        .coerceIn(MIN_TIMEOUT_SECONDS, MAX_TIMEOUT_SECONDS)

/** Builds measurement JSON bytes — the verbatim bytes that get signed and sent. */
internal fun measurements(build: JSONObject.() -> Unit): ByteArray =
    JSONObject().apply(build).toString().toByteArray(Charsets.UTF_8)

/** Milliseconds (fractional) elapsed since a `System.nanoTime()` reading. */
internal fun elapsedMs(startNanos: Long): Double =
    (System.nanoTime() - startNanos) / 1_000_000.0

/** The check could not be run at all — parameters invalid, type unknown. */
internal fun inconclusive(message: String): CheckResult = CheckResult(
    ResultOutcome.RESULT_OUTCOME_INCONCLUSIVE,
    measurements { put("error", message) },
)

/** The check exceeded its deadline. */
internal fun timeoutResult(message: String): CheckResult = CheckResult(
    ResultOutcome.RESULT_OUTCOME_TIMEOUT,
    measurements { put("error", message) },
)
