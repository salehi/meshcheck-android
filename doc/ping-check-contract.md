# Ping Check Contract

This document is the complete, byte-level contract for the **`ping`** Check type, written for an external agent author who wants to produce Results the Platform will accept and score identically to a native Node. It covers the parameter shape, the measurement shape, the outcome rules, the signing scheme, and the precise division of responsibility between the agent and the Platform.

It is a companion to the [Agent Protocol](agent-protocol.md) (transport, auth, message framing) and the [Schema](../schema/schema.md) (the `checks.parameters` / `results.measurements` jsonb columns these types back). Where the two disagree, the **source code is authoritative** — every claim here is cited to a file and line.

---

## Source of truth & version pin

The shapes are defined once and shared by both the Platform and the reference Node agent, in the **`meshcheck-agent`** submodule mounted at [agent/](../../agent/):

| Concern | File | License |
| --- | --- | --- |
| Parameter & measurement structs, defaulting, validation | [agent/pkg/checkspec/checkspec.go](../../agent/pkg/checkspec/checkspec.go) | Apache-2.0 |
| Canonical hash + Ed25519 signing scheme | [agent/pkg/resultsig/resultsig.go](../../agent/pkg/resultsig/resultsig.go) | Apache-2.0 |
| Wire message / outcome enum | [agent/proto/agent.proto](../../agent/proto/agent.proto) | Apache-2.0 |
| Platform-side ingestion & aggregation | [internal/collect/collect.go](../../internal/collect/collect.go) | AGPL-3.0 |
| Reference agent ping implementation (behavioral spec) | [agent/cmd/agent/checks.go](../../agent/cmd/agent/checks.go) | AGPL-3.0 |

**Version pin:** the submodule is pinned by the superproject to commit **`affd7fa42e4cfe59ec81532d8e007930af88dfb7`** (`heads/main`). **There is no git tag on that commit** — it is pinned by SHA only. The `proto/agent.proto` you generate against is the copy *inside* that commit; the duplicate at [android-app/proto/agent.proto](../../android-app/proto/agent.proto) is byte-identical.

> The `checkspec` and `resultsig` packages are deliberately Apache-licensed and live in the public agent submodule precisely so a third-party agent can import or re-implement them without touching the AGPL Platform code.

---

## Parameters — `checks.parameters` jsonb

Produced by the Requester, validated by the Platform at submission, delivered to the agent.

```go
// agent/pkg/checkspec/checkspec.go
type PingParams struct {
	Count          int `json:"count"`
	TimeoutSeconds int `json:"timeout_seconds"`
}
```

| Field | Type | Required | Default | Valid range |
| --- | --- | --- | --- | --- |
| `count` | int | optional | `4` | 1–20 |
| `timeout_seconds` | int | optional | `10` | 1–60 |

- **`timeout_seconds` is the shared timeout field** used by every Check type (constants `defaultTimeoutSecs = 10`, `maxTimeoutSecs = 60`). It bounds the **entire** check (the whole traceroute must finish within it) — it is **not** a per-probe timeout.
- **An empty body defaults to `{"count": 4, "timeout_seconds": 10}`.** `decodeParams` treats `""`, `"{}"`, and `"null"` as "keep the defaults" ([checkspec.go](../../agent/pkg/checkspec/checkspec.go)). Both fields are optional; the caller may send neither, either, or both.
- **No `interval`, `payload_size`, `packet_size`, or `dscp` parameter exists.** Sending one is harmless (see [Unknown fields](#unknown-fields)) but has no effect.
- The platform-side validation hook is `checkspec.Validate("ping", params)` → `ParsePingParams`, which rejects out-of-range values so a bad Check fails at submission rather than on the Node.

### ⚠️ `count` is currently inert

The reference agent's ping is implemented as a **traceroute** that sends a hardcoded `traceProbes = 3` ICMP echoes per TTL hop. **`count` is validated but never read in the ping path** — the only `.Count` reference in the agent executor is DNS's `RecordCount`. So today `count` does not change `packets_sent` or probe volume. Validate and accept it; do not rely on it to scale your probing.

### Address family

**The caller cannot choose the IP address family, and neither does the agent — `ping` is hardwired to IPv4.** The reference agent resolves with `net.ResolveIPAddr("ip4", target)` and the traceroute aborts with an error if the target is not an IPv4 address (`dst.To4() == nil`). There is no `family`/`af` field. An external agent should resolve the target to IPv4 and probe v4 only, to stay comparable with native Nodes. For a literal-IP target, the resolution simply echoes the target.

---

## Measurements — `results.measurements` jsonb

Produced by the agent, stored opaquely by the Platform. A `ping` Result is a **traceroute**: TTL-limited ICMP echoes record the route in `hops`, and the top-level packet/RTT statistics describe the **final, target-reaching hop**.

```go
// agent/pkg/checkspec/checkspec.go
type PingMeasurements struct {
	PacketsSent   int      `json:"packets_sent"`
	PacketsRecv   int      `json:"packets_recv"`
	PacketLossPct float64  `json:"packet_loss_pct"`
	RTTMinMs      float64  `json:"rtt_min_ms"`
	RTTAvgMs      float64  `json:"rtt_avg_ms"`
	RTTMaxMs      float64  `json:"rtt_max_ms"`
	RTTStdDevMs   float64  `json:"rtt_stddev_ms"`
	ResolvedIPs   []string `json:"resolved_ips,omitempty"`
	Hops          []Hop    `json:"hops,omitempty"`
}

type Hop struct {
	TTL    int       `json:"ttl"`
	IP     string    `json:"ip,omitempty"`
	RTTMs  []float64 `json:"rtt_ms,omitempty"`
	Target bool      `json:"target,omitempty"` // true once the probe reached the target
}
```

| Field | Type | Units / meaning | omitempty |
| --- | --- | --- | --- |
| `packets_sent` | int | probes sent **at the final, target-reaching hop** (3 in practice) | no |
| `packets_recv` | int | how many of those came back | no |
| `packet_loss_pct` | float64 | **0..100 percentage** (not a 0..1 fraction); final-hop loss | no |
| `rtt_min_ms` | float64 | **fractional milliseconds** (µs precision) | no |
| `rtt_avg_ms` | float64 | fractional ms | no |
| `rtt_max_ms` | float64 | fractional ms | no |
| `rtt_stddev_ms` | float64 | **population** standard deviation, ms (this is the jitter figure) | no |
| `resolved_ips` | []string | address(es) the target resolved to (single IPv4 in the reference agent) | yes |
| `hops` | []Hop | the ordered traceroute route to the target | yes |

Per-hop (`hops[]`):

| Field | Type | Meaning | omitempty |
| --- | --- | --- | --- |
| `ttl` | int | the TTL for this hop | no |
| `ip` | string | the router that answered; absent/empty = no answer at this distance | yes |
| `rtt_ms` | []float64 | **per-probe** round-trip times at this TTL | yes |
| `target` | bool | `true` on the hop where the target itself answered | yes |

Notes that matter for byte-exact output:

- **RTT stats are `float64` milliseconds**, not integers — the agent computes `float64(d.Microseconds()) / 1000.0`.
- **Packet loss is `packet_loss_pct`, 0..100.**
- **Top-level RTT fields are aggregates only**; per-probe RTTs live inside `hops[].rtt_ms`. The final hop's `rtt_ms` array is effectively the per-probe RTTs to the target.
- **TTL / hop information is carried entirely in `hops`.** There is **no DSCP field** and no top-level hop-count field (it is `len(hops)`).
- **No `omitempty` on the six numeric fields** — they always serialize, even as `0`. Only `resolved_ips` and `hops` are omitempty.
- Go marshals struct fields in declaration order, so the on-wire key order is exactly the order above — but see [Field order](#field-order-vs-signature): order does not affect acceptance.

### Measurements on failure / timeout

There are **two distinct shapes**, and an external agent must reproduce the right one:

1. **Hard executor errors** — parameter parse failure, DNS resolution failure, or ICMP socket setup failure — emit a completely different blob:

   ```json
   {"error": "<message>"}
   ```

   This is **not** the `PingMeasurements` shape. The reference agent uses it for the `INCONCLUSIVE` (parse/socket) and `FAIL` (resolution) error paths.

2. **Target unreachable but the traceroute ran** (ordinary `FAIL`/`TIMEOUT`) — the full `PingMeasurements` shape, with:
   - `packets_sent: 0`, `packets_recv: 0`
   - **`packet_loss_pct: 0`** — the loss calculation is guarded by `if packets_sent > 0`, so a total miss reports **`0`, not `100`**
   - all `rtt_*_ms: 0`
   - `resolved_ips` populated
   - `hops` populated with the **partial** route showing how far the probe got

So no measurement field is *required* to be non-zero on failure; zeros plus the partial `hops` array carry the diagnostic story, and the **outcome enum** carries the verdict. Do **not** emit `packet_loss_pct: 100` for a dead target.

---

## Outcome rules — the Platform trusts the agent

This is the single most important fact for an external agent: **the Platform takes the agent's submitted `outcome` enum verbatim and never recomputes it from the measurements.** On ingestion ([collect.go](../../internal/collect/collect.go) `HandleResult`):

```go
Outcome:      outcomeName(rs.GetOutcome()),   // straight from the wire
Measurements: rs.GetMeasurements(),           // stored opaque (jsonb)
```

```go
func outcomeName(o agentpb.ResultOutcome) string {
	switch o {
	case agentpb.ResultOutcome_RESULT_OUTCOME_PASS:
		return result.OutcomePass        // "pass"
	case agentpb.ResultOutcome_RESULT_OUTCOME_FAIL:
		return result.OutcomeFail        // "fail"
	case agentpb.ResultOutcome_RESULT_OUTCOME_TIMEOUT:
		return result.OutcomeTimeout     // "timeout"
	default:
		return result.OutcomeInconclusive // "inconclusive" (covers UNKNOWN=0)
	}
}
```

The wire enum ([agent.proto](../../agent/proto/agent.proto)):

```protobuf
enum ResultOutcome {
  RESULT_OUTCOME_UNKNOWN      = 0;  // → "inconclusive" on the platform
  RESULT_OUTCOME_PASS         = 1;
  RESULT_OUTCOME_FAIL         = 2;
  RESULT_OUTCOME_INCONCLUSIVE = 3;
  RESULT_OUTCOME_TIMEOUT      = 4;
}
```

> **`RESULT_OUTCOME_UNKNOWN` (0) maps to `inconclusive`.** Leaving the field unset is not neutral — set the outcome explicitly.

What the Platform reads out of ping measurements is minimal: `rtt_avg_ms` (for the Verdict's p50/p95 latency, and only for results already marked `pass`) and `packets_sent`/`packets_recv` (for the public result page). The aggregate Verdict is just a distribution of per-Node outcomes plus latency percentiles — **every signed Result counts with equal weight; there is no consensus scoring** (see [trust-and-scoring](../architecture/trust-and-scoring.md)). A Check is `inconclusive` only when *zero* Results arrive.

### The de-facto PASS / FAIL / TIMEOUT rule

Because the Platform won't correct you, matching the reference agent keeps your Results comparable to native Nodes. From [checks.go](../../agent/cmd/agent/checks.go) `runPing`:

```go
outcome := outcomeFail
switch {
case tr.reached && len(tr.finalRTTs) > 0:
	outcome = outcomePass
case !tr.reached && (ctx.Err() != nil || !time.Now().Before(deadline)):
	outcome = outcomeTimeout
}
```

- **PASS** ⟺ the target itself answered at least one probe. **Any single reply from the target is a PASS.**
- **There is no partial-loss threshold.** A target answering 1 of 3 probes (66% final-hop loss) is still **PASS**. `packet_loss_pct` is informational and never flips the outcome.
- **TIMEOUT** ⟺ the target never answered **and** the time budget ran out (context cancelled or `timeout_seconds` deadline hit).
- **FAIL** ⟺ everything else — most notably the target never answered but the route was exhausted (hit `traceMaxTTL = 30`) *within* the time budget; also DNS resolution failure.
- **INCONCLUSIVE** ⟺ parameter parse / ICMP socket setup failure (and the wire `UNKNOWN`).

**100% loss is therefore `FAIL` or `TIMEOUT` depending on why probing stopped:** out of TTL hops → `FAIL`; out of wall-clock time → `TIMEOUT`.

---

## Signing — canonical hash & Ed25519

The signature is **not** over the measurements bytes alone. Ed25519 signs the **SHA-256 of a canonical concatenation of `ResultSubmit` fields 1–6** ([resultsig.go](../../agent/pkg/resultsig/resultsig.go)):

```
canonical = task_id
          ‖ check_id
          ‖ outcome        (uint32, big-endian)
          ‖ measurements   (the exact JSON bytes you transmit)
          ‖ started_at     (int64,  big-endian, unix milliseconds)
          ‖ completed_at   (int64,  big-endian, unix milliseconds)

signature = Ed25519_sign(privkey, SHA256(canonical))
```

Implications for an external agent:

- Sign **all six fields**, with `outcome` as a **uint32 big-endian** and the timestamps as **int64 big-endian unix milliseconds** (`agent.proto` `started_at` / `completed_at`).
- The signature binds the measurements *to* a specific task, check, outcome, and time window — you cannot replay a measurements signature under a different outcome or task.
- It signs the **SHA-256 digest**, not the raw preimage, so the Platform can re-verify from the stored row months later. The digest is persisted in `results.signature_canonical_hash` for audit.
- Verification is `resultsig.Verify(pubkey, rs)`; a failed signature **discards the Result with no penalty** — it is never persisted.

### Field order vs. signature

JSON key order **within** the `measurements` blob does not affect acceptance. The Platform never re-serializes your measurements — it hashes and stores the **exact bytes you transmit**. So as long as the bytes you *sign* are byte-identical to the bytes you *send*, any internal key order verifies, and downstream parsing is order-independent JSON. **Names, types, and units matter only for the few fields the Platform reads** (`rtt_avg_ms`, `packets_sent`, `packets_recv`); everything else is stored and ignored.

### Unknown fields

**Extra / unknown measurement fields are accepted and ignored, never rejected.** Measurements are stored as opaque `jsonb` with only a JSON-validity cast at insert. There is **no measurement-schema validation** on the result path — `checkspec.Validate` runs only on the *parameters* at submission time, never on measurements. Unknown fields survive in the database and are simply not read.

---

## Worked example

> ⚠️ **Constructed from the marshalling code, not a captured DB row.** No real ping measurement fixture exists in the repo (the dev seeders don't synthesize ping measurements). Because Go's `encoding/json` emits struct fields in declaration order, this is the exact byte layout a successful 3-hop PASS produces:

```json
{"packets_sent":3,"packets_recv":3,"packet_loss_pct":0,"rtt_min_ms":11.482,"rtt_avg_ms":12.901,"rtt_max_ms":14.057,"rtt_stddev_ms":1.05,"resolved_ips":["93.184.216.34"],"hops":[{"ttl":1,"ip":"192.168.1.1","rtt_ms":[0.512,0.488,0.503]},{"ttl":2},{"ttl":3,"ip":"93.184.216.34","rtt_ms":[11.482,12.164,14.057],"target":true}]}
```

The `ttl: 2` hop has no `ip`/`rtt_ms` (a router that didn't answer — both fields are `omitempty`). The last hop carries `"target": true`.

---

## Checklist for an external agent

1. Resolve the target to **IPv4** only.
2. Honour `timeout_seconds` (default 10, max 60) as the whole-check budget; accept and validate `count` (default 4, range 1–20) even though it is currently inert.
3. Emit measurements in the `PingMeasurements` shape on success and ordinary failure; emit `{"error": "..."}` only for parse / resolution / socket setup failures.
4. Report `packet_loss_pct` as a **0..100** percentage and RTTs as **fractional-ms float64**.
5. Decide the outcome yourself using the PASS/FAIL/TIMEOUT/INCONCLUSIVE rule above — the Platform trusts it.
6. Sign `SHA256(task_id ‖ check_id ‖ outcome(u32 BE) ‖ measurements ‖ started_at(i64 BE) ‖ completed_at(i64 BE))` with your Node's Ed25519 key, and transmit the **exact** measurement bytes you signed.
