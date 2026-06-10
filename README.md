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
2. Run `./docker/setup.sh` to build the image and install the Android SDK.

Then build with the wrapper:

    ./build.sh assembleDebug            # offline by default — never downloads
    ./build.sh --online assembleDebug   # allow Gradle to fetch dependencies

`build.sh` runs Gradle one-shot in a throwaway container. It is **offline by
default** so a build never pulls dependencies unexpectedly; pass `--online` to
permit downloads. The debug APK lands in `app/build/outputs/apk/debug/`; the
Gradle and SDK caches live in `.docker-cache/` (gitignored).

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

No repository secrets are required: the release APK is signed with the
auto-generated debug key and the release is published with the built-in
`GITHUB_TOKEN`. If the app later ships to Google Play, add the Play service
account JSON as a secret (`gh secret set PLAY_SERVICE_ACCOUNT_JSON < key.json`)
and a publish step — until then no Google credentials are involved.

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
