#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
cd "$ROOT_DIR"

HOST="${HOST:-16.176.148.77}"
KEY_PATH="${KEY_PATH:-stock-ec2-key.pem}"
VARIANT="${VARIANT:-debug}" # debug|release
ALLOW_FCM_DISABLED="${ALLOW_FCM_DISABLED:-false}"

usage() {
  cat <<EOF
Usage:
  HOST=16.176.148.77 KEY_PATH=stock-ec2-key.pem VARIANT=debug scripts/publish_apk_ec2.sh
  GOOGLE_SERVICES_JSON_B64=<base64> scripts/publish_apk_ec2.sh
  ALLOW_FCM_DISABLED=true scripts/publish_apk_ec2.sh   # only when intentionally no push

Outputs (served by nginx on EC2):
  http://\$HOST/apk/app-latest.apk
  http://\$HOST/apk/app-latest.apk.sha256
  http://\$HOST/apk/  (directory listing)
EOF
}

if [[ "${1:-}" == "-h" || "${1:-}" == "--help" ]]; then
  usage
  exit 0
fi

case "$VARIANT" in
  debug|release) ;;
  *)
    echo "VARIANT must be debug|release (got: $VARIANT)" >&2
    exit 2
    ;;
esac

bash "${SCRIPT_DIR}/preflight_fcm_config.sh"

VARIANT_CAP="$(printf '%s' "$VARIANT" | awk '{print toupper(substr($0,1,1)) substr($0,2)}')"

./gradlew ":app:export${VARIANT_CAP}Apk" --no-daemon

APK_DIR="app/build/outputs/apk/export/${VARIANT}"
APK_PATH="$(ls -t "${APK_DIR}"/mstock_*.apk | head -n 1)"
APK_BASE="$(basename "$APK_PATH")"

echo "Publishing: ${APK_PATH}"

chmod 600 "$KEY_PATH" 2>/dev/null || true

scp -o StrictHostKeyChecking=no -i "$KEY_PATH" "$APK_PATH" "ubuntu@${HOST}:/tmp/${APK_BASE}"

eval "$(
  python3 - "$APK_BASE" <<'PY'
import re,sys
apk = sys.argv[1]
# Filename format:
#   mstock_v<versionName>(<versionCode>)_<buildLabel>_<buildType>.apk
# buildLabel can contain underscores (e.g. V3_203).
m = re.match(r'^mstock_v(?P<vname>[^()]+)\((?P<vcode>\d+)\)_(?P<label>.+)_(?P<btype>debug|release)\.apk$', apk)
if not m:
    raise SystemExit(f"Cannot parse APK filename: {apk}")
print(f"VERSION_NAME='{m.group('vname')}'")
print(f"VERSION_CODE='{m.group('vcode')}'")
print(f"BUILD_LABEL='{m.group('label')}'")
print(f"BUILD_TYPE='{m.group('btype')}'")
PY
)"

# Publish a short APK filename for easier sharing/download history.
# Example: james._V3_203.apk
PUBLISHED_APK_BASE="james._${BUILD_LABEL}.apk"
if [[ ! "$PUBLISHED_APK_BASE" =~ ^james\._V[0-9_]+\.apk$ ]]; then
  # Fallback to versionCode if build label format ever changes.
  PUBLISHED_APK_BASE="james._V${VERSION_CODE}.apk"
fi

PUBLISHED_AT="$(date -u +'%Y-%m-%dT%H:%M:%SZ')"

# Static metadata for the app to poll (/apk/latest.json).
cat > /tmp/latest.json <<EOF
{
  "version_name": "${VERSION_NAME}",
  "version_code": ${VERSION_CODE},
  "build_label": "${BUILD_LABEL}",
  "build_type": "${BUILD_TYPE}",
  "apk_url": "http://${HOST}/apk/download",
  "apk_versioned_url": "http://${HOST}/apk/${PUBLISHED_APK_BASE}",
  "apk_filename": "${PUBLISHED_APK_BASE}",
  "sha256_url": "http://${HOST}/apk/app-latest.apk.sha256",
  "sha256_versioned_url": "http://${HOST}/apk/${PUBLISHED_APK_BASE}.sha256",
  "published_at": "${PUBLISHED_AT}",
  "notes": ""
}
EOF
scp -o StrictHostKeyChecking=no -i "$KEY_PATH" /tmp/latest.json "ubuntu@${HOST}:/tmp/latest.json"

ssh -o StrictHostKeyChecking=no -i "$KEY_PATH" "ubuntu@${HOST}" "sudo bash -s" <<EOS
set -euo pipefail
mkdir -p /var/www/stock/apk
mv -f "/tmp/${APK_BASE}" "/var/www/stock/apk/${PUBLISHED_APK_BASE}"
cd /var/www/stock/apk
sha256sum "${PUBLISHED_APK_BASE}" > "${PUBLISHED_APK_BASE}.sha256"
ln -sf "${PUBLISHED_APK_BASE}" app-latest.apk
ln -sf "${PUBLISHED_APK_BASE}.sha256" app-latest.apk.sha256
mv -f /tmp/latest.json /var/www/stock/apk/latest.json
chown -R www-data:www-data /var/www/stock/apk
chmod 0644 /var/www/stock/apk/*
echo "OK"
echo "latest:  http://${HOST}/apk/app-latest.apk"
echo "sha256:  http://${HOST}/apk/app-latest.apk.sha256"
echo "browse:  http://${HOST}/apk/"
echo "meta:    http://${HOST}/apk/latest.json"
echo "named:   http://${HOST}/apk/${PUBLISHED_APK_BASE}"
EOS

# Verify downloadable artifact is a real APK(zip starts with PK).
# Prefer /apk/download, but if that route is blocked/interstitial, fallback to the versioned URL.
probe_magic() {
  local url="$1"
  local out
  if ! out="$(curl -fsSL --range 0-1 "$url" | xxd -p 2>/dev/null)"; then
    echo ""
    return 1
  fi
  echo "$out"
  return 0
}

DOWNLOAD_URL="http://${HOST}/apk/download"
VERSIONED_URL="http://${HOST}/apk/${PUBLISHED_APK_BASE}"
MAGIC="$(probe_magic "$DOWNLOAD_URL" || true)"
if [[ "$MAGIC" != "504b" ]]; then
  echo "[warn] /apk/download verification failed (magic=${MAGIC:-none}). Trying versioned URL..." >&2
  MAGIC_VERSIONED="$(probe_magic "$VERSIONED_URL" || true)"
  if [[ "$MAGIC_VERSIONED" != "504b" ]]; then
    echo "[error] APK verification failed for both routes." >&2
    echo "[error] /apk/download magic=${MAGIC:-none}, /apk/${PUBLISHED_APK_BASE} magic=${MAGIC_VERSIONED:-none}" >&2
    exit 5
  fi
  echo "[info] /apk/download may be interstitial-blocked, but versioned APK URL is valid: $VERSIONED_URL"
fi
