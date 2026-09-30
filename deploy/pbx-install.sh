#!/usr/bin/env bash
# CallBridge PBX: Asterisk for the GoIP GSM gateway + website softphone (WebRTC).
# Run after deploy/install.sh:   sudo bash deploy/pbx-install.sh
# Re-running is safe: passwords already in server/.env are kept.
set -euo pipefail

APP_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ENV_FILE="$APP_DIR/server/.env"
TPL="$APP_DIR/deploy/asterisk"
SVC_USER="callbridge"
LIVE_DIR=/var/lib/callbridge/live
SOUNDS_DIR=/var/lib/callbridge/sounds

bold() { printf '\n\033[1;36m==> %s\033[0m\n' "$*"; }
warn() { printf '\033[1;33m[!] %s\033[0m\n' "$*"; }
die()  { printf '\033[1;31m[x] %s\033[0m\n' "$*"; exit 1; }

[ "$(id -u)" -eq 0 ] || die "Run as root:  sudo bash deploy/pbx-install.sh"
[ -f "$ENV_FILE" ] || die "Run deploy/install.sh first ($ENV_FILE not found)."
id "$SVC_USER" >/dev/null 2>&1 || die "User $SVC_USER missing — run deploy/install.sh first."

envget() { sed -n "s/^$1=//p" "$ENV_FILE" | tail -1; }
envset() {
  if grep -q "^$1=" "$ENV_FILE"; then sed -i "s#^$1=.*#$1=$2#" "$ENV_FILE"; else echo "$1=$2" >> "$ENV_FILE"; fi
}

DOMAIN=$(envget PUBLIC_URL | sed -E 's#^https?://##; s#/.*$##')
APP_PORT=$(envget PORT); APP_PORT="${APP_PORT:-3100}"
[ -n "$DOMAIN" ] || die "PUBLIC_URL missing in $ENV_FILE"

bold "1/7  Installing Asterisk"
export DEBIAN_FRONTEND=noninteractive
apt-get update -y
apt-get install -y asterisk asterisk-core-sounds-en fail2ban

bold "2/7  Passwords"
GOIP_PASSWORD=$(envget GOIP_PASSWORD); [ -n "$GOIP_PASSWORD" ] || GOIP_PASSWORD=$(openssl rand -hex 12)
WEB_PASSWORD=$(envget SIP_WEB_PASSWORD); [ -n "$WEB_PASSWORD" ] || WEB_PASSWORD=$(openssl rand -hex 16)
PBX_SECRET=$(envget PBX_SECRET); [ -n "$PBX_SECRET" ] || PBX_SECRET=$(openssl rand -hex 16)

bold "3/7  Folders"
install -d -o asterisk -g "$SVC_USER" -m 2770 "$LIVE_DIR"
install -d -o "$SVC_USER" -g asterisk -m 2775 "$SOUNDS_DIR"
usermod -aG asterisk "$SVC_USER"   # lets the app read PBX status (asterisk -rx)

bold "4/7  Asterisk configuration"
for f in pjsip.conf extensions.conf http.conf rtp.conf; do
  [ -f "/etc/asterisk/$f" ] && [ ! -f "/etc/asterisk/$f.orig" ] && cp "/etc/asterisk/$f" "/etc/asterisk/$f.orig"
  sed -e "s#__GOIP_PASSWORD__#$GOIP_PASSWORD#g" -e "s#__WEB_PASSWORD__#$WEB_PASSWORD#g" \
      -e "s#__PBX_SECRET__#$PBX_SECRET#g" -e "s#__APP_PORT__#$APP_PORT#g" \
      -e "s#__LIVE_DIR__#$LIVE_DIR#g" -e "s#__SOUNDS_DIR__#$SOUNDS_DIR#g" \
      "$TPL/$f" > "/etc/asterisk/$f"
  chown asterisk:asterisk "/etc/asterisk/$f"; chmod 640 "/etc/asterisk/$f"
done
# Ubuntu still ships the deprecated chan_sip; it steals the WebSocket and port 5060 from PJSIP.
grep -q '^noload => chan_sip.so' /etc/asterisk/modules.conf || sed -i 's/^autoload=yes/autoload=yes\nnoload => chan_sip.so/' /etc/asterisk/modules.conf
# Control socket readable by the asterisk group (for status in the dashboard).
if ! grep -q '^astctlgroup' /etc/asterisk/asterisk.conf; then
  printf '\n[files]\nastctlpermissions = 0660\nastctlowner = asterisk\nastctlgroup = asterisk\n' >> /etc/asterisk/asterisk.conf
fi
systemctl enable asterisk
systemctl restart asterisk
sleep 3
asterisk -rx "core show version" >/dev/null || die "Asterisk did not start — check: journalctl -u asterisk -n 50"
asterisk -rx "module show like func_curl" | grep -q func_curl || warn "func_curl is not loaded — call events will not reach the website."

bold "5/7  Nginx: WebSocket for the website softphone"
SITE=/etc/nginx/sites-available/callbridge
if ! grep -q 'location /sip-ws' "$SITE"; then
  python3 - "$SITE" <<'PY'
import sys, re
p = sys.argv[1]
s = open(p).read()
block = """    location /sip-ws {
        proxy_pass http://127.0.0.1:8088/ws;
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";
        proxy_set_header Host $host;
        proxy_read_timeout 86400s;
        proxy_send_timeout 86400s;
    }

"""
s = re.sub(r'(\n\s*location / \{)', '\n' + block.rstrip('\n') + r'\1', s)
open(p, 'w').write(s)
PY
fi
nginx -t
systemctl reload nginx

bold "6/7  Firewall + brute-force protection"
if command -v ufw >/dev/null && ufw status | grep -q "Status: active"; then
  ufw allow 5060/udp >/dev/null
  ufw allow 10000:20000/udp >/dev/null
fi
cat > /etc/fail2ban/jail.d/callbridge-asterisk.local <<'JAIL'
[asterisk]
enabled  = true
logpath  = /var/log/asterisk/messages*
maxretry = 5
bantime  = 86400
JAIL
systemctl enable --now fail2ban
systemctl restart fail2ban || warn "fail2ban did not restart — SIP brute-force protection is off."

bold "7/7  Connecting the website"
envset PBX_ENABLED 1
envset PBX_DOMAIN "$DOMAIN"
envset PBX_SECRET "$PBX_SECRET"
envset SIP_WEB_USER webrtc
envset SIP_WEB_PASSWORD "$WEB_PASSWORD"
envset GOIP_USER goip
envset GOIP_PASSWORD "$GOIP_PASSWORD"
envset PBX_LIVE_DIR "$LIVE_DIR"
envset PBX_SOUNDS_DIR "$SOUNDS_DIR"
systemctl restart callbridge
sleep 2
curl -fsS "http://127.0.0.1:$APP_PORT/health" >/dev/null && STATUS=running || STATUS="NOT running — journalctl -u callbridge -n 50"

cat <<DONE

============================================================
  CallBridge PBX is ready (website: $STATUS)

  Line gateway settings (HT813 FXO port, or GoIP Basic VoIP):
    SIP Server / Registrar : $DOMAIN      Port: 5060
    SIP User ID / Phone No : goip
    Authenticate ID        : goip
    Password               : $GOIP_PASSWORD
  Guides: deploy/LANDLINE-GU.md (HT813) · deploy/GOIP-GU.md (SIM)

  Website: https://$DOMAIN → Phone  (log in again if it was open)
============================================================
DONE
