#!/usr/bin/env bash
# Package the TailTrace Magisk module: APK as /system/priv-app + permission
# allowlist. Install the resulting zip through the Magisk app ("Install from
# storage"), then reboot.
#
# Usage:
#   ./scripts/build-magisk-module.sh [path/to/TailTrace.apk]
#
# Default APK path is the debug build output. Build it first:
#   ./gradlew assembleDebug
#
# Notes for official LineageOS + Magisk (e.g. Fairphone 5):
#  - If a sideloaded copy of the app is already installed, uninstall it BEFORE
#    rebooting with this module — a /data install with the same package but a
#    conflicting state confuses the package manager. Same signing key (the
#    committed debug keystore) means later `adb install -r` updates on top of
#    the system copy keep working.
#  - The allowlist XML is mandatory: priv-apps requesting privileged
#    permissions that are not allowlisted can prevent boot in enforce mode.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
APK="${1:-$REPO_ROOT/app/build/outputs/apk/debug/app-debug.apk}"
OUT="$REPO_ROOT/build/tailtrace-magisk-$(date +%Y%m%d).zip"

if [[ ! -f "$APK" ]]; then
    echo "APK not found: $APK" >&2
    echo "Build it first: ./gradlew assembleDebug" >&2
    exit 1
fi

STAGE="$(mktemp -d)"
trap 'rm -rf "$STAGE"' EXIT

cp -r "$REPO_ROOT/magisk/." "$STAGE/"
mkdir -p "$STAGE/system/priv-app/TailTrace"
cp "$APK" "$STAGE/system/priv-app/TailTrace/TailTrace.apk"

mkdir -p "$(dirname "$OUT")"
rm -f "$OUT"
(cd "$STAGE" && zip -r -X "$OUT" . -x '*.gitkeep') >/dev/null

echo "Module: $OUT"
echo "Install via Magisk app -> Modules -> Install from storage, then reboot."
