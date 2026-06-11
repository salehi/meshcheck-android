package io.meshcheck.checks

/**
 * JNI bridge to the native ICMP traceroute engine (`libmeshcheck_ping.so`,
 * `src/main/cpp/traceroute.c`).
 *
 * The native side is a dumb probe engine: it walks TTLs and collects raw
 * per-hop data. All interpretation — the measurement JSON, the outcome rule,
 * the RTT statistics — lives in [PingCheck], so the byte-exact output stays
 * consistent with the other executors.
 */
internal object PingNative {
    init { System.loadLibrary("meshcheck_ping") }

    /**
     * Runs a traceroute to [targetIpv4] (a resolved IPv4 dotted-quad).
     *
     * @param maxTtl maximum hop count to probe (the platform agent uses 30).
     * @param probesPerHop echoes sent per TTL (the platform agent uses 3).
     * @param deadlineEpochMs absolute wall-clock deadline (unix millis) for the
     *   whole traceroute — the engine never exceeds it.
     */
    external fun traceroute(
        targetIpv4: String,
        maxTtl: Int,
        probesPerHop: Int,
        deadlineEpochMs: Long,
    ): PingNativeResult
}

/**
 * Raw traceroute output, populated field-by-field from native code (hence the
 * [JvmField] fields and the no-arg constructor with defaults). Hops are
 * flattened into parallel arrays to keep the JNI boundary simple:
 *
 * - hop *i* has TTL [hopTtl]`[i]`, router [hopIp]`[i]` (null = no answer at that
 *   distance), and [hopIsTarget]`[i]`.
 * - hop *i*'s per-probe RTTs are `rttFlat[rttOffset[i] until rttOffset[i + 1]]`.
 *
 * [setupError] non-null means the engine never ran (socket/parse failure) and
 * every other field is at its default.
 */
internal class PingNativeResult {
    @JvmField var setupError: String? = null
    @JvmField var reached: Boolean = false
    @JvmField var timedOut: Boolean = false
    @JvmField var hopCount: Int = 0
    @JvmField var hopTtl: IntArray = IntArray(0)
    @JvmField var hopIp: Array<String?> = arrayOfNulls(0)
    @JvmField var hopIsTarget: BooleanArray = BooleanArray(0)
    @JvmField var rttFlat: DoubleArray = DoubleArray(0)
    @JvmField var rttOffset: IntArray = IntArray(0)
}
