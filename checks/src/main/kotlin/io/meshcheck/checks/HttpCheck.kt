package io.meshcheck.checks

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import meshcheck.agent.v1.ResultOutcome
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit

/**
 * The `http` check: a `GET` or `HEAD` to the target URL, recording the status
 * code and timing. See doc/check-types.md § `http`.
 */
internal object HttpCheck {

    private val client: OkHttpClient by lazy { OkHttpClient() }

    suspend fun run(target: String, parametersJson: ByteArray): CheckResult {
        val params = parseParameters(parametersJson)
            ?: return inconclusive("invalid http parameters")
        val method = params.optString("method", "GET").uppercase()
        if (method != "GET" && method != "HEAD") {
            return inconclusive("unsupported http method: $method")
        }
        val expectedStatus = params.optInt("expected_status", 0)
        val timeoutMs = params.timeoutSeconds() * 1000L

        return withContext(Dispatchers.IO) {
            withTimeoutOrNull(timeoutMs + TIMEOUT_GRACE_MS) {
                execute(target, method, expectedStatus, timeoutMs)
            } ?: timeoutResult("request timed out")
        }
    }

    private fun execute(
        target: String,
        method: String,
        expectedStatus: Int,
        timeoutMs: Long,
    ): CheckResult {
        val call = client.newBuilder()
            .callTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .build()
            .newCall(Request.Builder().url(target).method(method, null).build())

        val startNanos = System.nanoTime()
        return try {
            call.execute().use { response ->
                val ttfbMs = elapsedMs(startNanos)
                // Drain the body so latency_ms covers the whole response.
                response.body?.bytes()
                CheckResult(
                    httpOutcome(expectedStatus, response.code),
                    measurements {
                        put("status_code", response.code)
                        put("latency_ms", elapsedMs(startNanos))
                        put("ttfb_ms", ttfbMs)
                    },
                )
            }
        } catch (e: InterruptedIOException) {
            // OkHttp's callTimeout (and socket timeouts) surface here.
            timeoutResult("request timed out")
        } catch (e: IOException) {
            CheckResult(
                ResultOutcome.RESULT_OUTCOME_FAIL,
                measurements {
                    put("error", e.message ?: "request failed")
                    put("latency_ms", elapsedMs(startNanos))
                },
            )
        }
    }
}

/**
 * PASS when the response status matches: [expectedStatus] exactly when it is
 * non-zero, otherwise any 2xx. FAIL otherwise.
 */
internal fun httpOutcome(expectedStatus: Int, actualStatus: Int): ResultOutcome {
    val matches = if (expectedStatus != 0) {
        actualStatus == expectedStatus
    } else {
        actualStatus in 200..299
    }
    return if (matches) ResultOutcome.RESULT_OUTCOME_PASS else ResultOutcome.RESULT_OUTCOME_FAIL
}
