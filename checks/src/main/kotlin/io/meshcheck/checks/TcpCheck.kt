package io.meshcheck.checks

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import meshcheck.agent.v1.ResultOutcome
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException

/**
 * The `tcp` check: opens a TCP connection to the target host and port,
 * recording whether it succeeded and how long it took. See
 * doc/check-types.md § `tcp`.
 */
internal object TcpCheck {

    private const val PORT_UNSET = -1

    suspend fun run(target: String, parametersJson: ByteArray): CheckResult {
        val params = parseParameters(parametersJson)
            ?: return inconclusive("invalid tcp parameters")
        val port = params.optInt("port", PORT_UNSET)
        if (port !in 1..65535) {
            return inconclusive("tcp check requires a port in 1..65535")
        }
        val timeoutMs = params.timeoutSeconds() * 1000L

        return withContext(Dispatchers.IO) {
            withTimeoutOrNull(timeoutMs + TIMEOUT_GRACE_MS) {
                connect(target, port, timeoutMs.toInt())
            } ?: timeoutResult("connect timed out")
        }
    }

    private fun connect(host: String, port: Int, timeoutMs: Int): CheckResult {
        val startNanos = System.nanoTime()
        return Socket().use { socket ->
            try {
                socket.connect(InetSocketAddress(host, port), timeoutMs)
                CheckResult(
                    ResultOutcome.RESULT_OUTCOME_PASS,
                    measurements {
                        put("connected", true)
                        put("latency_ms", elapsedMs(startNanos))
                    },
                )
            } catch (e: SocketTimeoutException) {
                timeoutResult("connect timed out")
            } catch (e: IOException) {
                // Connection refused, host unreachable, DNS failure, ...
                CheckResult(
                    ResultOutcome.RESULT_OUTCOME_FAIL,
                    measurements {
                        put("connected", false)
                        put("latency_ms", elapsedMs(startNanos))
                    },
                )
            }
        }
    }
}
