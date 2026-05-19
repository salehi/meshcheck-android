package io.meshcheck.protocol

/**
 * Rolling job counters for the current contribution session. Reset every time
 * [AgentClient.start] is called, so they count work done since the user last
 * pressed Start — the figure the contributor screen shows.
 */
data class SessionStats(
    val received: Int = 0,
    val done: Int = 0,
)
