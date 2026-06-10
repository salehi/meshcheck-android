# Phase 6 — Android Contributor App

This document specifies the **Android contributor app**: the concrete next
piece of Phase 6 work. It is a companion to
[CLAUDE.md](../CLAUDE.md), which covers the goal of
the phase and the platform-side support that is already built. Read that
document first for context.

This spec is written to be **self-contained** so it can be lifted into a
separate repository. Where it depends on the MeshCheck platform it says so
explicitly; see [Platform-Side Additions Needed](#platform-side-additions-needed)
and [Codebase Placement](#codebase-placement).

---

## Scope Decisions

These decisions narrow the broad iOS+Android scope of the parent phase doc to
a deliverable first release.

| Decision | Choice | Rationale |
|---|---|---|
| Platforms | **Android only** | iOS deferred indefinitely. The APNs half of `internal/push` becomes dormant; no platform rework. |
| Device reach | **minSdk 21** (Android 5.0) | Covers ~99% of devices, including old and low-end hardware. |
| Language / UI | **Kotlin + Jetpack Compose** | Native, fast, least code for a small app. Baseline profile keeps cold-start smooth on low-end devices. |
| Connection model | **Always-connected foreground service** | FCM push-wake deferred — it needs Google Play Services, which the oldest target devices lack. |
| Scheduling controls | **None in v1** | No battery threshold, no data cap, no Wi-Fi-only toggle. The app contributes whenever it is in the Contributing state. |
| Distribution | **Google Play *and* direct APK** | Play for reach; direct APK for non-GMS and very old devices, and as a policy-dispute fallback. |

### What the App Is Not

- Not a substitute for the desktop agent — mobile is additive.
- Not a place for account management, payout setup, geo declaration, or
  billing. **Every interaction other than the four on the main screen happens
  on the web dashboard.** The app is deliberately minimal.

---

## What the App Does

The app has exactly two states a user ever sees: **not yet enrolled**, and
**enrolled**.

### First launch — enrollment

1. The app opens to a short explainer of what contributing means and what the
   app will do in the background.
2. It asks for the camera permission needed to scan.
3. It opens the camera to **scan a QR code** shown on the contributor's web
   dashboard. The QR carries a single-use enrollment token (see
   [Enrollment](#enrollment-via-qr)).
4. On success, the device is linked to a Node and the app moves to the
   enrolled screen, **Paused**. The user presses Start to begin contributing —
   which is when the notification and battery-optimization prompts appear.

### Every launch after that — the connected screen

The app skips straight to the enrolled screen; credentials persist on the
device. The screen shows four things and nothing else:

- **Jobs** — the count of jobs this session the platform confirmed it persisted
  (`ResultAck.persisted`). Results merely sent but never acknowledged are not
  counted — only work that reaches the server and earns.
- **Earnings** — read from the existing Phase 5 accruals API.
- **Start / Stop** — the primary control (pause and resume contributing).
- **Unlink this device** — secondary, destructive, behind a confirmation.

```
┌──────────────────────────────────┐
│  ●  Contributing                  │   ← STATE (read-only)
│     Your phone is taking jobs      │
│                                    │
│     Jobs this session     12       │
│     Earnings          $0.34        │
│                                    │
│   ┌───────────────────────────┐    │
│   │     Stop contributing     │    │   ← ACTION (verb)
│   └───────────────────────────┘    │
│   Pauses new jobs. Earnings and    │   ← CONSEQUENCE
│   this device stay linked.         │
│                                    │
│   Unlink this device               │   ← secondary, dim
└──────────────────────────────────┘
```

---

## The State Model

The app distinguishes **state** (what is true now) from **action** (what a
button does) so the two can never be confused. There are three states:

| State | Meaning | Service | Connection |
|---|---|---|---|
| **Contributing** | Taking jobs | Running | WebSocket open |
| **Paused** | Linked but idle | Stopped | Closed |
| **Transitioning** | Mid stop/start | Starting or stopping | Opening or closing |

UI rules that keep state and action unambiguous:

- A **state indicator** (label + colored dot) is read-only and shows the
  current state: "Contributing", "Connecting…" (the Transitioning state), or
  "Paused".
- The **action button** is labeled with the verb for the *other* state:
  Contributing → "Stop contributing"; Paused → "Start contributing". It
  reflects the user's intent rather than the live connection, so a brief
  reconnect never disables the control or locks the user out.
- A **consequence line** under the button says what pressing it will do.
- The foreground-service **notification mirrors the state** ("Contributing" /
  "Connecting…" / "Paused") — it is the contributor's status when the app is
  closed and must never contradict the in-app indicator.

### Start / Stop vs. Unlink

These are different actions at different lifetimes; the app keeps them
visually and functionally separate.

- **Start / Stop** — frequent, reversible. Stop closes the WebSocket and stops
  the foreground service. **Credentials are kept.** Start brings the service
  back with no re-scan. This is the everyday control.
- **Unlink this device** — rare, destructive. Wipes the Node API key and the
  Ed25519 signing key from Android Keystore and returns the app to the
  not-yet-enrolled state. To contribute again the user re-scans a QR.

Unlinking on the device and revoking on the web dashboard are
**complementary**: the dashboard can revoke the Node's API key server-side,
but only the device can scrub the private signing key off the phone itself.
Both belong in the product.

---

## Connection and Background Model

The [agent protocol](agent-protocol.md) is a long-lived
WebSocket: `ServerHello`/`ClientHello`, a heartbeat every ~30 s, and the
platform **does not queue tasks for offline Nodes** — it re-dispatches them.
A Node therefore earns only while it holds an open connection.

### Foreground service

Android kills background WebSockets within minutes. The only reliable way to
hold the connection is a **foreground service with a persistent
notification**. While the service runs it:

- Holds the WebSocket to `wss://gateway.meshcheck.io/agent`.
- Sends heartbeats, accepts `TaskAssignment`s, executes checks, submits signed
  `ResultSubmit`s.
- Survives the Activity (UI) being closed or swiped from recents — the service
  lives independently of the UI.

### Foreground-service type

Android 14+ requires a declared service type. Android 15 caps the `dataSync`
type at **6 hours per 24 h**, after which the OS force-stops it — wrong for a
Node meant to contribute continuously. The app declares the
**`specialUse`** type, which has no timeout. Play Console requires a written
justification for `specialUse`; this is supplied alongside the honest
background-contribution description Play requires anyway.

### Persistence — surviving "the app is closed"

The app separates "UI closed" from "Disconnected" with one persisted flag,
`userWantsConnected`. The boot receiver and the watchdog only restart the
service when that flag is `true`.

| Event | Survives? | Mechanism |
|---|---|---|
| User swipes app from recents | Yes | Foreground service runs independently of the UI. |
| OS kills service under memory pressure | Recovers | `START_STICKY` — the system relaunches the service. |
| Device reboots | Yes, if `userWantsConnected` | `BOOT_COMPLETED` receiver restarts the service. |
| `WorkManager` periodic watchdog | Yes | Re-launches the service if it finds it down and `userWantsConnected` is true. |
| User presses **Stop** | No (intended) | `userWantsConnected` set false; service stays down until Start. |
| User does **Force-stop** in system Settings | No | Nothing can override an explicit OS force-stop. Correct behavior. |

### Battery-optimization exemption

Without the exemption, aggressive OEMs (Xiaomi, Huawei, Oppo, realme, older
Samsung) kill the service shortly after the app is swiped away — defeating the
whole "keep working when closed" requirement. The app walks the user through
the system "don't optimize" dialog the first time they start contributing.

### Best-effort, not a guarantee

Even with all of the above, 24/7 uptime cannot be guaranteed: force-stop, the
"Restricted" app-standby bucket, and a few OEMs that ignore the exemption will
still cut the connection. This needs **no protocol work**: on restart the app
opens a fresh connection, the protocol does no task catch-up, the dispatcher
re-dispatches dropped tasks, and Phase 6 already calls for a mobile reliability
profile that tolerates intermittent connectivity. The product framing is
**best-effort persistence**.

---

## Enrollment via QR

Enrollment links one device to one Node without putting account login in the
app.

1. The contributor opens the web dashboard and chooses to add an Android
   device. The dashboard **creates the Node** (`connection_class = mobile`) and
   mints a long-lived **device-enrollment JWT** bound to that node and org. It
   shows a QR encoding a base64-JSON envelope:
   `{v, typ:"meshcheck-enroll", api, gateway, token}`, where `token` is that
   JWT. (The JWT is the credential — treat the QR like a password.)
2. The app scans the QR and **generates an Ed25519 keypair on-device**; the
   private seed never leaves the device — it is held encrypted at rest by a
   non-exportable Android Keystore key (the Keystore cannot hold an Ed25519 key
   itself before API 33).
3. Enrollment is **entirely local — there is no redeem call.** The
   device-enrollment JWT *is* the gateway credential: the app stores it and
   presents it verbatim as `Authorization: Bearer <jwt>`, exactly like a Node
   API key. The Node already exists; the app reads its id from the JWT `nid`
   claim. The Ed25519 public key is registered later, in the first
   `ClientHello`.
4. The app stores the credential (Keystore-wrapped) **and the `gateway` URL from
   the QR** (so it connects to the right deployment), then connects.

The QR replaces the "single sign-on between web and app" idea from the parent
phase doc — it is simpler and needs no login UI in the app.

---

## Identity and Credentials

- **Gateway bearer credential** — authenticates the WebSocket connection
  (`Authorization: Bearer <token>`). For a mobile device this is the
  device-enrollment JWT from the QR (not an `mck_live_…` API key); the platform
  accepts a node-scoped JWT exactly like an API key. Stored encrypted, wrapped
  by a non-exportable Keystore key.
- **Ed25519 keypair** — signs every `ResultSubmit`. Generated on-device at
  enrollment in software (BouncyCastle low-level API), because the Android
  Keystore only holds Ed25519 keys from API 33. The private seed is kept
  encrypted at rest by a non-exportable Keystore key (AES-GCM on API 23+, RSA
  on API 21–22) and is decrypted only in memory to sign. The public key is
  registered via `ClientHello`; per the protocol it is immutable after first
  connection.
- **Unlink** wipes both. **Force-stop / app uninstall** does not reach the
  Keystore on its own — uninstall clears app Keystore entries; a Node whose
  device is gone should also be revoked from the web dashboard.

---

## Permissions

| Permission | Why | Notes |
|---|---|---|
| `CAMERA` | Scan the enrollment QR | Runtime prompt, at enrollment only. |
| `POST_NOTIFICATIONS` | Foreground-service notification | Runtime prompt on Android 13+. |
| `FOREGROUND_SERVICE` | Run the service | Normal, auto-granted. |
| `FOREGROUND_SERVICE_SPECIAL_USE` | Declare the service type | Android 14+; needs Play Console justification. |
| `RECEIVE_BOOT_COMPLETED` | Restart the service after reboot | Normal, auto-granted. |
| `INTERNET`, `ACCESS_NETWORK_STATE` | Connect; detect connectivity | Normal, auto-granted. |
| `WAKE_LOCK` | Hold the CPU briefly during check execution | Normal, auto-granted. |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | Launch the exemption dialog | Used to open the system dialog, not auto-exempt. |

"Ask for background perm" is, in reality, this set — primarily camera,
notifications, and the battery-optimization exemption.

---

## Protocol Integration

The app is a Node and speaks the [agent protocol](agent-protocol.md)
unchanged. Implementation notes specific to Android:

- **Transport** — `wss://` with the `meshcheck.agent.v1` subprotocol token.
  The protocol mandates **TLS 1.3**; Android's system TLS only does 1.3 from
  API 29, so the app **bundles Conscrypt** to provide TLS 1.3 down to API 21.
- **Messages** — generate Kotlin types from `proto/agent.proto`; one framed
  Protobuf `Envelope` per WebSocket binary frame. No gRPC runtime.
- **Capabilities** — the app advertises `connection_class = CONNECTION_CLASS_MOBILE`,
  `can_send_icmp = false`, and a `supported_check_types` set of `http`, `tcp`,
  `dns` for v1 (`tls` deferred). "Disable check types the OS cannot run" is
  handled entirely by what `ClientHello` declares; the dispatcher already
  filters on it — no new platform code.
- **Reconnect** — on any drop, reconnect with a fresh `ServerHello`/
  `ClientHello`; expect only new Tasks, never catch-up.
- **Result signing** — Ed25519 signature over the canonical hash defined in
  the protocol doc.

Reachable check types on mobile are limited to those needing no raw sockets;
v1 ships `http`, `tcp`, and `dns` (`tls` is viable but deferred past v1).
Concurrency is low. Heavy checks (headless browser, multi-step transactions)
are out of scope per the parent phase doc.

---

## Technical Stack

| Concern | Choice |
|---|---|
| Language | Kotlin |
| UI | Jetpack Compose, with a baseline profile |
| Min / target SDK | minSdk 21 / latest stable targetSdk |
| TLS 1.3 on old devices | Bundled Conscrypt |
| WebSocket | OkHttp WebSocket (works to API 21) |
| DNS lookups | dnsjava — resolves MX/TXT/NS/CNAME, beyond Android's A/AAAA-only APIs |
| Protobuf | Generated Kotlin from `proto/agent.proto` |
| QR scanning | ZXing core decoder (`com.google.zxing:core`) — pure library, no Play Services |
| Camera | CameraX (preview + frame analysis) |
| Background work | Foreground service (`specialUse`) + `WorkManager` watchdog + `BOOT_COMPLETED` receiver |
| Credential storage | Software Ed25519 key + API key, each wrapped by a non-exportable Android Keystore key, stored in `SharedPreferences` |

ZXing over ML Kit deliberately: ML Kit barcode scanning depends on Google Play
Services, which the oldest target devices lack.

---

## Distribution

Both channels ship the same APK build:

- **Google Play** — widest reach and auto-updates. The listing must honestly
  describe the background-contribution behavior and consent flow; Play
  scrutinizes apps that do networking on behalf of others (a Phase 6 risk).
- **Direct APK** — for non-GMS and very old devices, and as a fallback if a
  Play policy dispute arises. Users must enable "install from unknown
  sources."

**The direct APK has no auto-update.** The agent protocol deprecates and
eventually drops old subprotocol versions (`426 Upgrade Required` after a
transition period). The app must therefore include a **version check on
connect** that nudges the user to update — otherwise sideloaded installs
silently die at a protocol bump.

---

## Out of Scope (and Where It Lives Instead)

| Not in the app | Where it lives |
|---|---|
| Account creation, login | Web dashboard |
| Payout wallet setup | Web dashboard |
| Geo declaration, Node settings | Web dashboard |
| Billing, invoices | Web dashboard |
| Battery / data / Wi-Fi-only controls | Not built in v1 (no scheduling controls) |
| iOS app, APNs push | Deferred indefinitely |
| FCM push-wake | Deferred (needs Play Services; revisit after v1) |
| Heavy check types | Out of scope per parent phase doc |

---

## Platform-Side Additions Needed

The platform side is already built. The QR-enrollment **issue** path exists:
the dashboard's "add an Android device" creates the Node and mints a
device-enrollment JWT, wrapped in the QR envelope (see "Enrollment via QR").

There is **no redeem path** — an earlier draft of this doc assumed the app
would exchange the token for a separate API key, but the platform uses the
device-enrollment JWT *as* the bearer credential (`internal/auth.resolveJWT`
returns a node-scoped principal; the gateway accepts it at the handshake). So
the app needs **no new server work**: enrollment is local, and task dispatch,
result ingestion, and earnings all reuse existing endpoints.

---

## Codebase Placement

**Open decision.** The app is a Kotlin/Android codebase with no source overlap
with the Go monorepo; its only shared artifact is `proto/agent.proto`. Three
options:

| Option | Pros | Cons |
|---|---|---|
| **In this repo** (`/android` or similar) | One place to keep `agent.proto` in sync; single issue tracker | Mixes Go and Android tooling, CI, and release cadence in one repo |
| **Git submodule** | Separate repo and CI, still referenced from the monorepo | Submodules are awkward to work with; `agent.proto` still needs a sync mechanism |
| **Separate project** | Clean Android tooling, independent release cadence, own CI; matches the parent doc's "separate codebase" framing | `agent.proto` must be vendored or published as a versioned artifact; cross-repo coordination on protocol changes |

The parent phase doc already states the mobile apps are "a separate codebase,
deferred … outside this Go monorepo." A **separate project** is the most
consistent with that, provided `proto/agent.proto` is vendored into the app
repo at a pinned version and re-synced deliberately when the protocol revs.
This document is written to support whichever option is chosen.

---

## Relationship to the Phase 6 Exit Gate

This app is what moves Phase 6 toward its exit gate (apps live on the store,
1,000 devices across 30 carriers, battery/data within budget — see
[CLAUDE.md](../CLAUDE.md)). The iOS half of the
original exit gate is dropped with the iOS app; the gate is restated in
Android-only terms in the parent doc's delivery notes.
