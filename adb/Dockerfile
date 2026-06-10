# adb-only environment for the MeshCheck Android app.
#
# A tiny Alpine image that provides just `adb` (Android platform-tools), so the
# host never needs the Android SDK or adb installed — the same keep-it-in-Docker
# principle as docker/Dockerfile. Driven by ./adb.sh, which keeps one container
# alive and execs adb into it (adb's background server must outlive each call).
#
# This is wireless-debugging only; no USB device access is wired up here.

FROM alpine:3.20

# android-tools provides adb (and fastboot). --no-cache keeps the layer small.
RUN apk add --no-cache android-tools
