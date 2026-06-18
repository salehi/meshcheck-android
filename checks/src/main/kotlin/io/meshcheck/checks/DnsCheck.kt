package io.meshcheck.checks

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import meshcheck.agent.v1.ResultOutcome
import org.json.JSONArray
import org.xbill.DNS.AAAARecord
import org.xbill.DNS.ARecord
import org.xbill.DNS.CNAMERecord
import org.xbill.DNS.Lookup
import org.xbill.DNS.MXRecord
import org.xbill.DNS.NSRecord
import org.xbill.DNS.Record
import org.xbill.DNS.ResolverConfig
import org.xbill.DNS.SimpleResolver
import org.xbill.DNS.TXTRecord
import org.xbill.DNS.TextParseException
import org.xbill.DNS.Type

/**
 * The `dns` check: resolves the target hostname for a given record type and
 * reports the records found. See doc/check-types.md § `dns`.
 *
 * Uses dnsjava because Android's `InetAddress` only resolves A/AAAA, while the
 * check supports A, AAAA, CNAME, MX, TXT, and NS.
 */
internal object DnsCheck {

    private val recordTypes: Map<String, Int> = mapOf(
        "A" to Type.A,
        "AAAA" to Type.AAAA,
        "CNAME" to Type.CNAME,
        "MX" to Type.MX,
        "TXT" to Type.TXT,
        "NS" to Type.NS,
    )

    suspend fun run(target: String, parametersJson: ByteArray): CheckResult {
        val params = parseParameters(parametersJson)
            ?: return inconclusive("invalid dns parameters")
        val recordType = params.optString("record_type", "A").uppercase()
        val type = recordTypes[recordType]
            ?: return inconclusive("unsupported dns record type: $recordType")
        val timeoutMs = params.timeoutSeconds() * 1000L

        return withContext(Dispatchers.IO) {
            withTimeoutOrNull(timeoutMs) {
                resolve(target, recordType, type)
            } ?: timeoutResult("dns lookup timed out")
        }
    }

    private fun resolve(target: String, recordType: String, type: Int): CheckResult {
        val lookup = try {
            Lookup(target, type)
        } catch (e: TextParseException) {
            return inconclusive("invalid dns target: $target")
        }
        // Pin to an explicit resolver so the reported `nameserver` is provably
        // the server queried, rather than the default ExtendedResolver's pick.
        val server = ResolverConfig.getCurrentConfig().server()
        val nameserver = if (server != null) {
            lookup.setResolver(SimpleResolver(server))
            "${server.address.hostAddress}:${server.port}"
        } else {
            "system"
        }

        val startNanos = System.nanoTime()
        val records = lookup.run()
        val latencyMs = elapsedMs(startNanos)
        val rendered = records?.mapNotNull(::renderRecord).orEmpty()
        // An empty record set is a failure, not a pass (see check-types.md).
        val resolved = rendered.isNotEmpty()

        return CheckResult(
            if (resolved) ResultOutcome.RESULT_OUTCOME_PASS else ResultOutcome.RESULT_OUTCOME_FAIL,
            measurements {
                put("resolved", resolved)
                put("record_type", recordType)
                put("record_count", rendered.size)
                put("records", JSONArray(rendered))
                put("latency_ms", latencyMs)
                put("nameserver", nameserver)
            },
        )
    }
}

/** Renders one DNS record to its string form per doc/check-types.md. */
internal fun renderRecord(record: Record): String? = when (record) {
    is ARecord -> record.address.hostAddress
    is AAAARecord -> record.address.hostAddress
    is CNAMERecord -> record.target.toString()
    is MXRecord -> "${record.priority} ${record.target}"
    is NSRecord -> record.target.toString()
    is TXTRecord -> record.strings.joinToString(separator = "")
    else -> null
}
