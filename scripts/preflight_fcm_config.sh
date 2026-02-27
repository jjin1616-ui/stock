#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
APP_JSON="${APP_JSON:-$ROOT_DIR/app/google-services.json}"
PACKAGE_NAME="${PACKAGE_NAME:-com.example.stock}"
ALLOW_FCM_DISABLED="${ALLOW_FCM_DISABLED:-false}"

log() { printf "[fcm-preflight] %s\n" "$*"; }
warn() { printf "[fcm-preflight][WARN] %s\n" "$*" >&2; }
err() { printf "[fcm-preflight][ERROR] %s\n" "$*" >&2; }

is_true() {
  local raw
  raw="$(printf '%s' "${1:-}" | tr '[:upper:]' '[:lower:]')"
  [[ "$raw" == "1" || "$raw" == "true" || "$raw" == "yes" || "$raw" == "y" ]]
}

write_from_env_if_needed() {
  if [[ -f "$APP_JSON" ]]; then
    return 0
  fi

  mkdir -p "$(dirname "$APP_JSON")"

  if [[ -n "${GOOGLE_SERVICES_JSON_B64:-}" ]]; then
    GOOGLE_SERVICES_JSON_B64="$GOOGLE_SERVICES_JSON_B64" APP_JSON="$APP_JSON" python3 - <<'PY'
import base64, os, pathlib, sys
raw = os.environ.get("GOOGLE_SERVICES_JSON_B64", "").strip()
target = pathlib.Path(os.environ["APP_JSON"])
if not raw:
    raise SystemExit("GOOGLE_SERVICES_JSON_B64 is empty")
try:
    data = base64.b64decode(raw, validate=True)
except Exception as exc:
    raise SystemExit(f"invalid GOOGLE_SERVICES_JSON_B64: {exc}") from exc
target.write_bytes(data)
PY
    chmod 600 "$APP_JSON" 2>/dev/null || true
    log "app/google-services.json created from GOOGLE_SERVICES_JSON_B64"
    return 0
  fi

  if [[ -n "${GOOGLE_SERVICES_JSON:-}" ]]; then
    printf '%s' "$GOOGLE_SERVICES_JSON" > "$APP_JSON"
    chmod 600 "$APP_JSON" 2>/dev/null || true
    log "app/google-services.json created from GOOGLE_SERVICES_JSON"
    return 0
  fi
}

validate_json_shape() {
  APP_JSON="$APP_JSON" PACKAGE_NAME="$PACKAGE_NAME" python3 - <<'PY'
import json, os, pathlib, sys
path = pathlib.Path(os.environ["APP_JSON"])
pkg = os.environ["PACKAGE_NAME"].strip()
if not path.exists():
    raise SystemExit("missing google-services.json")
try:
    obj = json.loads(path.read_text(encoding="utf-8"))
except Exception as exc:
    raise SystemExit(f"invalid JSON: {exc}") from exc

project_info = obj.get("project_info") or {}
project_number = str(project_info.get("project_number") or "").strip()
if not project_number:
    raise SystemExit("project_info.project_number missing")

clients = obj.get("client")
if not isinstance(clients, list) or not clients:
    raise SystemExit("client array missing")

matched = None
for c in clients:
    android_info = ((c or {}).get("client_info") or {}).get("android_client_info") or {}
    if str(android_info.get("package_name") or "").strip() == pkg:
        matched = c
        break

if matched is None:
    raise SystemExit(f"package_name mismatch: expected {pkg}")

client_info = (matched.get("client_info") or {})
mobilesdk_app_id = str(client_info.get("mobilesdk_app_id") or "").strip()
if not mobilesdk_app_id:
    raise SystemExit("client_info.mobilesdk_app_id missing")

api_keys = matched.get("api_key")
if not isinstance(api_keys, list) or not api_keys:
    raise SystemExit("client.api_key missing")
current_key = str((api_keys[0] or {}).get("current_key") or "").strip()
if not current_key:
    raise SystemExit("client.api_key.current_key missing")

print(f"OK package={pkg} project_number={project_number} app_id={mobilesdk_app_id}")
PY
}

main() {
  write_from_env_if_needed

  if [[ ! -f "$APP_JSON" ]]; then
    if is_true "$ALLOW_FCM_DISABLED"; then
      warn "google-services.json missing but ALLOW_FCM_DISABLED=true; continue with FCM disabled."
      return 0
    fi
    err "app/google-services.json is required for push-enabled build/deploy."
    err "Fix: place file at app/google-services.json or set GOOGLE_SERVICES_JSON_B64."
    err "If intentionally disabling FCM, set ALLOW_FCM_DISABLED=true explicitly."
    return 11
  fi

  local check_out
  if ! check_out="$(validate_json_shape 2>&1)"; then
    err "$check_out"
    err "google-services.json is present but invalid for package ${PACKAGE_NAME}."
    return 12
  fi

  log "$check_out"
}

main "$@"
