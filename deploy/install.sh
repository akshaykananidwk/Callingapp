#!/usr/bin/env bash
# CallBridge one-shot installer for Ubuntu 22.04 / 24.04 (also Debian 12).
# Usage (from the repo root):   sudo bash deploy/install.sh
# Re-running is safe: it keeps the existing database, .env and tokens.
set -euo pipefail

DOMAIN="${DOMAIN:-test.akdwk.in}"
APP_PORT="${APP_PORT:-3100}"
WHISPER_PORT="${WHISPER_PORT:-8765}"
WHISPER_DIR="${WHISPER_DIR:-/opt/whisper.cpp}"
APP_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SERVER_DIR="$APP_DIR/server"
ENV_FILE="$SERVER_DIR/.env"
SVC_USER="callbridge"

bold() { printf '\n\033[1;36m==> %s\033[0m\n' "$*"; }
warn() { printf '\033[1;33m[!] %s\033[0m\n' "$*"; }
die()  { printf '\033[1;31m[x] %s\033[0m\n' "$*"; exit 1; }

[ "$(id -u)" -eq 0 ] || die "Run as root:  sudo bash deploy/install.sh"
[ -f "$SERVER_DIR/package.json" ] || die "Run this from the CallBridge repository (server/ folder not found)."
command -v apt-get >/dev/null || die "This installer supports Ubuntu/Debian (apt) only."

# ---------------------------------------------------------------------------------------------
bold "CallBridge installer — domain: $DOMAIN"
if [ -z "${EMAIL:-}" ]; then read -rp "Email for the SSL certificate (Let's Encrypt): " EMAIL; fi
if [ -z "${ADMIN_USER:-}" ]; then read -rp "Dashboard admin username [admin]: " ADMIN_USER; ADMIN_USER="${ADMIN_USER:-admin}"; fi
if [ -z "${ADMIN_PASS:-}" ]; then
  while true; do
    read -rsp "Dashboard admin password (min 8 chars): " ADMIN_PASS; echo
    read -rsp "Repeat password: " ADMIN_PASS2; echo
    [ "${#ADMIN_PASS}" -ge 8 ] && [ "$ADMIN_PASS" = "$ADMIN_PASS2" ] && break
    warn "Passwords must match and be at least 8 characters."
  done
fi
MEM_MB=$(awk '/MemTotal/ {print int($2/1024)}' /proc/meminfo)
DEFAULT_MODEL=small; [ "$MEM_MB" -lt 2000 ] && DEFAULT_MODEL=base
if [ -z "${WHISPER_MODEL:-}" ]; then
  echo "Whisper model: tiny | base | small | medium   (RAM on this server: ${MEM_MB} MB)"
  echo "  small = good balance (needs ~1 GB RAM), medium = better Gujarati but slow on CPU (~2.5 GB RAM)"
  read -rp "Model [$DEFAULT_MODEL]: " WHISPER_MODEL; WHISPER_MODEL="${WHISPER_MODEL:-$DEFAULT_MODEL}"
fi
case "$WHISPER_MODEL" in tiny|base|small|medium|large-v3|large-v3-turbo) ;; *) die "Unknown model $WHISPER_MODEL";; esac

# ---------------------------------------------------------------------------------------------
bold "1/9  System packages"
export DEBIAN_FRONTEND=noninteractive
apt-get update -y
apt-get install -y curl ca-certificates git nginx postgresql build-essential cmake \
  certbot python3-certbot-nginx openssl

bold "2/9  Node.js 20"
if ! command -v node >/dev/null || [ "$(node -p 'process.versions.node.split(".")[0]')" -lt 20 ]; then
  curl -fsSL https://deb.nodesource.com/setup_20.x | bash -
  apt-get install -y nodejs
fi
node -v

bold "3/9  Service user"
id "$SVC_USER" >/dev/null 2>&1 || useradd --system --home-dir /nonexistent --shell /usr/sbin/nologin "$SVC_USER"

bold "4/9  PostgreSQL"
systemctl enable --now postgresql
DB_PASS=""
if [ -f "$ENV_FILE" ]; then
  DB_PASS=$(sed -n 's#^DATABASE_URL=postgres://callbridge:\([^@]*\)@.*#\1#p' "$ENV_FILE")
fi
[ -n "$DB_PASS" ] || DB_PASS=$(openssl rand -hex 16)
if sudo -u postgres psql -tAc "SELECT 1 FROM pg_roles WHERE rolname='callbridge'" | grep -q 1; then
  sudo -u postgres psql -qc "ALTER ROLE callbridge WITH LOGIN PASSWORD '$DB_PASS'"
else
  sudo -u postgres psql -qc "CREATE ROLE callbridge WITH LOGIN PASSWORD '$DB_PASS'"
fi
if ! sudo -u postgres psql -tAc "SELECT 1 FROM pg_database WHERE datname='callbridge'" | grep -q 1; then
  sudo -u postgres createdb -O callbridge callbridge
fi

bold "5/9  App configuration (.env)"
if [ ! -f "$ENV_FILE" ]; then
  cat > "$ENV_FILE" <<ENV
PORT=$APP_PORT
HOST=127.0.0.1
PUBLIC_URL=https://$DOMAIN
DATABASE_URL=postgres://callbridge:$DB_PASS@127.0.0.1:5432/callbridge
STT_PROVIDER=whisper_cpp
WHISPER_URL=http://127.0.0.1:$WHISPER_PORT/inference
OPENAI_API_KEY=
OPENAI_MODEL=whisper-1
DEFAULT_LANGUAGE=auto
CHUNK_SECONDS=5
ENV
else
  echo "Keeping existing $ENV_FILE"
  APP_PORT=$(sed -n 's/^PORT=//p' "$ENV_FILE"); APP_PORT="${APP_PORT:-3100}"
fi
chown root:"$SVC_USER" "$ENV_FILE"; chmod 640 "$ENV_FILE"

bold "6/9  Node dependencies"
cd "$SERVER_DIR"
npm ci --omit=dev --no-audit --no-fund
chmod -R o+rX "$APP_DIR"

bold "7/9  whisper.cpp speech-to-text (this can take several minutes)"
if [ ! -x "$WHISPER_DIR/build/bin/whisper-server" ]; then
  [ -d "$WHISPER_DIR/.git" ] || git clone --depth 1 https://github.com/ggml-org/whisper.cpp "$WHISPER_DIR"
  cmake -S "$WHISPER_DIR" -B "$WHISPER_DIR/build" -DCMAKE_BUILD_TYPE=Release
  cmake --build "$WHISPER_DIR/build" -j"$(nproc)" --config Release --target whisper-server
fi
MODEL_FILE="$WHISPER_DIR/models/ggml-$WHISPER_MODEL.bin"
[ -f "$MODEL_FILE" ] || bash "$WHISPER_DIR/models/download-ggml-model.sh" "$WHISPER_MODEL"
chmod -R o+rX "$WHISPER_DIR"
THREADS=$(nproc); [ "$THREADS" -gt 8 ] && THREADS=8

cat > /etc/systemd/system/callbridge-whisper.service <<UNIT
[Unit]
Description=CallBridge whisper.cpp server
After=network.target

[Service]
User=$SVC_USER
ExecStart=$WHISPER_DIR/build/bin/whisper-server -m $MODEL_FILE --host 127.0.0.1 --port $WHISPER_PORT -t $THREADS -l auto
Restart=always
RestartSec=3

[Install]
WantedBy=multi-user.target
UNIT

cat > /etc/systemd/system/callbridge.service <<UNIT
[Unit]
Description=CallBridge server (API, WebSocket, dashboard)
After=network.target postgresql.service callbridge-whisper.service
Wants=postgresql.service

[Service]
User=$SVC_USER
WorkingDirectory=$SERVER_DIR
ExecStart=$(command -v node) src/index.js
Restart=always
RestartSec=3
Environment=NODE_ENV=production

[Install]
WantedBy=multi-user.target
UNIT

# Create/reset the dashboard admin (also runs DB migrations) before the app starts.
sudo -u "$SVC_USER" bash -c "cd '$SERVER_DIR' && node scripts/create-admin.js '$ADMIN_USER' \"\$0\"" "$ADMIN_PASS"

systemctl daemon-reload
systemctl enable --now callbridge-whisper
systemctl enable callbridge
systemctl restart callbridge


bold "8/9  Nginx"
cat > /etc/nginx/sites-available/callbridge <<NGINX
map \$http_upgrade \$cb_connection_upgrade {
    default upgrade;
    ''      close;
}

server {
    listen 80;
    listen [::]:80;
    server_name $DOMAIN;
    client_max_body_size 10m;

    location / {
        proxy_pass http://127.0.0.1:$APP_PORT;
        proxy_http_version 1.1;
        proxy_set_header Upgrade \$http_upgrade;
        proxy_set_header Connection \$cb_connection_upgrade;
        proxy_set_header Host \$host;
        proxy_set_header X-Real-IP \$remote_addr;
        proxy_set_header X-Forwarded-For \$proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto \$scheme;
        proxy_read_timeout 3600s;
        proxy_send_timeout 3600s;
        proxy_buffering off;
    }
}
NGINX
ln -sf /etc/nginx/sites-available/callbridge /etc/nginx/sites-enabled/callbridge
nginx -t
systemctl enable --now nginx
systemctl reload nginx
if command -v ufw >/dev/null && ufw status | grep -q "Status: active"; then
  ufw allow OpenSSH >/dev/null; ufw allow 'Nginx Full' >/dev/null
fi

bold "9/9  HTTPS certificate"
SERVER_IP=$(curl -4 -fsS https://api.ipify.org || true)
DNS_IP=$(getent ahostsv4 "$DOMAIN" | awk 'NR==1{print $1}' || true)
SSL_OK=0
if [ -n "$DNS_IP" ] && [ "$DNS_IP" = "$SERVER_IP" ]; then
  if certbot --nginx -d "$DOMAIN" --non-interactive --agree-tos -m "$EMAIL" --redirect; then SSL_OK=1; fi
else
  warn "DNS for $DOMAIN points to '${DNS_IP:-nothing}', but this server is '$SERVER_IP'."
  warn "Add an A record  $DOMAIN -> $SERVER_IP  and then run:  sudo certbot --nginx -d $DOMAIN --redirect"
fi
if [ "$SSL_OK" -eq 1 ]; then
  sed -i "s#^PUBLIC_URL=.*#PUBLIC_URL=https://$DOMAIN#" "$ENV_FILE"
else
  # Without HTTPS the login cookie must not be marked Secure.
  sed -i "s#^PUBLIC_URL=.*#PUBLIC_URL=http://$DOMAIN#" "$ENV_FILE"
fi
systemctl restart callbridge

sleep 2
if curl -fsS "http://127.0.0.1:$APP_PORT/health" >/dev/null; then
  STATUS="running"
else
  STATUS="NOT running — check: journalctl -u callbridge -n 50"
fi
URL=$([ "$SSL_OK" -eq 1 ] && echo "https://$DOMAIN" || echo "http://$DOMAIN")

cat <<DONE

============================================================
  CallBridge is installed ($STATUS)

  Dashboard : $URL
  Login     : $ADMIN_USER / (the password you entered)

  Next:
   1. Open $URL and sign in
   2. Tokens -> Generate token
   3. Phone app -> VPS URL: $URL   API token: <paste>
============================================================
DONE
