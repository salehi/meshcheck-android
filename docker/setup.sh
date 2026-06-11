#!/usr/bin/env bash
#
# One-time build-environment warm-up:
#   1. builds the meshcheck-android-build Docker image, and
#   2. downloads the Android SDK packages into ./.docker-cache/android-sdk.
#
# Re-run this only to rebuild the image or change SDK versions.

set -euo pipefail
cd "$(dirname "$0")/.."

IMAGE=meshcheck-android-build:latest
CACHE="$PWD/.docker-cache"
mkdir -p "$CACHE/gradle" "$CACHE/android-sdk"

echo "==> Building $IMAGE"
docker build -t "$IMAGE" docker/

echo "==> Installing Android SDK packages into .docker-cache/android-sdk"
docker run --rm \
  --user "$(id -u):$(id -g)" \
  -e HOME=/tmp \
  -v "$CACHE/android-sdk":/sdk \
  "$IMAGE" \
  bash -c 'yes | sdkmanager --sdk_root=/sdk --licenses > /dev/null \
        && yes | sdkmanager --sdk_root=/sdk \
             "platform-tools" \
             "platforms;android-35" \
             "build-tools;35.0.0" \
             "ndk;27.2.12479018" \
             "cmake;3.22.1"'

echo "==> Done. Build image and Android SDK are ready."
