package io.meshcheck.core.crypto

import meshcheck.agent.v1.ResultOutcome
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The signature must be byte-exact with the platform's, or the Result is
 * silently discarded. These tests pin the canonical serialization against a
 * hand-computed byte array so an accidental change to field order, encoding,
 * or separators fails loudly.
 */
class ResultSignerTest {

    @Test
    fun canonicalBytes_areTheExactConcatenationOfFields() {
        val actual = ResultSigner.canonicalBytes(
            taskId = "abc",
            checkId = "de",
            outcome = ResultOutcome.RESULT_OUTCOME_FAIL, // wire value 2
            measurements = "{}".toByteArray(Charsets.UTF_8),
            startedAt = 1L,
            completedAt = 258L, // 0x0102
        )

        val expected = byteArrayOf(
            0x61, 0x62, 0x63,             // "abc"  — task_id, raw UTF-8
            0x64, 0x65,                   // "de"   — check_id, raw UTF-8
            0, 0, 0, 2,                   // outcome — uint32 big-endian
            0x7b, 0x7d,                   // "{}"   — measurements, raw bytes
            0, 0, 0, 0, 0, 0, 0, 1,       // started_at   — int64 big-endian
            0, 0, 0, 0, 0, 0, 1, 2,       // completed_at — int64 big-endian
        )

        assertArrayEquals(expected, actual)
    }

    @Test
    fun sign_producesA64ByteSignatureThatVerifies() {
        val keyPair = Ed25519.generate()

        val signature = ResultSigner.sign(
            privateSeed = keyPair.privateSeed,
            taskId = "task-1",
            checkId = "check-1",
            outcome = ResultOutcome.RESULT_OUTCOME_PASS,
            measurements = """{"latency_ms":12.7}""".toByteArray(Charsets.UTF_8),
            startedAt = 1_700_000_000_000L,
            completedAt = 1_700_000_000_321L,
        )
        assertEquals(Ed25519.SIGNATURE_SIZE, signature.size)

        // The signature is over SHA-256(canonical), matching the platform.
        val digest = ResultSigner.canonicalHash(
            taskId = "task-1",
            checkId = "check-1",
            outcome = ResultOutcome.RESULT_OUTCOME_PASS,
            measurements = """{"latency_ms":12.7}""".toByteArray(Charsets.UTF_8),
            startedAt = 1_700_000_000_000L,
            completedAt = 1_700_000_000_321L,
        )
        assertTrue(Ed25519.verify(keyPair.publicKey, digest, signature))

        // Regression guard: it must be hash-then-sign — the signature must NOT
        // verify over the raw canonical preimage, or the platform rejects it.
        val rawCanonical = ResultSigner.canonicalBytes(
            taskId = "task-1",
            checkId = "check-1",
            outcome = ResultOutcome.RESULT_OUTCOME_PASS,
            measurements = """{"latency_ms":12.7}""".toByteArray(Charsets.UTF_8),
            startedAt = 1_700_000_000_000L,
            completedAt = 1_700_000_000_321L,
        )
        assertFalse(Ed25519.verify(keyPair.publicKey, rawCanonical, signature))
    }

    @Test
    fun verify_failsWhenMeasurementsAreTampered() {
        val keyPair = Ed25519.generate()

        val signature = ResultSigner.sign(
            privateSeed = keyPair.privateSeed,
            taskId = "t",
            checkId = "c",
            outcome = ResultOutcome.RESULT_OUTCOME_PASS,
            measurements = "{}".toByteArray(Charsets.UTF_8),
            startedAt = 1L,
            completedAt = 2L,
        )

        val tampered = ResultSigner.canonicalHash(
            taskId = "t",
            checkId = "c",
            outcome = ResultOutcome.RESULT_OUTCOME_PASS,
            measurements = """{"x":1}""".toByteArray(Charsets.UTF_8),
            startedAt = 1L,
            completedAt = 2L,
        )
        assertFalse(Ed25519.verify(keyPair.publicKey, tampered, signature))
    }
}
