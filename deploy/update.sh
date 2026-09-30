#!/usr/bin/env bash
# Pull the latest code and restart CallBridge.   Usage: sudo bash deploy/update.sh
set -euo pipefail
APP_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$APP_DIR"
git pull --ff-only
cd server
npm ci --omit=dev --no-audit --no-fund
chmod -R o+rX "$APP_DIR"
systemctl restart callbridge
sleep 2
systemctl --no-pager --lines=5 status callbridge
