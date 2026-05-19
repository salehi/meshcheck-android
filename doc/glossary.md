# Glossary — Android Build and Tooling Terms

A reference for the acronyms and jargon that show up when building this app
or reading its docs. It explains the *terminology*; for project *decisions*
see [CLAUDE.md](../CLAUDE.md) and [app-spec.md](app-spec.md).

Terms are grouped by topic. Where a term ties to a locked decision, that is
noted inline.

---

## Build artifacts and packaging

**AAR** — *Android Archive.* A `.aar` file: the distribution format for an
Android **library**. It is a zip bundling compiled code (`classes.jar`),
plus Android-specific parts a plain JAR cannot carry — `res/`,
`AndroidManifest.xml`, `assets/`, `jni/`, `R.txt`, and ProGuard/consumer
rules. The Compose, OkHttp, ZXing, and Conscrypt dependencies arrive as AARs.

**JAR** — *Java Archive.* A zip of compiled `.class` files (and resources).
Plain Java/Kotlin libraries ship as JARs; an AAR contains a `classes.jar`
inside it.

**APK** — *Android Package.* The installable `.apk` artifact — one file, one
device install. This is what the **direct-APK** distribution channel ships,
and what an `assembleRelease` build produces.

**AAB** — *Android App Bundle.* A `.aab` upload format for Google Play. Play
itself splits a bundle into per-device APKs at install time. The **Google
Play** distribution channel uses an AAB; the **direct-APK** channel uses a
universal APK — same build inputs.

**Baseline profile** — A list of hot code paths shipped with the app so the
Android runtime (ART) can ahead-of-time compile them at install, instead of
JIT-compiling on first run. It is a locked decision: it keeps cold start
smooth on low-end devices.

**Manifest merging** — The AGP build step that combines the app's
`AndroidManifest.xml` with the manifest of every library AAR into one final
manifest. Relevant here because the foreground-service declaration and its
`specialUse` type must survive the merge intact.

---

## Build tooling

**AGP** — *Android Gradle Plugin.* The Gradle plugin (`com.android.application`,
`com.android.library`) that teaches Gradle how to build Android: compiling
resources, merging manifests, packaging APK/AAB, running R8. Its version is
declared in the root `build.gradle.kts` and is coupled to a supported Gradle
version range.

**Gradle** — The build system. Driven by `build.gradle.kts` /
`settings.gradle.kts` scripts (Kotlin DSL here) and the `gradlew` wrapper.
Builds in this project run inside Docker — see the project memory on
Docker-only builds; never install Gradle on the host.

**Gradle wrapper** (`gradlew`) — A checked-in script + `gradle-wrapper.jar`
that downloads and runs a pinned Gradle version, so every machine builds with
the same Gradle regardless of what is installed locally.

**Artifact transform** — A Gradle mechanism that converts a resolved
dependency from one form to another on demand, with caching. AGP uses these
heavily for AARs: `ExtractAarTransform` explodes the zip, then `AarTransform`
hands a requested piece (classes, `res/`, manifest, ...) to whichever task
asked for it. You normally never touch these — they surface only in build
scans and dependency-resolution stack traces.

**R8** — The compiler that does **shrinking** (dead-code removal),
**optimization**, and **obfuscation** in release builds. It replaced the
older **ProGuard**; AGP still reads `proguard-rules.pro`-style keep rules and
the `consumer-rules` bundled inside AARs.

**KSP** — *Kotlin Symbol Processing.* The annotation-processing framework for
Kotlin code generation. Faster than the older `kapt`. Only relevant if a
library here needs generated code.

---

## Android SDK and runtime

**SDK** — *Software Development Kit.* The Android SDK: platform APIs, build
tools, and the emulator. In Gradle config it appears as the three "sdk"
levels below.

**API level** — An integer naming a release of the Android platform API
(e.g. API 21 = Android 5.0). Used by the three knobs:

- **minSdk** — lowest API level the app installs on. Locked at **21**.
- **targetSdk** — API level the app is *tested and behaves* against; governs
  runtime-behavior opt-ins. Locked at "latest stable."
- **compileSdk** — API level the app is *compiled* against; sets which APIs
  the code can reference. Usually equals or exceeds targetSdk.

**ART** — *Android Runtime.* The on-device runtime that executes the app's
code (successor to Dalvik). The baseline profile is consumed by ART.

**DEX** — *Dalvik Executable.* The bytecode format ART runs; the build
converts `.class` files into `classes.dex` inside the APK. "Multidex" is
the workaround for the 65 536-method-per-DEX limit on old devices.

**Foreground service** — An Android service that shows an ongoing
notification and is allowed to keep running while the app is not on screen.
This app uses one to hold the agent WebSocket open.

**Service type `specialUse`** — A foreground-service category declared in the
manifest. A locked decision: the alternative `dataSync` is capped at 6h/day on
Android 15, which would kill an always-connected node.

**GMS** — *Google Mobile Services.* Google's proprietary app layer (Play
Services, Play Store). Not present on every device — which is why **FCM
push-wake is deferred** and **ZXing** (no-GMS QR scanning) was chosen over the
ML Kit barcode scanner.

**FCM** — *Firebase Cloud Messaging.* Google's push-notification transport.
Push-wake via FCM is deferred to a later version; v1 relies only on the
always-connected foreground service.

---

## Libraries and protocol

**Jetpack Compose** — Android's declarative Kotlin UI toolkit. The locked UI
choice for this app.

**Conscrypt** — A TLS provider library (Google's, BoringSSL-backed). Bundled
so that **TLS 1.3** is available on pre-API-29 devices, whose system TLS
stack does not support it.

**OkHttp** — The HTTP/WebSocket client library. The locked choice for the
long-lived agent WebSocket connection.

**ZXing** — "Zebra Crossing," a barcode/QR library. The locked choice for
scanning the QR **enrollment token**, because it needs no GMS.

**Protocol Buffers / protobuf** — Google's schema-driven binary serialization
format. `proto/agent.proto` is the schema; the build generates Kotlin message
types from it. The file is **vendored and pinned** — do not edit it here.

**Ed25519** — The elliptic-curve digital-signature algorithm used to sign
every check Result. The keypair is generated on-device and the private key is
non-exportable. See the project memory on Ed25519 / Keystore for the
minSdk-21 storage approach.

**Android Keystore** — The OS-backed secure container for cryptographic keys.
Keys can be marked non-exportable so the private key never leaves the device.

**WebSocket subprotocol** — A token negotiated during the WebSocket handshake
that pins the protocol version (`meshcheck.agent.v1`). The app must
version-check on connect so a stale sideloaded APK fails loudly instead of
silently.

---

## MeshCheck domain terms

These name platform concepts, not Android tooling. They are defined fully in
[app-spec.md](app-spec.md) and [agent-protocol.md](agent-protocol.md); listed
here only so this glossary is a single lookup point.

**Node** — A single monitoring vantage point. This app is one **peer node**.

**Peer node** — A node contributed by a user on their own device (versus a
platform-operated VPS node). Peer-node contributors earn a revenue share.

**Check** — A single reachability probe of one type (`http`, `tcp`, `dns`,
`tls`) against a target.

**Verdict** — The platform's aggregated conclusion over many nodes' results.

**TaskAssignment / ResultSubmit** — The protocol messages: the platform
assigns a check, the app executes it and submits a signed result.

**Enrollment token** — The QR-delivered token a user redeems to turn the app
into a registered node.
