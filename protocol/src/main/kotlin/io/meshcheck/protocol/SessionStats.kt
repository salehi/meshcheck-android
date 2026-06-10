package io.meshcheck.protocol

/**
 * Rolling job counter for the current contribution session. Reset every time
 * [AgentClient.start] is called, so it counts work done since the user last
 * pressed Start — the figure the contributor screen shows.
 *
 * [confirmed] counts only results the platform acknowledged as persisted
 * (`ResultAck.persisted`) — the work that actually reaches the server and earns,
 * not results merely handed to the WebSocket.
 */
data class SessionStats(
    val confirmed: Int = 0,
)
