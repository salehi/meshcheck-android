#!/usr/bin/env bash
#
# Runs the project's Gradle build inside the Docker build container, so no
# JDK, Gradle, or Android SDK is ever installed on the host. Gradle itself is
# baked into the image, so this calls `gradle` directly (no wrapper).
#
# NETWORK MODE
#   Builds run OFFLINE by default, so a build never surprises you with a
#   download on a metered link — it either succeeds from ./.docker-cache or
#   fails fast telling you something is missing. To allow downloads, pass
#   --online as the FIRST argument and run it on your cheap network:
#
#     ./build.sh --online assembleDebug   # downloads allowed — cheap network
#     ./build.sh assembleDebug            # offline — fast network, no surprises
#
#   Run --online once whenever dependencies change (e.g. after a new library
#   is added); afterwards build offline freely.
#
# Verbose by default (--console=plain --info --stacktrace) so long steps are
# visible and never look stuck.
#
# The Gradle cache and Android SDK persist in ./.docker-cache (gitignored).

set -euo pipefail
cd "$(dirname "$0")"

IMAGE=meshcheck-android-build:latest
CACHE="$PWD/.docker-cache"

# Offline unless --online is passed as the first argument.
NET_FLAG="--offline"
if [ "${1:-}" = "--online" ]; then
    NET_FLAG=""
    shift
fi

if [ -n "$NET_FLAG" ]; then
    echo ">> build.sh: OFFLINE — no downloads (pass --online to allow them)"
else
    echo ">> build.sh: ONLINE — downloads allowed; connect your cheap network"
fi

GRADLE_FLAGS="--console=plain --info --stacktrace $NET_FLAG"

# With no task given, build both variants. Each variant's APK is moved into
# dist/ as it finishes (see app/build.gradle.kts).
if [ "$#" -eq 0 ]; then
    set -- assembleDebug assembleRelease
fi

# Bind-mount sources must exist before `docker run`, otherwise Docker creates
# them as root and the --user mapping below cannot write into them.
mkdir -p "$CACHE/gradle" "$CACHE/android-sdk"

# Stable signing key, generated once per machine into the gitignored
# .docker-cache (never committed — see .gitignore). With a stable signature a
# rebuilt APK updates an installed app in place (adb install -r); without it,
# AGP's auto-generated debug key is ephemeral inside the throwaway container, so
# every build is signed differently and the only way to install is to uninstall
# first — which wipes the enrollment. app/build.gradle.kts reads this same path.
KEYSTORE_HOST="$CACHE/signing/meshcheck-dev.jks"
mkdir -p "$CACHE/signing"
if [ ! -f "$KEYSTORE_HOST" ]; then
    echo ">> build.sh: generating stable dev signing key (one-time) at .docker-cache/signing/"
    docker run --rm \
        --user "$(id -u):$(id -g)" \
        -e HOME=/tmp \
        -v "$PWD":/workspace -w /workspace \
        "$IMAGE" \
        keytool -genkeypair -v \
            -keystore /workspace/.docker-cache/signing/meshcheck-dev.jks \
            -storetype PKCS12 -alias meshcheck \
            -keyalg RSA -keysize 2048 -validity 10000 \
            -storepass meshcheck -keypass meshcheck \
            -dname "CN=MeshCheck Dev (local sideload key), OU=Android, O=MeshCheck"
fi

exec docker run --rm \
    --user "$(id -u):$(id -g)" \
    -e HOME=/tmp \
    -v "$PWD":/workspace -w /workspace \
    -v "$CACHE/gradle":/.gradle \
    -v "$CACHE/android-sdk":/sdk \
    "$IMAGE" \
    gradle $GRADLE_FLAGS "$@"
