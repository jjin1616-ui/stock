#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
REMOTE_BASE_DEFAULT="/home/ubuntu/stock"
REMOTE_DIR_DEFAULT="${REMOTE_BASE_DEFAULT}/backend"
APP_MODULE_OVERRIDE="${APP_MODULE:-}"
DOMAIN_OVERRIDE="${DOMAIN:-}"
SSH_USER_DEFAULT="ubuntu"

log() { printf "[%s] %s\n" "$(date +'%Y-%m-%d %H:%M:%S')" "$*"; }
warn() { printf "[WARN] %s\n" "$*" >&2; }
err() { printf "[ERROR] %s\n" "$*" >&2; }

require_cmd() {
  command -v "$1" >/dev/null 2>&1 || { err "missing command: $1"; exit 1; }
}

detect_key_path() {
  local provided="${1:-}"
  if [[ -n "$provided" ]]; then
    echo "$provided"
    return
  fi
  local candidates=(
    "/mnt/data/stock-ec2-key.pem"
    "$HOME/Downloads/codex.pem"
    "$HOME/.ssh/stock-ec2-key.pem"
  )
  local c
  for c in "${candidates[@]}"; do
    if [[ -f "$c" ]]; then
      echo "$c"
      return
    fi
  done
  echo ""
}

is_ip_or_host() {
  [[ "$1" =~ ^[A-Za-z0-9._-]+$ ]]
}

discover_from_repo() {
  python3 - <<'PY' "$ROOT_DIR"
import ipaddress, pathlib, re, sys
root = pathlib.Path(sys.argv[1])
patterns = re.compile(r"https?://[A-Za-z0-9._-]+|ec2-[A-Za-z0-9-]+\.compute\.amazonaws\.com|(?:\d{1,3}\.){3}\d{1,3}")
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
    if re.match(r"^ec2-[A-Za-z0-9-]+\.compute\.amazonaws\.com$", h):
        print(h); sys.exit(0)
for h in hosts:
    if h in seen:
        pass
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

discover_domain() {
  local from_env="${DOMAIN_OVERRIDE}"
  if [[ -n "$from_env" ]]; then
    echo "$from_env"
    return
  fi

  python3 - <<'PY' "$ROOT_DIR"
import ipaddress, pathlib, re, sys
root = pathlib.Path(sys.argv[1])
rx = re.compile(r"https?://([A-Za-z0-9.-]+)")
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
    for line in txt.splitlines():
        lower = line.lower()
        if not any(k in lower for k in ("domain", "base_url", "server_url", "api_url")):
            continue
        hosts.extend(rx.findall(line))

def is_ip(v: str) -> bool:
    try:
        ipaddress.ip_address(v)
        return True
    except ValueError:
        return False

for h in hosts:
    h = h.strip().lower()
    if h in {"localhost", "127.0.0.1", "0.0.0.0"}:
        continue
    if h.startswith(("10.", "172.", "192.168.")):
        continue
    # Exclude known external/data-source domains; we only want *our* API domain.
    if any(x in h for x in ("naver.com", "google", "firebase", "amazonaws.com", "krx.co.kr", "koreainvestment.com")):
        continue
    if is_ip(h):
        continue
    if "." in h:
        print(h); sys.exit(0)
print("")
PY
}

detect_app_module() {
  if [[ -n "$APP_MODULE_OVERRIDE" ]]; then
    echo "$APP_MODULE_OVERRIDE"
    return
  fi

  python3 - <<'PY' "$ROOT_DIR"
import pathlib, re, sys
root = pathlib.Path(sys.argv[1])
backend = root
cands = []
for p in backend.rglob('*.py'):
    if any(x in p.parts for x in ('.venv','__pycache__','results','scripts')):
        continue
    try:
        t = p.read_text(encoding='utf-8', errors='ignore')
    except Exception:
        continue
    if 'FastAPI(' not in t:
        continue
    for m in re.finditer(r'^(\w+)\s*=\s*FastAPI\s*\(', t, flags=re.M):
        obj = m.group(1)
        rel = p.relative_to(backend).with_suffix('')
        mod = '.'.join(rel.parts)
        score = 0
        if p.name == 'main.py': score += 5
        if rel.parts and rel.parts[0] == 'app': score += 4
        score -= len(rel.parts)
        cands.append((score, f"{mod}:{obj}"))
if not cands:
    print('app.main:app')
else:
    cands.sort(reverse=True)
    print(cands[0][1])
PY
}

usage() {
  cat <<USAGE
Usage:
  $0 [EC2_HOST] [SSH_KEY_PATH] [SSH_USER] [DOMAIN]

Examples:
  $0 13.217.238.162 /mnt/data/stock-ec2-key.pem ubuntu
  DOMAIN=api.example.com $0 13.217.238.162 ~/Downloads/codex.pem ubuntu
  $0   # auto-discover host/key/domain
USAGE
}

main() {
  require_cmd ssh
  require_cmd rsync
  require_cmd scp
  require_cmd rg
  require_cmd python3

  local EC2_HOST="${1:-}"
  local SSH_KEY_INPUT="${2:-}"
  local SSH_USER="${3:-$SSH_USER_DEFAULT}"
  local DOMAIN_INPUT="${4:-}"

  if [[ -z "$EC2_HOST" ]]; then
    EC2_HOST="$(discover_from_repo)"
  fi
  if [[ -z "$EC2_HOST" ]]; then
    EC2_HOST="$(discover_from_history)"
  fi
  if [[ -z "$EC2_HOST" ]]; then
    err "EC2 호스트를 자동 탐색하지 못했습니다. 예: $0 <EC2_PUBLIC_IP> <SSH_KEY_PATH> ubuntu"
    exit 2
  fi
  if ! is_ip_or_host "$EC2_HOST"; then
    err "유효하지 않은 EC2_HOST: $EC2_HOST"
    exit 2
  fi

  local SSH_KEY
  SSH_KEY="$(detect_key_path "$SSH_KEY_INPUT")"
  if [[ -z "$SSH_KEY" || ! -f "$SSH_KEY" ]]; then
    err "SSH 키를 찾지 못했습니다. 인자로 경로를 넘기세요."
    exit 2
  fi
  chmod 400 "$SSH_KEY"

  local DOMAIN
  if [[ -n "$DOMAIN_INPUT" ]]; then
    DOMAIN="$DOMAIN_INPUT"
  else
    DOMAIN="$(discover_domain)"
  fi

  local APP_MODULE
  APP_MODULE="$(detect_app_module)"

  local REMOTE_BASE="${REMOTE_BASE_DEFAULT/ubuntu/$SSH_USER}"
  local REMOTE_DIR="${REMOTE_DIR_DEFAULT/ubuntu/$SSH_USER}"

  log "EC2_HOST=$EC2_HOST"
  log "SSH_USER=$SSH_USER"
  log "SSH_KEY=$SSH_KEY"
  log "APP_MODULE=$APP_MODULE"
  if [[ -n "$DOMAIN" ]]; then
    log "DOMAIN=$DOMAIN"
  else
    log "DOMAIN=(none)"
  fi

  log "0/7 상세카드 뉴스 회귀 preflight"
  python3 "${ROOT_DIR}/scripts/preflight_detail_card_news.py"
  log "0.5/7 자동매매 계약 preflight"
  python3 "${ROOT_DIR}/scripts/preflight_autotrade_contract.py"

  log "1/7 remote 디렉토리 준비"
  ssh -i "$SSH_KEY" -o StrictHostKeyChecking=accept-new "${SSH_USER}@${EC2_HOST}" "mkdir -p '${REMOTE_DIR}' '${REMOTE_BASE}/logs'"

  log "2/7 백업 생성 (서버)"
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
  # tar returns exit code 1 when files change while being archived (common for SQLite DBs);
  # treat that as a warning so deploy doesn't abort.
  set +e
  sudo tar -czf "$BACKUP_DIR/stock_${TS}.tar.gz" "${items[@]}"
  rc=$?
  set -e
  if [[ $rc -ne 0 && $rc -ne 1 ]]; then
    echo "[WARN] backup tar failed (rc=$rc)" >&2
    exit "$rc"
  fi
fi
sudo ls -1t "$BACKUP_DIR"/stock_*.tar.gz 2>/dev/null | tail -n +8 | xargs -r sudo rm -f

# install daily DB snapshot cron (05:10)
sudo tee /usr/local/bin/stock_db_snapshot.sh >/dev/null <<'SNAP'
#!/usr/bin/env bash
set -euo pipefail
SRC="/var/lib/stock-backend/korea_stock_dash.db"
DST_DIR="/var/backups/stock/db_snapshots"
mkdir -p "$DST_DIR"
if [[ -f "$SRC" ]]; then
  ts="$(date +'%Y%m%d')"
  cp -a "$SRC" "$DST_DIR/korea_stock_dash_${ts}.db"
fi
SNAP
sudo chmod +x /usr/local/bin/stock_db_snapshot.sh
sudo tee /etc/cron.d/stock_db_snapshot >/dev/null <<'CRON'
10 5 * * * root /usr/local/bin/stock_db_snapshot.sh >/var/log/stock_db_snapshot.log 2>&1
CRON
BASH

  log "3/7 소스 업로드 (rsync)"
  rsync -az --delete \
    -e "ssh -i ${SSH_KEY} -o StrictHostKeyChecking=accept-new" \
    --exclude '.git' \
    --exclude '.venv' \
    --exclude '__pycache__' \
    --exclude '*.pyc' \
    --exclude '*.pem' \
    --exclude 'build' \
    --exclude 'dist' \
    --exclude 'node_modules' \
    --exclude '*.log' \
    --exclude '.DS_Store' \
    "${ROOT_DIR}/" "${SSH_USER}@${EC2_HOST}:${REMOTE_DIR}/"

  log "4/7 EC2 bootstrap 실행"
  ssh -i "$SSH_KEY" "${SSH_USER}@${EC2_HOST}" \
    "APP_MODULE='${APP_MODULE}' DOMAIN='${DOMAIN}' REMOTE_DIR='${REMOTE_DIR}' REMOTE_BASE='${REMOTE_BASE}' USER_NAME='${SSH_USER}' bash '${REMOTE_DIR}/scripts/ec2_bootstrap.sh'"

  log "5/7 상태 검증"
  ssh -i "$SSH_KEY" "${SSH_USER}@${EC2_HOST}" "sudo systemctl is-active stock-backend && curl -sS http://127.0.0.1:8000/health"

  log "6/7 외부 경로 검증"
  ssh -i "$SSH_KEY" "${SSH_USER}@${EC2_HOST}" "curl -sS http://127.0.0.1/health"

  echo
  echo "=== Deploy Summary ==="
  echo "EC2_HOST: ${EC2_HOST}"
  echo "APP_MODULE: ${APP_MODULE}"
  if [[ -n "$DOMAIN" ]]; then
    echo "Service URL: https://${DOMAIN}/"
  else
    echo "Service URL: http://${EC2_HOST}/"
    echo "(도메인 연결 후 HTTPS 적용: sudo certbot --nginx -d <DOMAIN>)"
  fi
  echo
  echo "운영 체크리스트"
  echo "- 보안그룹: 22(내 IP만), 80/443 허용, 8000 비공개"
  echo "- 서비스 상태: sudo systemctl status stock-backend"
  echo "- 앱 로그: sudo journalctl -u stock-backend -n 200 --no-pager"
  echo "- Nginx 로그: /var/log/nginx/access.log, /var/log/nginx/error.log"
}

if [[ "${1:-}" == "-h" || "${1:-}" == "--help" ]]; then
  usage
  exit 0
fi

main "$@"
