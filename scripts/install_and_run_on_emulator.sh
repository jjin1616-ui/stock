#!/usr/bin/env bash
set -euo pipefail

# Fast path for daily iteration:
# assumes an emulator is already running and only does install + launch.
#
# Usage:
#   scripts/install_and_run_on_emulator.sh
#   scripts/install_and_run_on_emulator.sh --no-build

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT_DIR"

ADB_BIN="${ADB_BIN:-$(command -v adb || true)}"
BUILD=1

while [[ $# -gt 0 ]]; do
  case "$1" in
    --no-build)
      BUILD=0
      shift
      ;;
    *)
      echo "[error] Unknown option: $1" >&2
      exit 1
      ;;
  esac
done

if [[ -z "$ADB_BIN" ]]; then
  echo "[error] adb not found in PATH." >&2
  exit 1
fi

SERIAL="$("$ADB_BIN" devices | awk 'NR>1 && $1 ~ /^emulator-/ && $2=="device" {print $1; exit}')"
if [[ -z "$SERIAL" ]]; then
  echo "[error] Running emulator not found." >&2
  echo "Start emulator first from Android Studio Device Manager, then rerun." >&2
  exit 1
fi

APK_PATH="$ROOT_DIR/app/build/outputs/apk/debug/app-debug.apk"
if [[ "$BUILD" -eq 1 || ! -f "$APK_PATH" ]]; then
  ./gradlew :app:assembleDebug
fi

"$ADB_BIN" -s "$SERIAL" install -r "$APK_PATH"
"$ADB_BIN" -s "$SERIAL" shell am start -W -n com.example.stock/.MainActivity >/dev/null

echo "[ok] Installed and launched on $SERIAL"

