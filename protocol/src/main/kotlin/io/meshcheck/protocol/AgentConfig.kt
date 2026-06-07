package io.meshcheck.protocol

/**
 * Static configuration for the agent connection.
 *
 * [supportedCheckTypes] is what the app advertises in `ClientHello` — v1 ships
 * `http`, `tcp`, `dns` (`tls` deferred). The dispatcher then only ever sends
 * those types.
 *
 * [nodeName] is the self-declared, human-readable label sent in
 * `Capabilities.name` (need not be unique; an operator may override it on the
 * platform). Empty means "unnamed".
 */
data class AgentConfig(
    val agentVersion: String,
    val gatewayUrl: String = "wss://gateway.meshcheck.io/agent",
    val subprotocol: String = "meshcheck.agent.v1",
    val platform: String = "android",
    val supportedCheckTypes: List<String> = listOf("http", "tcp", "dns"),
    val nodeName: String = "",
)
