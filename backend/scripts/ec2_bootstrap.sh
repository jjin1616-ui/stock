#!/usr/bin/env bash
set -euo pipefail

log() { printf "[%s] %s\n" "$(date +'%Y-%m-%d %H:%M:%S')" "$*"; }
err() { printf "[ERROR] %s\n" "$*" >&2; }
warn() { printf "[WARN] %s\n" "$*" >&2; }

REMOTE_DIR="${REMOTE_DIR:-$HOME/stock/backend}"
REMOTE_BASE="${REMOTE_BASE:-$HOME/stock}"
USER_NAME="${USER_NAME:-ubuntu}"
APP_MODULE="${APP_MODULE:-app.main:app}"
DOMAIN="${DOMAIN:-}"
ENV_FILE="/etc/stock-backend.env"

if [[ ! -d "$REMOTE_DIR" ]]; then
  err "REMOTE_DIR not found: $REMOTE_DIR"
  exit 1
fi

cd "$REMOTE_DIR"

log "Install packages"
sudo apt-get update -y
sudo apt-get install -y python3 python3-venv python3-pip nginx certbot python3-certbot-nginx

log "Ensure data dir (/var/lib/stock-backend)"
sudo mkdir -p /var/lib/stock-backend
sudo chown -R "${USER_NAME}:${USER_NAME}" /var/lib/stock-backend

log "Python venv setup"
python3 -m venv .venv
source .venv/bin/activate
pip install --upgrade pip
pip install -r requirements.txt

log "Migrate per-server env (if needed)"
if [[ ! -f "$ENV_FILE" ]]; then
  # Try to extract existing Environment= lines so we don't wipe secrets like KRX_API_KEY,
  # BOOTSTRAP_MASTER_PASSWORD, etc.
  if [[ -f /etc/systemd/system/stock-backend.service ]]; then
    sudo awk -F'Environment=' '/^Environment=/{print $2}' /etc/systemd/system/stock-backend.service \
      | grep -v -E '^(APP_TZ|STOCK_DATA_DIR|DATABASE_URL|RESET_DB_ON_START)=' \
      | sudo tee "$ENV_FILE" >/dev/null || true
  fi
  # Ensure the file exists even if migration yielded nothing.
  sudo touch "$ENV_FILE"
  sudo chmod 600 "$ENV_FILE"
fi

if ! sudo grep -q '^AUTH_PEPPER=' "$ENV_FILE"; then
  # AUTH_PEPPER must be stable across deploys; generate once.
  pepper="$(python3 - <<'PY'
import secrets
print(secrets.token_hex(24))
PY
)"
  echo "AUTH_PEPPER=${pepper}" | sudo tee -a "$ENV_FILE" >/dev/null
  sudo chmod 600 "$ENV_FILE"
fi

firebase_json="$(
  sudo awk -F= '
    /^FIREBASE_ADMIN_JSON=/{
      sub(/^FIREBASE_ADMIN_JSON=/, "", $0);
      print $0
    }
  ' "$ENV_FILE" | tail -n1 | tr -d "\r" | sed -e 's/^"//' -e 's/"$//'
)"
if [[ -z "$firebase_json" ]]; then
  warn "FIREBASE_ADMIN_JSON not set in ${ENV_FILE} (push send will not work)."
elif ! sudo test -f "$firebase_json"; then
  warn "FIREBASE_ADMIN_JSON points to missing file: ${firebase_json}"
else
  log "Firebase admin json detected: ${firebase_json}"
fi

log "Render systemd template"
sudo install -d -m 755 /etc/systemd/system
sudo sed \
  -e "s|__USER__|${USER_NAME}|g" \
  -e "s|__WORKDIR__|${REMOTE_DIR}|g" \
  -e "s|__APP_MODULE__|${APP_MODULE}|g" \
  "$REMOTE_DIR/templates/stock-backend.service" | sudo tee /etc/systemd/system/stock-backend.service >/dev/null

log "Enable stock-backend service"
sudo systemctl daemon-reload
sudo systemctl enable stock-backend
sudo systemctl restart stock-backend
sudo systemctl --no-pager --full status stock-backend || true

log "Backend local health check"
for i in $(seq 1 30); do
  if curl -fsS http://127.0.0.1:8000/health >/dev/null 2>&1; then
    break
  fi
  sleep 1
done
curl -fsS http://127.0.0.1:8000/health >/dev/null

log "Render nginx template"
local_server_name="_"
if [[ -n "$DOMAIN" ]]; then
  local_server_name="$DOMAIN"
fi
sudo sed \
  -e "s|__SERVER_NAME__|${local_server_name}|g" \
  "$REMOTE_DIR/templates/nginx_stock_api.conf" | sudo tee /etc/nginx/sites-available/stock-api >/dev/null

sudo ln -sfn /etc/nginx/sites-available/stock-api /etc/nginx/sites-enabled/stock-api
if [[ -f /etc/nginx/sites-enabled/default ]]; then
  sudo rm -f /etc/nginx/sites-enabled/default
fi

sudo nginx -t
sudo systemctl enable nginx
sudo systemctl reload nginx

log "Nginx local health check"
for i in $(seq 1 20); do
  if curl -fsS http://127.0.0.1/health >/dev/null 2>&1; then
    break
  fi
  sleep 1
done
curl -fsS http://127.0.0.1/health >/dev/null

if [[ -n "$DOMAIN" ]]; then
  log "Try HTTPS certbot for domain=${DOMAIN}"
  CERTBOT_EMAIL="${CERTBOT_EMAIL:-admin@${DOMAIN}}"
  if sudo certbot --nginx -d "$DOMAIN" --non-interactive --agree-tos --redirect -m "$CERTBOT_EMAIL"; then
    sudo systemctl enable certbot.timer || true
    sudo systemctl start certbot.timer || true
    sudo certbot renew --dry-run || true
    log "HTTPS enabled: https://${DOMAIN}/"
  else
    err "certbot failed. Domain DNS/A record or 80/443 routing 확인 필요"
  fi
else
  log "DOMAIN 미설정: HTTPS 생략 (HTTP만 활성)"
fi

log "Done"
