package io.meshcheck.protocol

/**
 * Static configuration for the agent connection.
 *
 * [supportedCheckTypes] is what the app advertises in `ClientHello` — `http`,
 * `tcp`, `dns`, plus `ping` when the ICMP capability probe passes (`tls`
 * deferred). The dispatcher then only ever sends those types.
 *
 * [canSendIcmp] mirrors that probe: true only when the device can open an
 * unprivileged ICMP socket. It is the `Capabilities.can_send_icmp` flag.
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
    val canSendIcmp: Boolean = false,
    val nodeName: String = "",
)
