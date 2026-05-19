package io.meshcheck.protocol

import java.security.SecureRandom

/**
 * Generates ULIDs for `Envelope.message_id` — a 48-bit millisecond timestamp
 * followed by 80 bits of randomness, rendered in Crockford base32 (26 chars).
 * Lexicographically sortable by creation time; uniqueness is all the protocol
 * needs for ACK correlation, so per-millisecond monotonicity is not enforced.
 */
internal object Ulid {

    private const val ENCODING = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
    private const val TIME_CHARS = 10
    private const val RANDOM_CHARS = 16

    private val random = SecureRandom()

    fun generate(): String {
        val builder = StringBuilder(TIME_CHARS + RANDOM_CHARS)

        var timestamp = System.currentTimeMillis()
        val time = CharArray(TIME_CHARS)
        for (i in TIME_CHARS - 1 downTo 0) {
            time[i] = ENCODING[(timestamp and 0x1F).toInt()]
            timestamp = timestamp shr 5
        }
        builder.append(time)

        repeat(RANDOM_CHARS) {
            builder.append(ENCODING[random.nextInt(ENCODING.length)])
        }
        return builder.toString()
    }
}
