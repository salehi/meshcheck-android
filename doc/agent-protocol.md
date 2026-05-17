# Agent Protocol

This document defines the contract between a MeshCheck Node (the agent running on a VPS or a Contributor's device) and the Platform. It covers transport, authentication, the message schema, the connection lifecycle, and the rules each side must obey for the system to remain coherent.

The protocol is the most consequential contract in the project. Both sides — agent and platform — generate their code from the same source of truth (the `.proto` file referenced below). Any change to it ripples through both codebases and through every deployed Node. Changes must therefore be deliberate, backward-compatible where possible, and versioned.

---

## Transport

**Transport stack, from bottom to top:**

1. **TLS 1.3** — established when the agent dials the platform's gateway over `wss://`.
2. **HTTP/1.1 with Upgrade to WebSocket** — the WebSocket handshake carries the agent's API key and the requested protocol version.
3. **WebSocket framing** — each binary frame carries exactly one Protobuf `Envelope` message. WebSocket already delimits messages, so no additional length-prefix or gRPC 5-byte framing is used.
4. **Protobuf messages** — defined in [Message Schema](#message-schema) below.

**Why this stack:** WebSocket transport ensures the protocol traverses corporate proxies, home routers, captive portals, and mobile NATs. Protobuf gives typed, code-generated messages on both sides. Flow control is handled at the application level by the `FlowControl` message rather than by a transport layer.

> **A note on the name.** This transport is referred to elsewhere in the docs as "gRPC-over-WebSocket". That name describes the *interaction model* — a long-lived, bidirectional stream of typed messages — not a literal gRPC-over-HTTP/2 wire format. The concrete wire format is one framed Protobuf `Envelope` per WebSocket binary frame, with no gRPC runtime on either side. Only the generated Protobuf message structs are needed; there are no generated gRPC service stubs.

**Endpoint:** `wss://gateway.meshcheck.io/agent` (the gateway is horizontally scalable and sits behind a load balancer that supports WebSocket connection affinity).

---

## Authentication

### API Key as Identity

Every Node has exactly one API key, issued at registration. The API key carries the Node's identity: looking it up yields `node_id`, `organization_id` (the Contributor or operator), `capabilities`, and authorization status. **The API key is the sole credential** — no JWT, no mutual TLS at the application layer (TLS at the transport layer is still required).

**Format:** `mck_<env>_<32_random_chars>` where `<env>` is `live` or `test`. The prefix makes leaked keys easy to spot in logs and source control.

**Storage:**
- On the agent side: stored in a configuration file with restrictive permissions (`0600`).
- On the platform side: stored as a hash (Argon2id or equivalent) plus a one-way HMAC-tagged lookup digest that allows constant-time key resolution without storing plaintext.

### Connection Handshake

The agent initiates the WebSocket upgrade with the following headers:

```
GET /agent HTTP/1.1
Host: gateway.meshcheck.io
Upgrade: websocket
Connection: Upgrade
Sec-WebSocket-Version: 13
Sec-WebSocket-Key: <generated>
Sec-WebSocket-Protocol: meshcheck.agent.v1
Authorization: Bearer mck_live_<32_random_chars>
X-Agent-Version: 1.4.2
X-Agent-Platform: linux/amd64
```

The platform:
1. Validates the `Authorization` header. If the key is missing, malformed, or unknown, the upgrade is rejected with `401 Unauthorized` and the connection is closed.
2. Validates the protocol version requested in `Sec-WebSocket-Protocol`. If unsupported, the upgrade is rejected with `426 Upgrade Required` and a header indicating the supported versions.
3. Looks up the Node identity, confirms it is not suspended or revoked, and records the connection in the Redis-backed connection registry: `node_id → {instance_id, since: ts}`.
4. Completes the WebSocket upgrade.
5. Sends the first message, a `ServerHello`, before any other traffic.

The agent must receive `ServerHello` before sending any other message. If it does not arrive within 5 seconds, the agent closes and reconnects.

---

## Message Schema

The full `.proto` definition lives in the source tree at `proto/agent.proto`. The shape below is the canonical reference; treat it as authoritative for design decisions until the file exists.

```proto
syntax = "proto3";

package meshcheck.agent.v1;

// === Outer envelope ===

// Every message in the bidirectional stream is wrapped in this envelope.
// The oneof determines what kind of message it is. New message types can
// be added without breaking older clients because oneof tolerates unknown
// fields.
message Envelope {
  string message_id = 1;        // ULID; used for ACK correlation
  int64 sent_at = 2;            // unix milliseconds, sender's clock

  oneof body {
    ServerHello server_hello = 10;
    ClientHello client_hello = 11;
    Heartbeat heartbeat = 12;
    CapabilityUpdate capability_update = 13;

    TaskAssignment task = 20;
    TaskAck task_ack = 21;
    TaskCancel task_cancel = 22;

    ResultSubmit result = 30;
    ResultAck result_ack = 31;

    FlowControl flow_control = 40;
    Shutdown shutdown = 41;
    Error error = 42;
  }
}

// === Lifecycle messages ===

message ServerHello {
  string node_id = 1;
  string instance_id = 2;       // agent-gateway instance handling this connection
  int32 heartbeat_interval_seconds = 3;
  int32 max_concurrent_tasks = 4;
  repeated string supported_check_types = 5;
}

message ClientHello {
  string agent_version = 1;
  string platform = 2;          // "linux/amd64", "windows/amd64", etc.
  Capabilities capabilities = 3;
  bytes ed25519_pubkey = 4;     // the Node's Result-signing public key
}

message Heartbeat {
  // Sent by the agent every heartbeat_interval_seconds.
  int32 current_load = 1;       // current concurrent tasks executing
  int32 cpu_percent = 2;
  int64 memory_bytes = 3;
}

message CapabilityUpdate {
  Capabilities capabilities = 1;
}

message Capabilities {
  repeated string supported_check_types = 1;  // "ping", "tcp", "http", ...
  bool can_send_icmp = 2;       // platform-permitted raw socket access
  GeoLocation geo = 3;          // self-declared by the Contributor (city, country)
  ConnectionClass connection_class = 4;  // self-declared

  // Note: ASN is NOT declared by the agent. The Platform resolves the Node's
  // ASN authoritatively from the source IP of the inbound WebSocket connection,
  // using a MaxMind GeoLite2/ASN MMDB lookup. See doc/architecture/trust-and-scoring.md.
}

enum ConnectionClass {
  CONNECTION_CLASS_UNKNOWN = 0;
  CONNECTION_CLASS_VPS = 1;
  CONNECTION_CLASS_RESIDENTIAL_WIRED = 2;
  CONNECTION_CLASS_RESIDENTIAL_WIRELESS = 3;
  CONNECTION_CLASS_OFFICE = 4;
  CONNECTION_CLASS_MOBILE = 5;
}

message GeoLocation {
  string country_code = 1;      // ISO 3166-1 alpha-2
  string region = 2;
  string city = 3;
}

// === Task delivery ===

message TaskAssignment {
  string task_id = 1;           // ULID, unique
  string check_id = 2;          // parent Check
  string check_type = 3;        // "ping", "http", ...
  bytes parameters = 4;         // JSON-encoded, type-specific (matches checks.parameters jsonb; see schema.md)
  int64 deadline = 5;           // unix milliseconds; agent must complete by this
  int32 max_attempts = 6;
  string target = 7;            // hostname, IP, or URL to check (matches checks.target)
}

message TaskAck {
  string task_id = 1;
  TaskAckStatus status = 2;
}

enum TaskAckStatus {
  TASK_ACK_UNKNOWN = 0;
  TASK_ACK_ACCEPTED = 1;        // agent will execute
  TASK_ACK_REJECTED_OVERLOAD = 2;
  TASK_ACK_REJECTED_INCAPABLE = 3;
  TASK_ACK_REJECTED_INVALID = 4;
}

message TaskCancel {
  string task_id = 1;
  string reason = 2;
}

// === Result submission ===

message ResultSubmit {
  string task_id = 1;
  string check_id = 2;
  ResultOutcome outcome = 3;
  bytes measurements = 4;       // JSON-encoded, type-specific (matches results.measurements jsonb; see schema.md)
  int64 started_at = 5;         // unix milliseconds
  int64 completed_at = 6;
  bytes signature = 7;          // Ed25519 signature over a canonical hash of fields 1-6
}

enum ResultOutcome {
  RESULT_OUTCOME_UNKNOWN = 0;
  RESULT_OUTCOME_PASS = 1;
  RESULT_OUTCOME_FAIL = 2;
  RESULT_OUTCOME_INCONCLUSIVE = 3;
  RESULT_OUTCOME_TIMEOUT = 4;
}

message ResultAck {
  string task_id = 1;
  bool persisted = 2;
}

// === Operational ===

message FlowControl {
  int32 max_inflight_tasks = 1; // dynamically adjusted by platform
}

message Shutdown {
  ShutdownReason reason = 1;
  int32 grace_seconds = 2;
}

enum ShutdownReason {
  SHUTDOWN_REASON_UNKNOWN = 0;
  SHUTDOWN_REASON_PLATFORM_RESTART = 1;
  SHUTDOWN_REASON_NODE_SUSPENDED = 2;
  SHUTDOWN_REASON_KEY_REVOKED = 3;
  SHUTDOWN_REASON_PROTOCOL_VIOLATION = 4;
}

message Error {
  string code = 1;
  string message = 2;
  string correlation_id = 3;    // optional; ties to a specific message_id
}
```

---

## Identity and Result Signing

### Node Keypair

In addition to its API key (used for authentication), each Node holds an **Ed25519 keypair** used to sign Results. The private key never leaves the Node. The public key is carried in the `ClientHello` (`ed25519_pubkey`) and registered by the platform on the Node's first connection; it is immutable thereafter and persists across reconnects.

**Why a separate keypair, not the API key?**
- The API key authenticates the connection. The signing key proves the Result was produced by the same Node that the platform believes it was — even after the Result has been persisted, archived, and audited months later, without needing to re-check API key state.
- If an API key is compromised, the attacker cannot retroactively forge Results signed before the compromise, because the signing key is independent.

### Canonical Result Hash

The signature in `ResultSubmit.signature` is an Ed25519 signature over a canonical serialization of fields 1–6 of `ResultSubmit`. The canonical form is defined as:

```
task_id || check_id || outcome (uint32 BE) || measurements (raw bytes) || started_at (int64 BE) || completed_at (int64 BE)
```

The platform verifies every signature on receipt. A Result whose signature fails verification is discarded and does not enter the system. There is no Reliability Score, so a discard carries no scoring penalty — see [trust-and-scoring.md](../architecture/trust-and-scoring.md).

---

## The Conversation

### Normal Operation

```
agent                                   platform
  │                                       │
  │── WS upgrade (Authorization: Bearer) ─▶│
  │◀───── WS 101 Switching Protocols ─────│
  │                                       │
  │◀─────────── ServerHello ──────────────│
  │────────── ClientHello ───────────────▶│
  │                                       │
  │── Heartbeat ───── every 30s ─────────▶│
  │                                       │
  │◀────────── TaskAssignment ────────────│
  │── TaskAck (ACCEPTED) ────────────────▶│
  │                                       │
  │              (executes check)         │
  │                                       │
  │── ResultSubmit (signed) ─────────────▶│
  │◀────────── ResultAck ─────────────────│
  │                                       │
  │              (continues...)           │
```

### Heartbeats

Once `ServerHello` defines `heartbeat_interval_seconds`, the agent must send a `Heartbeat` at that cadence. If three consecutive heartbeats are missed, the platform considers the connection dead and closes it. In-flight Tasks transition to `timed_out`.

### Capability Updates

The agent may send a `CapabilityUpdate` at any time after `ClientHello` — for example, if its network conditions change (it can no longer send ICMP, or it has connected to a different Wi-Fi network with a different geographic profile). The platform updates the Node's capability record and adjusts which Task types it is eligible for going forward.

### Flow Control

The platform may at any time send a `FlowControl` message reducing the agent's `max_inflight_tasks`. The agent must respect the new limit immediately: if it currently has more tasks in flight than the new limit, it continues executing them but accepts no new tasks until it is below the threshold.

The platform uses `FlowControl` to:
- Reduce dispatch to a Node that is reporting high load
- Throttle Nodes that have begun returning anomalous Results (a slowdown, not an immediate cutoff)
- Recover from sudden global traffic spikes

### Cancellation

The platform may send `TaskCancel` for a Task it no longer needs — for example, if enough other Nodes have already returned Results to reach consensus. The agent should stop executing the cancelled Task if possible. Cancelled Tasks for which the agent has already invested effort but not yet submitted a Result simply produce no Result; the platform records this and does not penalize the Node.

### Shutdown

The platform may close a connection cleanly by sending `Shutdown` with a reason and a grace period. The agent should finish in-flight Tasks if the grace period allows, then disconnect. The agent must not reconnect until the grace period has elapsed.

---

## Disconnection and Re-dispatch

The protocol does **not** queue Tasks for offline Nodes. The rationale and full behavior:

- When a connection drops, the platform marks any in-flight Tasks for that Node as `timed_out`. The Dispatcher re-dispatches them to other eligible Nodes.
- When the agent reconnects (a fresh `ServerHello` / `ClientHello` exchange), it receives only **new** Tasks. There is no "catch-up" of Tasks it missed while offline.
- The reasoning: a Check that is 90 seconds late is no longer useful for the Requester. Re-dispatching to a different Node within seconds is materially better than waiting for the original Node to reconnect.

A disconnect that is shorter than a single heartbeat interval may not be observed by the platform, and any in-flight Tasks remain attributed to the agent. The agent must be prepared to receive `ResultAck` (or `TaskCancel`) for Tasks whose state crossed the gap.

---

## Versioning and Forward Compatibility

### Subprotocol Token

The WebSocket subprotocol token (`Sec-WebSocket-Protocol: meshcheck.agent.v1`) declares the major version. The platform supports at least the current version and the previous major version simultaneously, giving Contributors a window to update their agents.

A new major version is required for **breaking** changes: removed messages or fields, renumbered fields, changed semantics.

### Proto Evolution

Within a major version, the protocol evolves via additive changes only:
- New message types added to the `Envelope` oneof — older agents ignore unknown variants.
- New fields added to existing messages with new tag numbers — older agents ignore unknown fields.
- New `enum` values added at the end — older agents see them as the zero value (`UNKNOWN`) and must handle this gracefully.

### Rolling Out a New Version

1. Platform begins supporting `vN+1` alongside `vN`. Agents continue to negotiate `vN`.
2. Agent binary releases default to `vN+1`. They will be accepted by the platform.
3. After a transition period (e.g., 90 days), the platform deprecates `vN`. Agents still on `vN` receive a warning during `ServerHello` (in a free-text field reserved for this purpose).
4. After a longer period (e.g., 180 days), the platform removes `vN` support. Agents on `vN` fail the upgrade with `426 Upgrade Required`.

---

## Errors and Misbehavior

### Protocol-Level Errors

Any of the following result in the platform sending a `Shutdown` with reason `PROTOCOL_VIOLATION` and disconnecting:

- A message arrives before `ClientHello`.
- A `ClientHello` arrives more than once on the same connection.
- A `ResultSubmit` references a `task_id` that was never assigned to this Node.
- The agent exceeds `max_inflight_tasks` after a `FlowControl` directive.

A `ResultSubmit` whose Ed25519 signature fails verification is **not** a protocol violation: the Result is discarded and does not enter the system, but the connection stays open and the Node is not penalized. A failed signature may be a benign bug in an outdated agent rather than misbehavior. See [trust-and-scoring.md](../architecture/trust-and-scoring.md).

The agent may not reconnect until at least 60 seconds have passed after a protocol-violation shutdown. Repeated violations within a short window trigger a longer cooldown.

### Application-Level Errors

The `Error` message carries non-fatal errors — for example, a malformed `parameters` field in a `TaskAssignment` that the agent cannot decode. These are observed for debugging; they do not close the connection.

---

## Observability

Every connection has a `correlation_id` written into every log line on both sides. Every `Envelope` carries a `message_id` (ULID) so that any error or anomaly can be traced back to the exact message that produced it. Heartbeats include load and resource metrics that feed the dispatcher's load-balancing decisions.

---

## What This Document Does Not Cover

- *How the Dispatcher selects which Node receives a Task* → `doc/architecture/check-lifecycle.md`
- *How Results are aggregated and the `divergence_rate` diagnostic is tracked* → `doc/architecture/trust-and-scoring.md`
- *The database tables that persist the Tasks, Results, and Nodes referenced here* → `doc/schema/schema.md`
- *The threats this protocol is designed to resist* → `doc/security/threat-model.md`
