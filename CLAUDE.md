# MeshCheck Android App

This repository is the **MeshCheck Android contributor app** — a standalone
project. It is not part of the MeshCheck platform monorepo; its only shared
artifact is the protocol file `proto/agent.proto`.

This file is the durable context for the project. Read `doc/app-spec.md` for
the full design before writing code.

---

## What MeshCheck is

MeshCheck is a distributed internet-reachability monitoring platform. It runs
checks (HTTP, TCP, DNS, TLS, ...) against customer-specified targets from a
large pool of **nodes** spread across real networks, and aggregates the
results into Verdicts. Nodes are either platform-operated VPS nodes or
**peer nodes** contributed by users on their own devices. Peer-node
contributors earn a revenue share.

The platform's value is vantage-point diversity — checks from real networks a
datacenter-only monitor can never reach. Mobile devices on consumer carrier
networks are the most valuable and least reachable vantage point of all.

## What this app is

This app is an **Android peer node**. When a user enrolls and the app is in
the Contributing state, it:

1. Holds a long-lived WebSocket to the platform gateway (`connection_class = mobile`).
2. Receives `TaskAssignment` messages, executes the checks, and submits
   Ed25519-signed `ResultSubmit` messages.
3. Shows the user their jobs and earnings.

It is deliberately minimal: jobs count, earnings, a Start/Stop control, and an
Unlink control. **Every other interaction — account, payouts, settings — lives
on the MeshCheck web dashboard, not in the app.**

The full design (UI, state model, background-service strategy, enrollment,
distribution) is in **`doc/app-spec.md`**. That document is authoritative.

## Trust model — why signing matters

Every check Result is signed with an **Ed25519 key the app generates and holds
on-device** (Android Keystore, non-exportable). The platform verifies every
signature; a Result whose signature fails verification is silently discarded.
There is no reliability score and no retry penalty — but a dropped Result is
unpaid work, so signing must be correct and byte-exact. See
`doc/check-types.md` § "Signing the result" and `doc/agent-protocol.md`.

---

## Decisions already locked

These were decided during design. Do not reopen them without a deliberate
reason — treat them as settled:

- **Android only.** iOS is dropped indefinitely.
- **minSdk 21** (Android 5.0), latest stable targetSdk.
- **Kotlin + Jetpack Compose**, with a baseline profile.
- **Always-connected foreground service**, declared service type
  **`specialUse`** (not `dataSync` — Android 15 caps `dataSync` at 6h/day).
- **QR-token enrollment** with on-device Ed25519 keypair generation. No login
  UI in the app.
- **No scheduling controls in v1** — no battery threshold, no data cap, no
  Wi-Fi-only toggle. The app contributes whenever it is Contributing.
- **Three-state UI model** — Contributing / Paused / Transitioning — with
  state, action, and consequence kept as separate UI elements.
- **Two controls**: Start/Stop (reversible pause) and Unlink (destructive,
  wipes credentials).
- **Distribution: Google Play *and* direct APK** (same build).
- **Bundled Conscrypt** for TLS 1.3 on pre-API-29 devices.
- **ZXing** for QR scanning (no Google Play Services dependency).
- **OkHttp** for the WebSocket.
- **FCM push-wake is deferred** — v1 relies only on the foreground service.

## Decisions still open — decide these deliberately

- The exact v1 check-type set. `http`, `tcp`, `dns`, `tls` are the candidates
  (see `doc/check-types.md`); confirm each is viable on mobile. `ping` is
  excluded permanently; `smtp` is deferred (carriers block port 25).
- The "jobs today" / earnings display window and refresh cadence.
- The contract of the platform enrollment endpoint (see below) — it does not
  exist yet and must be designed jointly with the platform team.
- The in-repo module/package structure.

---

## Platform dependencies (external contracts)

The app talks to the MeshCheck platform. These are the touch points:

| Endpoint | Status | Use |
|---|---|---|
| `wss://gateway.meshcheck.io/agent` | Exists, stable | The agent WebSocket. Speak the protocol in `doc/agent-protocol.md` exactly. |
| Enrollment-token redeem | **Does not exist yet** | The app exchanges a QR enrollment token + its Ed25519 public key for a Node and its API key. **Blocking dependency for the enrollment screen** — must be specified with the platform team first. |
| `GET /v1/organizations/{id}/accruals` | Exists, reused as-is | The earnings figure shown in-app. |
| `PUT /v1/nodes/{id}/push-token` | Exists | For FCM push-wake — **deferred**, not called in v1. |

## The protocol coupling — `proto/agent.proto`

`proto/agent.proto` is **vendored from the MeshCheck platform monorepo at a
pinned version.** It is the single source of truth for every message on the
wire; the app generates Kotlin message types from it.

Rule: **do not edit `agent.proto` in this repo.** When the platform revs the
protocol, re-sync the file deliberately and regenerate. The protocol is
versioned (the `meshcheck.agent.v1` WebSocket subprotocol token; old major
versions are dropped ~180 days after deprecation). Because of that, the app
**must implement a version-check-on-connect** that nudges the user to update —
otherwise a sideloaded APK silently dies at a protocol bump.

---

## Repository docs

- `doc/app-spec.md` — **the authoritative app design.** Read first.
- `doc/agent-protocol.md` — the Node↔Platform protocol contract (transport,
  handshake, message lifecycle, signing, versioning). Copied from the platform
  monorepo; some cross-links inside it point to monorepo docs and will not
  resolve here — that is expected.
- `doc/check-types.md` — parameter/measurement JSON shapes and outcome rules
  for each check type the app executes.
- `doc/glossary.md` — terminology reference for Android build/tooling
  acronyms (AAR, AGP, R8, ...) and MeshCheck domain terms.
- `proto/agent.proto` — the protocol message schema (vendored, pinned).

## Working rules

- Documentation is Markdown only.
- Write clean, human-readable code that matches the surrounding style.
- Keep related docs in sync — when a decision changes, update every doc that
  states it (`CLAUDE.md`, `doc/app-spec.md`).
- When the protocol or a platform contract is unclear, treat
  `doc/agent-protocol.md` and `proto/agent.proto` as authoritative; if they
  disagree with this file, they win.
