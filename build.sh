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

# Signing credentials live in a gitignored .env (NEVER committed — see
# .gitignore). build.sh sources them and forwards them into the build container;
# app/build.gradle.kts reads MESHCHECK_KEYSTORE_PASSWORD / MESHCHECK_KEY_ALIAS to
# sign the APK. Nothing is hardcoded. On a fresh machine the password is
# generated once and persisted to .env — mirroring how the keystore itself is
# generated once (below). To share with CI: set the GitHub secrets
# ANDROID_KEYSTORE_PASSWORD (this password) and ANDROID_KEYSTORE_B64 (the
# keystore); see README § "Signing & in-place updates".
ENV_FILE="$PWD/.env"
if [ -f "$ENV_FILE" ]; then
    set -a; . "$ENV_FILE"; set +a
fi
if [ -z "${MESHCHECK_KEYSTORE_PASSWORD:-}" ]; then
    echo ">> build.sh: no signing password found — generating one into .env (one-time)"
    GEN_PASS="$(openssl rand -hex 24 2>/dev/null || head -c 24 /dev/urandom | od -An -tx1 | tr -d ' \n')"
    if [ -z "$GEN_PASS" ]; then
        echo ">> build.sh: ERROR — could not generate a random signing password" >&2
        exit 1
    fi
    umask 077
    {
        echo "# MeshCheck local signing credentials — DO NOT COMMIT (gitignored)."
        echo "# Consumed by build.sh and app/build.gradle.kts; mirror into CI as the"
        echo "# GitHub secrets ANDROID_KEYSTORE_PASSWORD + ANDROID_KEYSTORE_B64."
        echo "MESHCHECK_KEYSTORE_PASSWORD=$GEN_PASS"
        echo "MESHCHECK_KEY_ALIAS=${MESHCHECK_KEY_ALIAS:-meshcheck}"
    } > "$ENV_FILE"
    chmod 600 "$ENV_FILE"
    set -a; . "$ENV_FILE"; set +a
fi
: "${MESHCHECK_KEY_ALIAS:=meshcheck}"
export MESHCHECK_KEYSTORE_PASSWORD MESHCHECK_KEY_ALIAS

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
        -e MESHCHECK_KEYSTORE_PASSWORD -e MESHCHECK_KEY_ALIAS \
        -v "$PWD":/workspace -w /workspace \
        "$IMAGE" \
        sh -c 'keytool -genkeypair -v \
            -keystore /workspace/.docker-cache/signing/meshcheck-dev.jks \
            -storetype PKCS12 -alias "$MESHCHECK_KEY_ALIAS" \
            -keyalg RSA -keysize 2048 -validity 10000 \
            -storepass "$MESHCHECK_KEYSTORE_PASSWORD" -keypass "$MESHCHECK_KEYSTORE_PASSWORD" \
            -dname "CN=MeshCheck Dev (local sideload key), OU=Android, O=MeshCheck"'
fi

exec docker run --rm \
    --user "$(id -u):$(id -g)" \
    -e HOME=/tmp \
    -e MESHCHECK_KEYSTORE_PASSWORD -e MESHCHECK_KEY_ALIAS \
    -v "$PWD":/workspace -w /workspace \
    -v "$CACHE/gradle":/.gradle \
    -v "$CACHE/android-sdk":/sdk \
    "$IMAGE" \
    gradle $GRADLE_FLAGS "$@"
