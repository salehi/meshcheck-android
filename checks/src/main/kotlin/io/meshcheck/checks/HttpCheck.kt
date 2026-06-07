package io.meshcheck.checks

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import meshcheck.agent.v1.ResultOutcome
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.io.InterruptedIOException
import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArrayList
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
        // Captured from DNS resolution so the result can report resolved_ips
        // (used on the platform to diagnose GeoDNS). See doc/check-types.md.
        val resolvedIps = CopyOnWriteArrayList<String>()
        val call = client.newBuilder()
            .callTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .eventListener(object : EventListener() {
                override fun dnsEnd(
                    call: Call,
                    domainName: String,
                    inetAddressList: List<InetAddress>,
                ) {
                    inetAddressList.forEach { it.hostAddress?.let(resolvedIps::add) }
                }
            })
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
                        putResolvedIps(resolvedIps)
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
                    putResolvedIps(resolvedIps)
                },
            )
        }
    }
}

/** Adds `resolved_ips` only when at least one address was resolved (the field
 *  is optional and omitted when unavailable). */
private fun JSONObject.putResolvedIps(ips: List<String>) {
    if (ips.isNotEmpty()) put("resolved_ips", JSONArray(ips))
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
