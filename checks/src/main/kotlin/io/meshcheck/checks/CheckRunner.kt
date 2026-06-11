package io.meshcheck.checks

/**
 * Runs a check by dispatching to the executor for its type.
 *
 * The app advertises `http`, `tcp`, `dns`, and — when the ICMP capability probe
 * passes — `ping` in `ClientHello`, so the dispatcher should never send another
 * type; any other value yielding INCONCLUSIVE is a defensive guard, not an
 * expected path.
 */
object CheckRunner {

    /**
     * Executes a check. [target] and [parametersJson] come straight from the
     * `TaskAssignment`. Never throws for an ordinary failure — every outcome,
     * including "could not run", comes back as a [CheckResult].
     */
    suspend fun run(
        checkType: String,
        target: String,
        parametersJson: ByteArray,
    ): CheckResult = when (checkType.lowercase()) {
        "http" -> HttpCheck.run(target, parametersJson)
        "tcp" -> TcpCheck.run(target, parametersJson)
        "dns" -> DnsCheck.run(target, parametersJson)
        "ping" -> PingCheck.run(target, parametersJson)
        else -> inconclusive("unsupported check type: $checkType")
    }
}
