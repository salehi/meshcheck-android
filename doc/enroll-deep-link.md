# Enrollment Deep Link Contract

This document is the contract for the **`meshcheck://enroll`** deep link the Peer **Install** page emits so an Android contributor can pair the very phone they are browsing on — a case the QR code cannot serve, because you cannot scan a code shown on that phone's own screen. It is written for the MeshCheck Android app author, who registers the URI and routes it into the existing enrollment path.

It is a companion to the QR-pairing flow: the deep link, the QR, and the "Copy pairing code" fallback all carry the **same** `enrollEnvelope` — only the transport differs. Where this doc and the code disagree, the **source code is authoritative**.

---

## Source of truth

| Concern | File |
| --- | --- |
| Envelope struct, version, type, deep-link prefix | [internal/dashboard/install.go](../../internal/dashboard/install.go) (`enrollEnvelope`, `enrollScheme`, `enrollDeepLink`) |
| Where the link is rendered (Android visitors only) | [internal/dashboard/templates/peer_install.html](../../internal/dashboard/templates/peer_install.html) (`.NewDevice` block) |
| App-side parse (already accepts base64 URL-safe + JSON) | `android-app` submodule — `EnrollmentPayload.parse()` / `EnrollmentQr` |

---

## The URI

```
meshcheck://enroll#<payload>
```

| Part | Value |
| --- | --- |
| scheme | `meshcheck` |
| host | `enroll` |
| fragment | `<payload>` — the enrollment envelope, **URL-safe base64** (`base64.URLEncoding`, alphabet `A–Z a–z 0–9 - _`, `=` padding) |

- The payload rides in the **fragment** (`#…`), never a query string, so the credential stays out of any query-string logging convention.
- It is **URL-safe** base64 (not standard) so the `+` / `/` of standard base64 cannot break URI parsing. Decode it with the app's `Base64.URL_SAFE` branch. (The QR and "Copy pairing code" still use *standard* base64 — the app's parser already tries both, so no app-side branching on transport is needed.)
- `enrollScheme = "meshcheck://enroll#"` is the exact prefix; everything after the `#` is the base64 payload.

## The payload — `enrollEnvelope`

Decodes (base64 → bytes → JSON) to the same envelope the QR carries:

```go
// internal/dashboard/install.go
type enrollEnvelope struct {
	V       int    `json:"v"`       // envelope version — currently 1
	Typ     string `json:"typ"`     // discriminator — "meshcheck-enroll"
	API     string `json:"api"`     // REST API base, scheme://host
	Gateway string `json:"gateway"` // agent WebSocket, wss://host/agent (ws:// over plain HTTP)
	Token   string `json:"token"`   // the long-lived device-enrollment JWT (the credential)
}
```

The app must accept only `typ == "meshcheck-enroll"` and `v == 1`; the `token` is itself the gateway bearer credential (there is **no** redeem call — enrollment is local). This is exactly the QR contract; see the QR-pairing notes in the `android-app` repo (`doc/app-spec.md`, "Enrollment via QR").

---

## App-side handling (the follow-up this spec exists for)

1. **Manifest** — add an `<intent-filter>` for `ACTION_VIEW` with `<data android:scheme="meshcheck" android:host="enroll" />`. A custom scheme needs no `autoVerify`.
2. **Activity** — on `ACTION_VIEW`, read `intent.data`, take the URI **fragment**, and feed it to the existing `EnrollmentQr.parse()` → `Enroller.enroll()` path. No new parse logic: the URL-safe-base64 branch already exists.
3. **Paste entry** — expose an "Add this device → Paste code" field that feeds the same `Enroller.enroll()`, so the "Copy pairing code" fallback works when the deep link doesn't fire (app not installed at tap time, chooser dismissed, etc.).

### Security

A custom `meshcheck://` scheme can be claimed by any installed app, which could intercept the enrollment JWT. The blast radius is limited — the worst an interceptor does is enroll **that one node** as the signed-in user — so it is acceptable for the beta. The hardening path is verified **Android App Links** (an `https://` URL plus a `.well-known/assetlinks.json` pinned to the release signing certificate), which only the verified app may open. The same clipboard-read caveat applies to the copy fallback on older Android.
