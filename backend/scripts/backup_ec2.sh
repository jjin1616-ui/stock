#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
SSH_USER_DEFAULT="ubuntu"

log() { printf "[%s] %s\n" "$(date +'%Y-%m-%d %H:%M:%S')" "$*"; }
err() { printf "[ERROR] %s\n" "$*" >&2; }

require_cmd() {
  command -v "$1" >/dev/null 2>&1 || { err "missing command: $1"; exit 1; }
}

detect_key_path() {
  local provided="${1:-}"
  if [[ -n "$provided" ]]; then
    echo "$provided"; return
  fi
  local candidates=(
    "/mnt/data/stock-ec2-key.pem"
    "$HOME/Downloads/codex.pem"
    "$HOME/.ssh/stock-ec2-key.pem"
    "$ROOT_DIR/stock-ec2-key.pem"
  )
  local c
  for c in "${candidates[@]}"; do
    if [[ -f "$c" ]]; then
      echo "$c"; return
    fi
  done
  echo ""
}

discover_from_repo() {
  python3 - <<'PY' "$ROOT_DIR"
import ipaddress, pathlib, re, sys
root = pathlib.Path(sys.argv[1])
patterns = re.compile(r"https?://[A-Za-z0-9._-]+|ec2-[A-Za-z0-9-]+\\.compute\\.amazonaws\\.com|(?:\\d{1,3}\\.){3}\\d{1,3}")
hosts = []
for p in root.rglob("*"):
    if not p.is_file():
        continue
    if any(x in p.parts for x in (".git", ".venv", "build", "__pycache__")):
        continue
    if p.stat().st_size > 2_000_000:
        continue
    try:
        txt = p.read_text(encoding="utf-8", errors="ignore")
    except Exception:
        continue
    for m in patterns.findall(txt):
        h = m
        if h.startswith("http://") or h.startswith("https://"):
            h = re.sub(r"^https?://", "", h).split("/")[0]
        hosts.append(h)

def is_public_ip(v: str) -> bool:
    try:
        ip = ipaddress.ip_address(v)
    except ValueError:
        return False
    return ip.is_global

seen = set()
for h in hosts:
    if h in seen:
        continue
    seen.add(h)
    if re.match(r"^ec2-[A-Za-z0-9-]+\\.compute\\.amazonaws\\.com$", h):
        print(h); sys.exit(0)
for h in hosts:
    if is_public_ip(h):
        print(h); sys.exit(0)
print("")
PY
}

discover_from_history() {
  local hist="$HOME/.zsh_history"
  [[ -f "$hist" ]] || { echo ""; return; }
  local hit
  hit="$(rg -No "ubuntu@((ec2-[A-Za-z0-9-]+\\.compute\\.amazonaws\\.com)|([0-9]{1,3}\\.){3}[0-9]{1,3})" "$hist" 2>/dev/null | tail -n1 | sed 's/^ubuntu@//' || true)"
  echo "$hit"
}

usage() {
  cat <<USAGE
Usage:
  $0 [EC2_HOST] [SSH_KEY_PATH] [SSH_USER]

Examples:
  $0 16.176.148.77 /path/to/stock-ec2-key.pem ubuntu
USAGE
}

main() {
  require_cmd ssh
  require_cmd rg
  require_cmd python3

  local EC2_HOST="${1:-}"
  local SSH_KEY_INPUT="${2:-}"
  local SSH_USER="${3:-$SSH_USER_DEFAULT}"

  if [[ -z "$EC2_HOST" ]]; then
    EC2_HOST="$(discover_from_repo)"
  fi
  if [[ -z "$EC2_HOST" ]]; then
    EC2_HOST="$(discover_from_history)"
  fi
  if [[ -z "$EC2_HOST" ]]; then
    err "EC2 호스트를 자동 탐색하지 못했습니다."
    exit 2
  fi

  local SSH_KEY
  SSH_KEY="$(detect_key_path "$SSH_KEY_INPUT")"
  if [[ -z "$SSH_KEY" || ! -f "$SSH_KEY" ]]; then
    err "SSH 키를 찾지 못했습니다. 인자로 경로를 넘기세요."
    exit 2
  fi
  chmod 400 "$SSH_KEY"

  log "backup 시작: ${EC2_HOST}"
  ssh -i "$SSH_KEY" -o StrictHostKeyChecking=accept-new "${SSH_USER}@${EC2_HOST}" "bash -s" <<'BASH'
set -euo pipefail
BACKUP_DIR="/var/backups/stock"
TS="$(date +'%Y%m%d_%H%M%S')"
sudo mkdir -p "$BACKUP_DIR" "$BACKUP_DIR/db_snapshots"
items=()
[[ -d "/home/ubuntu/stock" ]] && items+=("/home/ubuntu/stock")
[[ -d "/var/lib/stock-backend" ]] && items+=("/var/lib/stock-backend")
[[ -f "/etc/systemd/system/stock-backend.service" ]] && items+=("/etc/systemd/system/stock-backend.service")
if [[ ${#items[@]} -gt 0 ]]; then
  sudo tar -czf "$BACKUP_DIR/stock_${TS}.tar.gz" "${items[@]}"
fi
sudo ls -1t "$BACKUP_DIR"/stock_*.tar.gz 2>/dev/null | tail -n +8 | xargs -r sudo rm -f
echo "[OK] backup: $BACKUP_DIR/stock_${TS}.tar.gz"
BASH
}

if [[ "${1:-}" == "-h" || "${1:-}" == "--help" ]]; then
  usage
  exit 0
fi

main "$@"
