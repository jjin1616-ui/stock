#!/usr/bin/env bash
set -euo pipefail

# Stable one-shot runner:
# 1) boot emulator (or reuse running)
# 2) wait for full boot
# 3) speed tweaks (disable animations)
# 4) build/install app
# 5) launch app
#
# Usage:
#   scripts/run_android_emulator_app.sh
#   scripts/run_android_emulator_app.sh --headless
#   scripts/run_android_emulator_app.sh --avd Medium_Phone_API_36.1

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT_DIR"

ADB_BIN="${ADB_BIN:-$(command -v adb || true)}"
EMULATOR_BIN_DEFAULT="$HOME/Library/Android/sdk/emulator/emulator"
EMULATOR_BIN="${EMULATOR_BIN:-$EMULATOR_BIN_DEFAULT}"
AVD_NAME="${AVD_NAME:-Medium_Phone_API_36.1}"
HEADLESS=0
BUILD=1

while [[ $# -gt 0 ]]; do
  case "$1" in
    --avd)
      AVD_NAME="${2:-}"
      shift 2
      ;;
    --headless)
      HEADLESS=1
      shift
      ;;
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
if [[ ! -x "$EMULATOR_BIN" ]]; then
  echo "[error] emulator binary not found: $EMULATOR_BIN" >&2
  exit 1
fi

if ! "$EMULATOR_BIN" -list-avds | rg -qx "$AVD_NAME"; then
  echo "[error] AVD '$AVD_NAME' not found." >&2
  echo "Available AVDs:"
  "$EMULATOR_BIN" -list-avds
  exit 1
fi

find_running_emulator() {
  "$ADB_BIN" devices | awk 'NR>1 && $1 ~ /^emulator-/ && $2=="device" {print $1; exit}'
}

boot_wait() {
  local serial="$1"
  "$ADB_BIN" -s "$serial" wait-for-device
  local ok=0
  local stable=0
  for _ in {1..150}; do
    local boot
    local dev
    local anim
    boot="$("$ADB_BIN" -s "$serial" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')"
    dev="$("$ADB_BIN" -s "$serial" shell getprop dev.bootcomplete 2>/dev/null | tr -d '\r')"
    anim="$("$ADB_BIN" -s "$serial" shell getprop init.svc.bootanim 2>/dev/null | tr -d '\r')"
    if [[ "$boot" == "1" && "$dev" == "1" && "$anim" == "stopped" ]]; then
      stable=$((stable + 1))
    else
      stable=0
    fi
    if [[ "$stable" -ge 3 ]]; then
      ok=1
      break
    fi
    sleep 2
  done
  if [[ "$ok" -ne 1 ]]; then
    echo "[error] Emulator boot timeout: $serial" >&2
    exit 1
  fi
  sleep 5
}

ensure_device_online() {
  local serial="$1"
  local ok=0
  for _ in {1..120}; do
    local state
    state="$("$ADB_BIN" -s "$serial" get-state 2>/dev/null || true)"
    if [[ "$state" == "device" ]]; then
      ok=1
      break
    fi
    sleep 1
  done
  if [[ "$ok" -ne 1 ]]; then
    echo "[error] Emulator not online: $serial" >&2
    "$ADB_BIN" devices -l || true
    exit 1
  fi
}

wait_package_manager_ready() {
  local serial="$1"
  local ok=0
  for _ in {1..120}; do
    if "$ADB_BIN" -s "$serial" shell cmd package list packages >/dev/null 2>&1; then
      ok=1
      break
    fi
    sleep 1
  done
  if [[ "$ok" -ne 1 ]]; then
    echo "[error] Package manager service not ready: $serial" >&2
    exit 1
  fi
}

start_emulator_if_needed() {
  local serial
  serial="$(find_running_emulator || true)"
  if [[ -n "$serial" ]]; then
    echo "[info] Reusing running emulator: $serial" >&2
    echo "$serial"
    return
  fi

  local log_file="/tmp/emulator_${AVD_NAME}.log"
  local opts=("-avd" "$AVD_NAME" "-netdelay" "none" "-netspeed" "full")
  if [[ "$HEADLESS" -eq 1 ]]; then
    opts+=("-no-window" "-gpu" "swiftshader_indirect")
  fi

  echo "[info] Starting emulator: $AVD_NAME" >&2
  nohup "$EMULATOR_BIN" "${opts[@]}" >"$log_file" 2>&1 &
  local pid=$!
  echo "[info] Emulator PID: $pid (log: $log_file)" >&2

  for _ in {1..90}; do
    serial="$(find_running_emulator || true)"
    if [[ -n "$serial" ]]; then
      echo "$serial"
      return
    fi
    sleep 2
  done

  echo "[error] Failed to detect emulator in adb devices." >&2
  tail -n 120 "$log_file" || true
  exit 1
}

apply_speed_tweaks() {
  local serial="$1"
  # Best-effort tweaks to make UI tests/manual checks faster.
  "$ADB_BIN" -s "$serial" shell settings put global window_animation_scale 0 >/dev/null 2>&1 || true
  "$ADB_BIN" -s "$serial" shell settings put global transition_animation_scale 0 >/dev/null 2>&1 || true
  "$ADB_BIN" -s "$serial" shell settings put global animator_duration_scale 0 >/dev/null 2>&1 || true
  "$ADB_BIN" -s "$serial" shell settings put system screen_off_timeout 1800000 >/dev/null 2>&1 || true
}

main() {
  "$ADB_BIN" start-server >/dev/null

  local serial
  serial="$(start_emulator_if_needed)"
  echo "[info] Waiting for full boot: $serial"
  boot_wait "$serial"
  ensure_device_online "$serial"
  wait_package_manager_ready "$serial"
  apply_speed_tweaks "$serial"

  local apk_path="$ROOT_DIR/app/build/outputs/apk/debug/app-debug.apk"
  if [[ "$BUILD" -eq 1 || ! -f "$apk_path" ]]; then
    echo "[info] Building debug APK..."
    ./gradlew :app:assembleDebug
  fi

  echo "[info] Installing app to $serial..."
  ensure_device_online "$serial"
  wait_package_manager_ready "$serial"
  local install_ok=0
  for _ in {1..5}; do
    if "$ADB_BIN" -s "$serial" install -r "$apk_path"; then
      install_ok=1
      break
    fi
    sleep 2
    ensure_device_online "$serial"
    wait_package_manager_ready "$serial"
  done
  if [[ "$install_ok" -ne 1 ]]; then
    echo "[error] APK install failed after retries." >&2
    exit 1
  fi

  echo "[info] Launching app..."
  ensure_device_online "$serial"
  wait_package_manager_ready "$serial"
  "$ADB_BIN" -s "$serial" shell am start -W -n com.example.stock/.MainActivity >/dev/null

  echo "[ok] Emulator ready and app launched on $serial"
}

main "$@"
