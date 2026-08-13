#!/usr/bin/env bash
# Wait for an Android device to appear on `adb devices`, then install the
# APK. Useful when the phone is plugged in mid-build, or for kiosk setups
# where the USB link drops periodically — re-run this and it blocks until
# the device comes back.
#
# Env knobs:
#   LIGPSPORT_APK        — path to APK (default: app/build/outputs/apk/debug/app-debug.apk)
#   LIGPSPORT_VARIANT    — debug|release; picks the default APK path. Ignored if
#                          LIGPSPORT_APK is set explicitly. (default: debug)
#   LIGPSPORT_TIMEOUT    — seconds to wait for a device, 0 = forever (default: 0)
#   LIGPSPORT_INSTALL_FLAGS — extra flags passed to `adb install` (default: -r)
#
# Exit codes:
#   0  install succeeded
#   2  APK not found / bad config
#   3  timed out waiting for device
#   4  adb install failed

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"

VARIANT="${LIGPSPORT_VARIANT:-debug}"
case "$VARIANT" in
    debug)   DEFAULT_APK="${REPO_ROOT}/app/build/outputs/apk/debug/app-debug.apk" ;;
    release) DEFAULT_APK="${REPO_ROOT}/app/build/outputs/apk/release/app-release.apk" ;;
    *)
        echo "ERROR: LIGPSPORT_VARIANT must be 'debug' or 'release' (got '$VARIANT')" >&2
        exit 2
        ;;
esac

APK="${LIGPSPORT_APK:-$DEFAULT_APK}"
TIMEOUT="${LIGPSPORT_TIMEOUT:-0}"
# Word-split intentionally so callers can pass e.g. "-r -g".
read -r -a INSTALL_FLAGS <<<"${LIGPSPORT_INSTALL_FLAGS:--r}"

if [ ! -f "$APK" ]; then
    echo "ERROR: APK not found at $APK" >&2
    echo "       build it with: nix run .#build-${VARIANT}" >&2
    exit 2
fi

# Returns the single connected serial, or empty if none / multiple.
# Devices in `unauthorized` or `offline` state are skipped — we want a
# real `device` line before we try to install.
first_ready_device() {
    adb devices | awk 'NR>1 && $2=="device" {print $1}' | head -n 1
}

echo "==> waiting for an Android device to connect"
echo "    apk:     $APK"
echo "    variant: $VARIANT"
if [ "$TIMEOUT" -gt 0 ]; then
    echo "    timeout: ${TIMEOUT}s"
fi

# `adb wait-for-device` is the obvious primitive but it blocks forever
# with no progress output and doesn't distinguish `unauthorized` from
# `device`. We poll instead so the user sees the heartbeat and can hit
# Allow on the phone's RSA prompt without restarting the script.
start_ts=$(date +%s)
SERIAL=""
while :; do
    SERIAL="$(first_ready_device || true)"
    if [ -n "$SERIAL" ]; then
        break
    fi

    if [ "$TIMEOUT" -gt 0 ]; then
        now=$(date +%s)
        if [ $(( now - start_ts )) -ge "$TIMEOUT" ]; then
            echo "ERROR: no device after ${TIMEOUT}s" >&2
            adb devices >&2 || true
            exit 3
        fi
    fi

    # Surface unauthorized/offline state once per loop so the user knows
    # to confirm the RSA dialog instead of staring at a silent prompt.
    pending="$(adb devices | awk 'NR>1 && $2!="" && $2!="device" {printf "%s(%s) ", $1, $2}')"
    if [ -n "$pending" ]; then
        printf '\r    pending: %s\033[K' "$pending"
    else
        printf '\r    no devices yet\033[K'
    fi
    sleep 1
done
printf '\r\033[K'
echo "==> device ready: $SERIAL"

echo "==> installing $APK"
# Pin to the exact serial we matched so a second device hot-plugged
# between the poll and the install can't end up as the target.
if ! adb -s "$SERIAL" install "${INSTALL_FLAGS[@]}" "$APK"; then
    echo "ERROR: adb install failed" >&2
    exit 4
fi

echo "==> done"
