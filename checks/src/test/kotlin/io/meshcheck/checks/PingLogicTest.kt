package io.meshcheck.checks

import meshcheck.agent.v1.ResultOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the pure ping/traceroute logic — outcome rule, RTT statistics, the
 * packet-loss guard, measurement assembly, and the native-array unflatten. The
 * native traceroute and the ICMP capability probe are exercised on-device.
 */
class PingLogicTest {

    private val delta = 1e-6

    // --- outcome rule -------------------------------------------------------

    @Test
    fun pingOutcome_passOnlyWhenTargetAnswered() {
        assertEquals(
            ResultOutcome.RESULT_OUTCOME_PASS,
            pingOutcome(reached = true, finalRttCount = 1, timedOut = false),
        )
        // No partial-loss threshold: one reply of three is still a PASS.
        assertEquals(
            ResultOutcome.RESULT_OUTCOME_PASS,
            pingOutcome(reached = true, finalRttCount = 3, timedOut = false),
        )
    }

    @Test
    fun pingOutcome_timeoutVsFail() {
        assertEquals(
            ResultOutcome.RESULT_OUTCOME_TIMEOUT,
            pingOutcome(reached = false, finalRttCount = 0, timedOut = true),
        )
        // Route exhausted within budget → FAIL, not TIMEOUT.
        assertEquals(
            ResultOutcome.RESULT_OUTCOME_FAIL,
            pingOutcome(reached = false, finalRttCount = 0, timedOut = false),
        )
        // Reached but no usable RTT is a FAIL, not a PASS.
        assertEquals(
            ResultOutcome.RESULT_OUTCOME_FAIL,
            pingOutcome(reached = true, finalRttCount = 0, timedOut = false),
        )
    }

    // --- RTT statistics -----------------------------------------------------

    @Test
    fun populationStdDev_dividesByN() {
        val values = listOf(11.482, 12.164, 14.057)
        // population variance = mean of squared deviations (÷N, not ÷N-1)
        val mean = values.average()
        val expected = Math.sqrt(values.sumOf { (it - mean) * (it - mean) } / values.size)
        assertEquals(expected, populationStdDev(values), delta)
        assertEquals(1.0893, populationStdDev(values), 1e-3)
    }

    @Test
    fun populationStdDev_zeroForFewerThanTwoSamples() {
        assertEquals(0.0, populationStdDev(emptyList()), delta)
        assertEquals(0.0, populationStdDev(listOf(5.0)), delta)
    }

    // --- packet-loss guard --------------------------------------------------

    @Test
    fun packetLoss_isZeroNotHundredOnTotalMiss() {
        // No target hop → a dead target → packet_loss_pct must be 0, never 100.
        val m = buildPingMeasurements(
            hops = listOf(
                HopData(ttl = 1, ip = "192.168.1.1", rtts = listOf(0.5), target = false),
                HopData(ttl = 2, ip = null, rtts = emptyList(), target = false),
            ),
            resolvedIps = listOf("1.2.3.4"),
            probesPerHop = 3,
        )
        assertEquals(0, m.getInt("packets_sent"))
        assertEquals(0, m.getInt("packets_recv"))
        assertEquals(0.0, m.getDouble("packet_loss_pct"), delta)
        assertEquals(0.0, m.getDouble("rtt_avg_ms"), delta)
        // The dead-target shape is the full measurements, NOT the {"error":...} blob.
        assertFalse(m.has("error"))
        assertTrue(m.has("resolved_ips"))
        assertEquals(2, m.getJSONArray("hops").length())
    }

    @Test
    fun packetLoss_partialLossPercentage() {
        val m = buildPingMeasurements(
            hops = listOf(HopData(ttl = 1, ip = "1.2.3.4", rtts = listOf(5.0), target = true)),
            resolvedIps = listOf("1.2.3.4"),
            probesPerHop = 3,
        )
        assertEquals(3, m.getInt("packets_sent"))
        assertEquals(1, m.getInt("packets_recv"))
        assertEquals(66.6667, m.getDouble("packet_loss_pct"), 1e-3)
    }

    // --- measurement assembly ----------------------------------------------

    @Test
    fun buildPingMeasurements_matchesContractShape() {
        val m = buildPingMeasurements(
            hops = listOf(
                HopData(1, "192.168.1.1", listOf(0.512, 0.488, 0.503), target = false),
                HopData(2, null, emptyList(), target = false),
                HopData(3, "93.184.216.34", listOf(11.482, 12.164, 14.057), target = true),
            ),
            resolvedIps = listOf("93.184.216.34"),
            probesPerHop = 3,
        )

        // Top-level stats come from the final, target-reaching hop.
        assertEquals(3, m.getInt("packets_sent"))
        assertEquals(3, m.getInt("packets_recv"))
        assertEquals(0.0, m.getDouble("packet_loss_pct"), delta)
        assertEquals(11.482, m.getDouble("rtt_min_ms"), delta)
        assertEquals(14.057, m.getDouble("rtt_max_ms"), delta)
        assertEquals((11.482 + 12.164 + 14.057) / 3.0, m.getDouble("rtt_avg_ms"), delta)
        assertEquals(listOf("93.184.216.34"), listOf(m.getJSONArray("resolved_ips").getString(0)))

        val hops = m.getJSONArray("hops")
        assertEquals(3, hops.length())

        // A no-answer hop is just {"ttl":2}.
        val hop2 = hops.getJSONObject(1)
        assertEquals(2, hop2.getInt("ttl"))
        assertFalse(hop2.has("ip"))
        assertFalse(hop2.has("rtt_ms"))
        assertFalse(hop2.has("target"))

        // The target hop carries "target": true and its per-probe RTTs.
        val hop3 = hops.getJSONObject(2)
        assertTrue(hop3.getBoolean("target"))
        assertEquals("93.184.216.34", hop3.getString("ip"))
        assertEquals(3, hop3.getJSONArray("rtt_ms").length())
    }

    // --- native-array unflatten --------------------------------------------

    @Test
    fun toHops_unflattensParallelArrays() {
        val native = PingNativeResult().apply {
            reached = true
            hopCount = 2
            hopTtl = intArrayOf(1, 3)
            hopIp = arrayOf("10.0.0.1", "93.184.216.34")
            hopIsTarget = booleanArrayOf(false, true)
            rttFlat = doubleArrayOf(0.5, 0.6, 1.0)
            rttOffset = intArrayOf(0, 2, 3)
        }

        val hops = native.toHops()
        assertEquals(2, hops.size)
        assertEquals(HopData(1, "10.0.0.1", listOf(0.5, 0.6), target = false), hops[0])
        assertEquals(HopData(3, "93.184.216.34", listOf(1.0), target = true), hops[1])
    }
}
