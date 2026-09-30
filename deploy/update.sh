#!/usr/bin/env bash
# Pull the latest code and restart CallBridge.   Usage: sudo bash deploy/update.sh
set -euo pipefail
APP_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$APP_DIR"
git pull --ff-only
cd server
npm ci --omit=dev --no-audit --no-fund
chmod -R o+rX "$APP_DIR"
# Call recordings folder (added in v1.2)
install -d -o callbridge -g callbridge -m 750 /var/lib/callbridge /var/lib/callbridge/recordings
grep -q '^RECORDINGS_DIR=' .env || echo "RECORDINGS_DIR=/var/lib/callbridge/recordings" >> .env
systemctl restart callbridge
sleep 2
systemctl --no-pager --lines=5 status callbridge
