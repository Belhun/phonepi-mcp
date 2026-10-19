#!/usr/bin/env bash
# One-command PhonePi recovery after a box refresh. Idempotent; safe to rerun.
# 1) installs Tailscale static binaries if missing, 2) starts tailscaled (userspace,
# SOCKS5 127.0.0.1:1055), 3) rebuilds server/dist if missing, 4) starts the watchdog,
# 5) re-applies `tailscale serve` if logged in, else prints how to log in.
# Never prints the PhonePi token.
set -euo pipefail
TS_VERSION="${TS_VERSION:-1.104.1}"
REPO=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
STATE_DIR=/var/lib/tailscale
SOCK=/var/run/tailscale/tailscaled.sock

if ! command -v tailscaled >/dev/null || ! tailscale version 2>/dev/null | head -1 | grep -qx "$TS_VERSION"; then
  echo "[restore] installing tailscale $TS_VERSION"
  tmp=$(mktemp -d)
  curl -fsSL -o "$tmp/ts.tgz" "https://pkgs.tailscale.com/stable/tailscale_${TS_VERSION}_amd64.tgz"
  tar xzf "$tmp/ts.tgz" -C "$tmp"
  sudo install -m 0755 "$tmp/tailscale_${TS_VERSION}_amd64/tailscale" "$tmp/tailscale_${TS_VERSION}_amd64/tailscaled" /usr/local/bin/
  rm -rf "$tmp"
fi

if ! pgrep -x tailscaled >/dev/null; then
  echo "[restore] starting tailscaled"
  sudo mkdir -p "$STATE_DIR" "$(dirname "$SOCK")"
  sudo sh -c "nohup /usr/local/bin/tailscaled --tun=userspace-networking --socks5-server=127.0.0.1:1055 --state=$STATE_DIR/tailscaled.state --socket=$SOCK > /var/log/tailscaled.log 2>&1 &"
  sleep 4
fi

if [[ ! -f "$REPO/server/dist/index.js" ]]; then
  echo "[restore] building server"
  (cd "$REPO/server" && npm ci && npm run build)
fi

if ! curl -fsS http://127.0.0.1:11043/api/health 2>/dev/null | grep -q '"ok":true'; then
  rm -f /home/box/.phonepi/watchdog.pid /home/box/.phonepi/server.pid
  "$REPO/scripts/phonepi-watchdog.sh" start
  sleep 4
fi
echo "[restore] health: $(curl -fsS http://127.0.0.1:11043/api/health 2>/dev/null | head -c 120 || echo DOWN)"

if sudo tailscale status >/dev/null 2>&1 && ! sudo tailscale status 2>&1 | grep -q "Logged out"; then
  sudo tailscale serve --bg --https=443 http://127.0.0.1:11041 >/dev/null
  echo "[restore] tailscale up; serve status:"; sudo tailscale serve status
else
  echo "[restore] tailscale is LOGGED OUT. Run: sudo tailscale up --hostname=grokbot-box"
  echo "          approve the URL as akcool151, then rerun this script to apply serve."
fi
