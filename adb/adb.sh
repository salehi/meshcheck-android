#!/usr/bin/env bash
#
# Runs `adb` inside a Docker Compose container, so neither the Android SDK nor
# adb is ever installed on the host — the same principle as build.sh. The
# container is defined in adb/compose.yaml; this script builds it on first use,
# keeps it up, and execs adb into it. Run with --help for usage and the
# wireless-debugging quick start.
#
# adb is a client plus a background *server* that holds the device connections,
# so the container is long-lived (compose.yaml runs `tail -f /dev/null`): the
# server and your `adb connect` sessions persist between calls.
#
# adb's RSA key — the one your phone authorizes — persists in
# ./.docker-cache/adb, so the phone doesn't re-prompt on every run.
#
# USB devices are deliberately NOT exposed (wireless only). To debug a
# cable-attached device, add `privileged: true` and a /dev/bus/usb volume to
# adb/compose.yaml and accept the udev/hotplug fiddliness that brings.

set -euo pipefail
cd "$(dirname "$0")"   # the adb/ directory — Compose's project dir; .. is repo root

IMAGE=meshcheck-adb:latest
CONTAINER=meshcheck-adb

usage() {
    cat <<'EOF'
Usage: ./adb/adb.sh [OPTION] [--] <adb-command> [args...]

Runs adb inside a Docker Compose container (see adb/compose.yaml), so adb never
has to be installed on the host. The container is long-lived, so the adb server
and your wireless connections persist between invocations.

Options (everything not listed here is passed to adb verbatim):
  -h, --help    Show this help and exit.
  --build       (Re)build the meshcheck-adb image, then exit. Needs network.
  --stop        Tear the container down. Drops all adb connections.
  --            End of script options; pass the rest to adb unchanged.

Wireless debugging quick start (phone and laptop on the same Wi-Fi):

  Android 11+ (fully cable-free):
    # Phone: Developer options > Wireless debugging > Pair device with code
    ./adb/adb.sh pair    <phone-ip>:<pairing-port>   # enter the 6-digit code
    ./adb/adb.sh connect <phone-ip>:<connect-port>   # port from the main WD screen

  Android 10 and below (one initial USB cable):
    ./adb/adb.sh tcpip 5555                          # while plugged in over USB
    ./adb/adb.sh connect <phone-ip>:5555

Install and drive the app (paths are relative to the repo root):
  ./adb/adb.sh devices
  ./adb/adb.sh install -r dist/app-release.apk
  ./adb/adb.sh shell pm grant io.meshcheck.contributor android.permission.CAMERA
  ./adb/adb.sh shell pm grant io.meshcheck.contributor android.permission.POST_NOTIFICATIONS

For adb's own help: ./adb/adb.sh help
EOF
}

# --- Argument parsing -------------------------------------------------------
# Only options known to this script are consumed; the first unrecognized
# argument ends parsing and everything from there on belongs to adb (so adb's
# own flags, e.g. `-s <serial>`, pass through untouched).
do_build=0
do_stop=0
while [ "$#" -gt 0 ]; do
    case "$1" in
        -h|--help) usage; exit 0 ;;
        --build)   do_build=1; shift ;;
        --stop)    do_stop=1; shift ;;
        --)        shift; break ;;
        *)         break ;;
    esac
done

# No action and no adb command: show usage rather than dumping adb's own help.
if [ "$do_build" -eq 0 ] && [ "$do_stop" -eq 0 ] && [ "$#" -eq 0 ]; then
    usage
    exit 0
fi

# --- Compose setup -----------------------------------------------------------
# compose.yaml runs adb as us via user: "${HOST_UID}:${HOST_GID}".
export HOST_UID HOST_GID
HOST_UID="$(id -u)"
HOST_GID="$(id -g)"

compose=(docker-compose)

if [ "$do_stop" -eq 1 ]; then
    "${compose[@]}" down
    exit 0
fi

if [ "$do_build" -eq 1 ]; then
    "${compose[@]}" build
    exit 0
fi

# --- Run adb -----------------------------------------------------------------
# The adb key cache must exist before the bind mount, or Docker creates it as root.
mkdir -p ../.docker-cache/adb

# First run: build the image with visible progress (one-time, needs network).
if ! docker image inspect "$IMAGE" >/dev/null 2>&1; then
    echo ">> adb.sh: building $IMAGE (one-time, needs network)"
    "${compose[@]}" build
fi

# Bring the long-lived container up if it isn't already (silent once it is).
if [ "$(docker inspect -f '{{.State.Running}}' "$CONTAINER" 2>/dev/null || true)" != "true" ]; then
    "${compose[@]}" up -d
fi

# Allocate a TTY only when we actually have one, so `adb shell` stays interactive
# while piped/non-interactive use keeps working.
exec_flags=()
[ -t 0 ] || exec_flags=(-T)
exec "${compose[@]}" exec "${exec_flags[@]}" adb adb "$@"
