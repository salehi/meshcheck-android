package io.meshcheck.checks

import meshcheck.agent.v1.ResultOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Covers the pure decision logic of the executors — parameter parsing and
 * outcome rules. The network execution itself is exercised on-device.
 */
class CheckLogicTest {

    @Test
    fun httpOutcome_anyTwoXxPassesWhenNoExpectedStatus() {
        assertEquals(ResultOutcome.RESULT_OUTCOME_PASS, httpOutcome(expectedStatus = 0, actualStatus = 200))
        assertEquals(ResultOutcome.RESULT_OUTCOME_PASS, httpOutcome(expectedStatus = 0, actualStatus = 204))
        assertEquals(ResultOutcome.RESULT_OUTCOME_FAIL, httpOutcome(expectedStatus = 0, actualStatus = 301))
        assertEquals(ResultOutcome.RESULT_OUTCOME_FAIL, httpOutcome(expectedStatus = 0, actualStatus = 404))
    }

    @Test
    fun httpOutcome_exactMatchWhenExpectedStatusGiven() {
        assertEquals(ResultOutcome.RESULT_OUTCOME_PASS, httpOutcome(expectedStatus = 301, actualStatus = 301))
        assertEquals(ResultOutcome.RESULT_OUTCOME_FAIL, httpOutcome(expectedStatus = 200, actualStatus = 204))
    }

    @Test
    fun timeoutSeconds_defaultsToTenAndClampsToRange() {
        assertEquals(10, parseParameters(ByteArray(0))!!.timeoutSeconds())
        assertEquals(5, parseParameters("""{"timeout_seconds":5}""".toByteArray())!!.timeoutSeconds())
        assertEquals(60, parseParameters("""{"timeout_seconds":120}""".toByteArray())!!.timeoutSeconds())
        assertEquals(1, parseParameters("""{"timeout_seconds":0}""".toByteArray())!!.timeoutSeconds())
    }

    @Test
    fun parseParameters_emptyMeansDefaults_invalidJsonIsNull() {
        assertNotNull(parseParameters(ByteArray(0)))
        assertNotNull(parseParameters("{}".toByteArray()))
        assertNull(parseParameters("not json".toByteArray()))
    }
}
