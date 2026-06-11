# MeshCheck Android App

This is the **MeshCheck Android contributor app** repository. It is its own
git repository, included in the MeshCheck platform monorepo as a **git
submodule** at `android-app/`. It was bootstrapped from a context bundle
assembled out of that monorepo, so a fresh session with no prior context can
pick the app up and run with it.

## How to use it

1. Open a fresh Claude Code session with **this directory** as the working
   directory.
2. Paste the prompt from `PROMPT.md` as the first message.

The session reads `CLAUDE.md` and `doc/` and starts warm — it has the product
context, the locked decisions, the protocol, and the check specs without
re-deriving anything.

## Building

Every build runs inside a Docker container — no JDK, Gradle, or Android SDK is
installed on the host.

First-time setup:

1. Download the Android command-line tools and Gradle distribution zips into
   `docker/vendor/` (gitignored — `docker/Dockerfile` names the exact files
   and versions).
2. Run `./docker/setup.sh` to build the image and install the Android SDK
   (this includes the **NDK and CMake** — `:checks` contains native code for the
   `ping` traceroute check, so re-run `./docker/setup.sh` after pulling a change
   that bumps the NDK/CMake version).

Then build with the wrapper:

    ./build.sh assembleDebug            # offline by default — never downloads
    ./build.sh --online assembleDebug   # allow Gradle to fetch dependencies

`build.sh` runs Gradle one-shot in a throwaway container. It is **offline by
default** so a build never pulls dependencies unexpectedly; pass `--online` to
permit downloads. The debug APK lands in `app/build/outputs/apk/debug/`; the
Gradle and SDK caches live in `.docker-cache/` (gitignored).

### Signing & in-place updates

A throwaway container would otherwise generate a *fresh* debug key on every
build, so each APK would be signed differently and `adb install -r` would fail
with `INSTALL_FAILED_UPDATE_INCOMPATIBLE` — the only way to install would be to
uninstall first, **wiping the enrollment** (the Keystore-encrypted Ed25519
seed). To avoid that, `build.sh` generates one **stable signing key per
machine** into `.docker-cache/signing/meshcheck-dev.jks` (gitignored — the key
is never committed, per `.gitignore`) and both build types use it
(`app/build.gradle.kts`). A rebuilt APK therefore updates an installed one in
place:

    ./build.sh assembleDebug
    ./adb/adb.sh install -r dist/app-debug.apk    # in-place update, enrollment kept

This is a **local sideload key, not a Play upload key**. The key is per-machine,
so APKs built on a *different* machine do not share this signer. To make a second
machine — or CI — sign identically, reuse the same keystore (CI does this from a
secret; see Releases).

## Releases

Releases are cut by **pushing a git tag** — GitHub Actions
(`.github/workflows/release.yml`) builds the APK on a hosted runner (no Docker;
standard `setup-java` + `setup-android` + the Gradle action) and publishes a
GitHub Release with the APK attached. CI does not need the Docker image.

The **tag carries the version**: it is injected as the app's `versionName`, so
the tag, the built APK's version, and the GitHub Release all agree. Two tag
formats, one per build variant:

    git tag debug-1.0.0   && git push origin debug-1.0.0     # assembleDebug   -> pre-release
    git tag release-1.0.0 && git push origin release-1.0.0   # assembleRelease -> release

The version must be `X.Y.Z`. A `versionCode` is derived from it automatically.
Local `./build.sh` builds are unaffected and keep the default version in
`app/build.gradle.kts`.

### Signing — in-place updates across releases

CI does *not* run `build.sh`, so it would otherwise fall back to AGP's
auto-generated debug key, which is **regenerated on every run** — consecutive
releases would be signed differently and a user could not update a sideloaded
APK in place (each version would need an uninstall, wiping enrollment). To
prevent that, the workflow restores a **stable signing key from the
`ANDROID_KEYSTORE_B64` repository secret** (base64 of the keystore) into
`.docker-cache/signing/meshcheck-dev.jks` before building, the same path
`app/build.gradle.kts` reads. It is the **same key `build.sh` uses locally**, so
local and CI APKs share a signer and update each other in place. A missing
secret fails the release loudly rather than shipping a non-updatable APK.

Set or rotate the secret from the keystore (the value never prints):

    base64 -w0 .docker-cache/signing/meshcheck-dev.jks | gh secret set ANDROID_KEYSTORE_B64

Rotating to a *different* key re-signs future releases, so installs built with
the old key must be reinstalled once. The release itself is published with the
built-in `GITHUB_TOKEN`. If the app later ships to Google Play, add a real Play
upload key and the Play service account JSON as a secret
(`gh secret set PLAY_SERVICE_ACCOUNT_JSON < key.json`) plus a publish step —
until then no Google credentials are involved.

## Remote setup

This repo and the submodule wiring in the superproject are only complete once
this repo has a remote and the superproject's `.gitmodules` `url` points to
it. See the project handoff notes — until then the submodule is local-only.

## What's in here

| File | What it is | Origin |
|---|---|---|
| `CLAUDE.md` | Durable project context for the new repo: what MeshCheck is, what the app is, locked vs. open decisions, platform dependencies, the proto-sync rule. | Written for this bundle. |
| `PROMPT.md` | The kickoff prompt to paste into the fresh session. | Written for this bundle. |
| `doc/app-spec.md` | The authoritative app design. | The monorepo's `doc/decisions/phases/phase-6-android-app.md` (cross-links adjusted). |
| `doc/agent-protocol.md` | The Node↔Platform protocol contract. | Verbatim copy of the monorepo's `doc/protocols/agent-protocol.md`. |
| `doc/check-types.md` | Check parameter/measurement JSON shapes and outcome rules. | Transcribed from the monorepo's Go `internal/checkspec`. |
| `proto/agent.proto` | The protocol message schema. | Verbatim copy of the monorepo's `proto/agent.proto`. |

## Keeping in sync with the platform

`proto/agent.proto` and `doc/agent-protocol.md` are **snapshots** of the
platform monorepo. They are vendored at a pinned version. When the platform
revs the protocol, re-copy both files deliberately and regenerate the app's
message types. See `CLAUDE.md` § "The protocol coupling".

`doc/check-types.md` is a hand transcription of the platform's `checkspec`
package — if the platform adds or changes a check type, update it too.
