# Check Types — Parameter and Measurement Shapes

This is the language-neutral transcription of the MeshCheck platform's
`internal/checkspec` package (which is Go). It is the contract the app must
honor when it decodes a `TaskAssignment` and encodes a `ResultSubmit`.

On the wire (see [agent-protocol.md](agent-protocol.md)):

- `TaskAssignment.parameters` — a JSON object, the **parameters** shape below
  for that `check_type`. The app decodes it.
- `TaskAssignment.target` — the hostname / IP / URL to check.
- `ResultSubmit.measurements` — a JSON object, the **measurements** shape
  below. The app encodes it.
- `ResultSubmit.outcome` — one of the `ResultOutcome` enum values; the mapping
  rules are given per type.

All JSON keys are exactly as written here (snake_case). An absent or empty
`parameters` object means "use the defaults."

---

## Which types the app supports

The platform defines six check types: `ping`, `tcp`, `http`, `dns`, `tls`,
`smtp`. The Android app does **not** support all of them:

| Type | App support | Reason |
|---|---|---|
| `http` | ✅ v1 | Plain sockets / HTTP client — always available. |
| `tcp`  | ✅ v1 | Plain TCP connect — always available. |
| `dns`  | ✅ v1 | Standard resolver lookups — always available. |
| `tls`  | ⚠️ deferred | Viable on mobile, but deferred past v1 to land the check pipeline first. |
| `ping` | ✅ v1 (capability-gated) | A traceroute on an *unprivileged* ICMP datagram socket (`SOCK_DGRAM, IPPROTO_ICMP`) — not a raw socket. Native-implemented (the ICMP error queue is unavailable to `android.system.Os` before API 33). Advertised only when a runtime probe can open the socket. |
| `smtp` | ⚠️ deferred | TCP port 25 is widely blocked on mobile carriers; revisit later. |

The app advertises only the types it supports in `ClientHello.Capabilities.supported_check_types`,
and the dispatcher then only ever sends it those types. `ping` is added to that
set — and `can_send_icmp` set to true — **only when the ICMP capability probe
succeeds**; otherwise `ping` is omitted and `can_send_icmp` stays false.
Supporting a type = being able to decode its parameters, execute it, and encode
its measurements.

---

## Common rules

- `timeout_seconds` — present on every type. Range 1–60, default **10**. The
  whole check (including DNS resolution and connect) must finish within it.
- `RESULT_OUTCOME_TIMEOUT` — used when the failure is a deadline/timeout.
- `RESULT_OUTCOME_FAIL` — used when the failure is an outright one (connection
  refused, NXDOMAIN, untrusted certificate, wrong status code, ...).
- `RESULT_OUTCOME_INCONCLUSIVE` — used when the app could not run the check at
  all (e.g. parameters failed to decode). Include an `{"error": "..."}` object
  as the measurements so the failure is still visible.
- `RESULT_OUTCOME_PASS` — the check succeeded.
- Latency fields are **fractional milliseconds** (a float, e.g. `12.704`).

---

## `http`

**Parameters**

| Key | Type | Default | Notes |
|---|---|---|---|
| `method` | string | `"GET"` | `GET` or `HEAD` only (upper-cased). |
| `expected_status` | int | `0` | `0` means "any 2xx is a pass"; otherwise an exact code 100–599. |
| `timeout_seconds` | int | `10` | 1–60. |

`target` is the full URL.

**Measurements**

| Key | Type | Notes |
|---|---|---|
| `status_code` | int | The HTTP response status. |
| `latency_ms` | float | Total request time. |
| `ttfb_ms` | float | Time to first response byte. |
| `resolved_ips` | string[] | Optional. The IP address(es) the host resolved to; omitted when unavailable. The platform shows these on the result page to diagnose GeoDNS. |

**Outcome** — `PASS` if the status matches (`expected_status` if non-zero,
otherwise any 2xx); `FAIL` if it does not match or the request errored;
`TIMEOUT` if the request exceeded the deadline.

---

## `tcp`

**Parameters**

| Key | Type | Default | Notes |
|---|---|---|---|
| `port` | int | (required) | 1–65535. |
| `timeout_seconds` | int | `10` | 1–60. |

**Measurements**

| Key | Type | Notes |
|---|---|---|
| `connected` | bool | Whether the TCP connection opened. |
| `latency_ms` | float | Time to connect (or to fail). |

**Outcome** — `PASS` if the connection opened; `TIMEOUT` if the connect timed
out; `FAIL` otherwise (e.g. connection refused).

---

## `dns`

**Parameters**

| Key | Type | Default | Notes |
|---|---|---|---|
| `record_type` | string | `"A"` | One of `A`, `AAAA`, `CNAME`, `MX`, `TXT`, `NS` (upper-cased). |
| `timeout_seconds` | int | `10` | 1–60. |

`target` is the hostname to resolve.

**Measurements**

| Key | Type | Notes |
|---|---|---|
| `resolved` | bool | Whether any records were found. |
| `record_type` | string | Echo of the requested type. |
| `record_count` | int | Number of records. |
| `records` | string[] | Rendered records (see below). |
| `latency_ms` | float | Resolution time. |

Record rendering: `A`/`AAAA` → IP strings; `CNAME` → the canonical name;
`MX` → `"<pref> <host>"`; `TXT` → the text strings; `NS` → nameserver hosts.

**Outcome** — `PASS` if at least one record was found; `FAIL` if resolution
failed or returned an **empty** set (an empty set is a failure, not a pass);
`TIMEOUT` if the lookup timed out.

---

## `ping`

`ping` is a **traceroute**. `doc/ping-check-contract.md` is the authoritative
byte-level contract (generated from the platform monorepo); this is a summary.
IPv4 only — the target is resolved to its first IPv4 address.

**Parameters**

| Key | Type | Default | Notes |
|---|---|---|---|
| `count` | int | `4` | 1–20. **Inert** — validated for protocol compatibility but does not scale probing (the engine always sends 3 echoes per hop), matching the reference agent. |
| `timeout_seconds` | int | `10` | 1–60. Bounds the **whole** traceroute. |

`target` is the hostname or IP to trace to.

**Measurements** — the top-level packet/RTT stats describe the **final,
target-reaching hop**; the full route is in `hops`.

| Key | Type | Notes |
|---|---|---|
| `packets_sent` | int | Probes sent at the final hop (3 in practice); `0` on a total miss. |
| `packets_recv` | int | How many came back. |
| `packet_loss_pct` | float | **0..100** percentage. **`0` (not 100) on a total miss** — the loss calc is guarded by `packets_sent > 0`. |
| `rtt_min_ms` / `rtt_avg_ms` / `rtt_max_ms` | float | Final-hop RTT stats, fractional ms. |
| `rtt_stddev_ms` | float | **Population** standard deviation (divide by N). |
| `resolved_ips` | string[] | The resolved IPv4 address (omitted if empty). |
| `hops` | object[] | Ordered route; each `{ttl, ip?, rtt_ms?[], target?}` (a no-answer hop is just `{"ttl":N}`; the target hop carries `"target":true`). Omitted if empty. |

**Two failure shapes** (per the contract):
- **Hard errors** — parameter parse / ICMP socket setup failure → `INCONCLUSIVE`
  with `{"error":"..."}`; DNS resolution failure → `FAIL` with `{"error":"..."}`.
  These are **not** the measurements shape above.
- **Ran but unreachable** — the full measurements shape with zeros + the partial
  `hops` route.

**Outcome** — `PASS` iff the target answered at least one probe (no partial-loss
threshold — one reply is a PASS); `TIMEOUT` iff the target never answered and the
time budget ran out; `FAIL` otherwise (route exhausted within budget, or
resolution failure); `INCONCLUSIVE` on setup failure. The platform trusts this
outcome verbatim — it does not recompute it from the measurements.

---

## `tls`

*Deferred past v1 — the contract is documented here for when `tls` lands.*

**Parameters**

| Key | Type | Default | Notes |
|---|---|---|---|
| `port` | int | `443` | 1–65535. |
| `timeout_seconds` | int | `10` | 1–60. |

`target` is the hostname; it is also the SNI / hostname used for verification.

**Measurements**

| Key | Type | Notes |
|---|---|---|
| `handshake_ok` | bool | The TLS handshake completed. |
| `latency_ms` | float | Handshake time. |
| `tls_version` | string | `"1.3"`, `"1.2"`, ... |
| `cipher_suite` | string | Negotiated cipher suite name. |
| `cert_subject` | string | Leaf certificate subject CN. |
| `cert_issuer` | string | Leaf certificate issuer CN. |
| `cert_expires_at` | string | Leaf `notAfter`, RFC 3339, UTC. |
| `cert_days_remaining` | int | Days until the leaf expires. |
| `cert_valid` | bool | Chain trusted **and** hostname matches **and** not expired. |

Important: do the handshake **without certificate verification** so the
certificate fields are captured even when the certificate is invalid or
expired; then verify the chain explicitly to set `cert_valid`. Expiry alerting
depends on `cert_*` being populated regardless of validity.

**Outcome** — `PASS` if the handshake succeeded and `cert_valid` is true;
`FAIL` if the handshake succeeded but the certificate is invalid, or there was
no peer certificate, or the handshake errored; `TIMEOUT` if it timed out.

---

## Signing the result

Every `ResultSubmit` is signed (see [agent-protocol.md](agent-protocol.md) §
"Identity and Result Signing"). The signature is an Ed25519 signature over the
canonical serialization of fields 1–6:

```
task_id || check_id || outcome (uint32 BE) || measurements (raw bytes) || started_at (int64 BE) || completed_at (int64 BE)
```

`measurements` is the **raw JSON bytes** exactly as placed in the message —
sign the same bytes you send. The platform discards a Result whose signature
fails verification (no penalty, but the Result is lost), so this must match
byte-for-byte.
