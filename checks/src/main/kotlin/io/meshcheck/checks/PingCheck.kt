package io.meshcheck.checks

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import meshcheck.agent.v1.ResultOutcome
import org.json.JSONArray
import org.json.JSONObject
import java.net.Inet4Address
import java.net.InetAddress
import java.net.UnknownHostException
import kotlin.math.sqrt

/**
 * The `ping` check — which on this platform is a **traceroute** (see
 * doc/ping-check-contract.md). It walks TTLs 1..30 sending ICMP echoes, records
 * the route in `hops`, and reports the target-reaching hop's packet/RTT stats
 * at the top level. The probing itself is native ([PingNative]); this object
 * owns parameter handling, the outcome rule, and the byte-exact measurement
 * JSON.
 *
 * IPv4 only — the platform's reference agent resolves `ip4` and errors on
 * non-IPv4 targets, so we stay comparable to native Nodes.
 */
internal object PingCheck {

    private const val TRACE_MAX_TTL = 30
    private const val TRACE_PROBES = 3
    private const val MIN_COUNT = 1
    private const val MAX_COUNT = 20
    private const val DEFAULT_COUNT = 4

    suspend fun run(target: String, parametersJson: ByteArray): CheckResult {
        val params = parseParameters(parametersJson)
            ?: return inconclusive("invalid ping parameters")
        // `count` is validated for protocol compatibility but is INERT: the
        // reference agent always sends TRACE_PROBES per hop and never scales
        // probing by count (see doc/ping-check-contract.md).
        val count = params.optInt("count", DEFAULT_COUNT)
        if (count !in MIN_COUNT..MAX_COUNT) {
            return inconclusive("ping count out of range: $count")
        }
        val timeoutMs = params.timeoutSeconds() * 1000L

        return withContext(Dispatchers.IO) {
            withTimeoutOrNull(timeoutMs + TIMEOUT_GRACE_MS) {
                execute(target, timeoutMs)
            } ?: timeoutResult("ping timed out")
        }
    }

    private fun execute(target: String, timeoutMs: Long): CheckResult {
        // Resolve to IPv4 only. No IPv4 address (incl. an IPv6-only target) is a
        // hard FAIL emitting the {"error":...} shape, matching the reference agent.
        val ipv4 = try {
            InetAddress.getAllByName(target).filterIsInstance<Inet4Address>().firstOrNull()
        } catch (e: UnknownHostException) {
            null
        }
        val resolvedIp = ipv4?.hostAddress
            ?: return CheckResult(
                ResultOutcome.RESULT_OUTCOME_FAIL,
                measurements { put("error", "no IPv4 address for $target") },
            )

        val native = try {
            PingNative.traceroute(resolvedIp, TRACE_MAX_TTL, TRACE_PROBES, timeoutMs)
        } catch (e: UnsatisfiedLinkError) {
            return inconclusive("native ping unavailable")
        }
        native.setupError?.let { return inconclusive(it) }

        val hops = native.toHops()
        val finalRttCount = hops.firstOrNull { it.target }?.rtts?.size ?: 0
        val measurements =
            buildPingMeasurements(hops, listOf(resolvedIp), TRACE_PROBES)
                .toString().toByteArray(Charsets.UTF_8)
        return CheckResult(pingOutcome(native.reached, finalRttCount, native.timedOut), measurements)
    }
}

/** One traceroute hop in plain form — the unit the measurement builder and
 *  tests work with, independent of the native ABI. */
internal data class HopData(
    val ttl: Int,
    val ip: String?,
    val rtts: List<Double>,
    val target: Boolean,
)

/** Unflattens [PingNativeResult]'s parallel arrays into [HopData]. */
internal fun PingNativeResult.toHops(): List<HopData> = (0 until hopCount).map { i ->
    HopData(
        ttl = hopTtl[i],
        ip = hopIp[i],
        rtts = (rttOffset[i] until rttOffset[i + 1]).map { rttFlat[it] },
        target = hopIsTarget[i],
    )
}

/**
 * PASS iff the target itself answered at least one probe; TIMEOUT iff it never
 * answered and the time budget ran out; FAIL otherwise (route exhausted within
 * budget). There is no partial-loss threshold — one reply is a PASS.
 */
internal fun pingOutcome(reached: Boolean, finalRttCount: Int, timedOut: Boolean): ResultOutcome =
    when {
        reached && finalRttCount > 0 -> ResultOutcome.RESULT_OUTCOME_PASS
        !reached && timedOut -> ResultOutcome.RESULT_OUTCOME_TIMEOUT
        else -> ResultOutcome.RESULT_OUTCOME_FAIL
    }

/** Population standard deviation (divide by N); 0.0 for fewer than two samples. */
internal fun populationStdDev(values: List<Double>): Double {
    if (values.isEmpty()) return 0.0
    val mean = values.average()
    return sqrt(values.sumOf { (it - mean) * (it - mean) } / values.size)
}

/**
 * Builds the `PingMeasurements` JSON (doc/ping-check-contract.md). Top-level
 * packet/RTT stats describe the target-reaching hop; the full route is in
 * `hops`. Keys are emitted in the contract's declaration order.
 *
 * A total miss (no target hop) reports `packets_sent: 0`, `packets_recv: 0`,
 * and crucially `packet_loss_pct: 0` (not 100) — the loss calc is guarded by
 * `packets_sent > 0` — plus the partial route in `hops`.
 */
internal fun buildPingMeasurements(
    hops: List<HopData>,
    resolvedIps: List<String>,
    probesPerHop: Int,
): JSONObject {
    val finalHop = hops.firstOrNull { it.target }
    val finalRtts = finalHop?.rtts.orEmpty()
    val packetsSent = if (finalHop != null) probesPerHop else 0
    val packetsRecv = finalRtts.size
    val lossPct = if (packetsSent > 0) (packetsSent - packetsRecv) * 100.0 / packetsSent else 0.0

    return JSONObject().apply {
        put("packets_sent", packetsSent)
        put("packets_recv", packetsRecv)
        put("packet_loss_pct", lossPct)
        put("rtt_min_ms", finalRtts.minOrNull() ?: 0.0)
        put("rtt_avg_ms", if (finalRtts.isNotEmpty()) finalRtts.average() else 0.0)
        put("rtt_max_ms", finalRtts.maxOrNull() ?: 0.0)
        put("rtt_stddev_ms", populationStdDev(finalRtts))
        if (resolvedIps.isNotEmpty()) put("resolved_ips", JSONArray(resolvedIps))
        if (hops.isNotEmpty()) put("hops", hopsJson(hops))
    }
}

/** Renders the ordered hop list, omitting absent fields per the contract
 *  (a no-answer hop is just `{"ttl":N}`; the target hop carries `"target":true`). */
private fun hopsJson(hops: List<HopData>): JSONArray = JSONArray().apply {
    for (hop in hops) {
        put(JSONObject().apply {
            put("ttl", hop.ttl)
            if (!hop.ip.isNullOrEmpty()) put("ip", hop.ip)
            if (hop.rtts.isNotEmpty()) put("rtt_ms", JSONArray(hop.rtts))
            if (hop.target) put("target", true)
        })
    }
}
