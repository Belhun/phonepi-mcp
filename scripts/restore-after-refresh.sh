#!/usr/bin/env bash
# Hands-off PhonePi recovery after a box refresh. Idempotent; safe to run any number of times.
# Never prints the PhonePi token or TS_AUTHKEY.
#  1) Tailscale binaries: /usr/local/bin <- ~/.phonepi/bin (persisted); download only as fallback.
#  2) tailscaled as the box user (userspace, SOCKS5 127.0.0.1:1055), state in ~/.phonepi/tailscale
#     (survives refresh; box-owned so the home sync can copy it).
#  3) If logged out and TS_AUTHKEY is set: tailscale up --hostname=grokbot-box with the key.
#  4) tailscale serve --https=443 -> 127.0.0.1:11041.
#  5) Rebuild server/dist if missing; start the watchdog if /api/health is not ok.
#  6) Start the read-only Google Messages bridge (127.0.0.1:11042) if it's down.
set -uo pipefail
umask 077
TS_VERSION="${TS_VERSION:-1.104.1}"
REPO=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
PP="$HOME/.phonepi"
PBIN="$PP/bin"
TSDIR="$PP/tailscale"
SOCK="$TSDIR/tailscaled.sock"
GM_DIR=/workspace/phonepi-linux
LOCK=/tmp/phonepi-restore.lock
# Take the auth key out of the environment so child daemons never inherit it.
TSK="${TS_AUTHKEY:-}"; unset TS_AUTHKEY
log() { echo "[restore $(date +%H:%M:%S)] $*"; }
ts() { /usr/local/bin/tailscale --socket="$SOCK" "$@"; }

exec 9>"$LOCK"
flock -n 9 || { log "another restore is running; exiting"; exit 0; }
mkdir -p "$PBIN" "$TSDIR"

# 0) Re-install the ~/.bashrc autostart hook if a refresh dropped it.
if ! grep -q ">>> phonepi autorestore >>>" "$HOME/.bashrc" 2>/dev/null && [[ -f "$REPO/scripts/bashrc-autorestore.snippet" ]]; then
  { cat "$REPO/scripts/bashrc-autorestore.snippet"; cat "$HOME/.bashrc" 2>/dev/null; } > "$HOME/.bashrc.new" && mv "$HOME/.bashrc.new" "$HOME/.bashrc" && chmod 644 "$HOME/.bashrc"
  log "re-installed ~/.bashrc autorestore hook"
fi

# 1) binaries
if ! /usr/local/bin/tailscaled --version 2>/dev/null | head -1 | grep -qx "$TS_VERSION"; then
  if [[ ! -x "$PBIN/tailscaled" ]] || ! "$PBIN/tailscale" version 2>/dev/null | head -1 | grep -qx "$TS_VERSION"; then
    log "downloading tailscale $TS_VERSION (no persisted copy)"
    tmp=$(mktemp -d)
    curl -fsSL -o "$tmp/ts.tgz" "https://pkgs.tailscale.com/stable/tailscale_${TS_VERSION}_amd64.tgz" \
      && tar xzf "$tmp/ts.tgz" -C "$tmp" \
      && install -m 0755 "$tmp/tailscale_${TS_VERSION}_amd64/tailscale" "$tmp/tailscale_${TS_VERSION}_amd64/tailscaled" "$PBIN/"
    rm -rf "$tmp"
  fi
  log "installing tailscale into /usr/local/bin from $PBIN"
  sudo install -m 0755 "$PBIN/tailscale" "$PBIN/tailscaled" /usr/local/bin/
fi

# 2) tailscaled (box user, persisted state)
if ! pgrep -u "$(id -u)" -x tailscaled >/dev/null; then
  log "starting tailscaled (state $TSDIR)"
  nohup /usr/local/bin/tailscaled --tun=userspace-networking --socks5-server=127.0.0.1:1055 \
    --statedir="$TSDIR" --socket="$SOCK" >>"$TSDIR/tailscaled.log" 2>&1 9>&- &
  for _ in $(seq 1 20); do [[ -S "$SOCK" ]] && break; sleep 0.5; done
  sleep 2
fi
# Make plain `sudo tailscale ...` find this daemon too.
sudo mkdir -p /var/run/tailscale && sudo ln -sfn "$SOCK" /var/run/tailscale/tailscaled.sock

# 3) login if needed
state=$(ts status --json 2>/dev/null | python3 -c 'import sys,json; print(json.load(sys.stdin).get("BackendState",""))' 2>/dev/null)
if [[ "$state" != "Running" ]]; then
  if [[ -n "$TSK" ]]; then
    log "tailscale state '$state'; logging in with TS_AUTHKEY"
    # Key goes through an fd, never argv (ps) or the child env.
    ts up --hostname=grokbot-box --auth-key=file:/dev/fd/8 --timeout=60s 8< <(printf %s "$TSK") >/dev/null 2>&1 \
      || log "tailscale up failed (key expired/revoked? check $TSDIR/tailscaled.log)"
    state=$(ts status --json 2>/dev/null | python3 -c 'import sys,json; print(json.load(sys.stdin).get("BackendState",""))' 2>/dev/null)
  else
    log "tailscale state '$state' and TS_AUTHKEY not set; manual: tailscale --socket=$SOCK up --hostname=grokbot-box"
  fi
fi

# 4) serve
if [[ "$state" == "Running" ]]; then
  if ! ts serve status 2>/dev/null | grep -q "127.0.0.1:11041"; then
    ts serve --bg --https=443 http://127.0.0.1:11041 >/dev/null 2>&1 && log "serve configured" || log "serve failed"
  fi
  log "tailscale: $(ts status --json 2>/dev/null | python3 -c 'import sys,json; s=json.load(sys.stdin)["Self"]; print(s["HostName"], s["DNSName"].rstrip("."), "online" if s.get("Online") else "offline")' 2>/dev/null)"
fi

# 5) PhonePi server
if [[ ! -f "$REPO/server/dist/index.js" ]]; then
  log "building server/dist"
  (exec 9>&-; cd "$REPO/server" && npm ci --silent && npm run build --silent) >/tmp/phonepi-build.log 2>&1 || log "build failed (see /tmp/phonepi-build.log)"
fi
if ! curl -fsS -m 3 http://127.0.0.1:11043/api/health 2>/dev/null | grep -q '"ok":true'; then
  if ! "$REPO/scripts/phonepi-watchdog.sh" status 2>/dev/null | grep -q "watchdog running"; then
    rm -f "$PP/watchdog.pid" "$PP/server.pid"
    "$REPO/scripts/phonepi-watchdog.sh" start >/dev/null 9>&-
  fi
  for _ in $(seq 1 20); do curl -fsS -m 2 http://127.0.0.1:11043/api/health 2>/dev/null | grep -q '"ok":true' && break; sleep 1; done
fi
log "health: $(curl -fsS -m 3 http://127.0.0.1:11043/api/health 2>/dev/null | python3 -c 'import sys,json; h=json.load(sys.stdin); print("ok" if h.get("ok") else "NOT ok", "phoneConnected=%s" % h.get("phoneConnected"))' 2>/dev/null || echo DOWN)"

# 6) Google Messages bridge (read-only, loopback). Same launch as get-texts.sh -status.
if [[ -x "$GM_DIR/get-texts.sh" ]] && ! (exec 3<>/dev/tcp/127.0.0.1/11042) 2>/dev/null; then
  log "starting read-only gmessages bridge"
  (cd "$GM_DIR" && env -u OPENMESSAGES_READONLY timeout 60 ./get-texts.sh -status </dev/null >/dev/null 2>&1 9>&-) || log "gmessages bridge did not start (re-pair needed?)"
fi
(exec 3<>/dev/tcp/127.0.0.1/11042) 2>/dev/null && log "gmessages bridge: listening" || log "gmessages bridge: down"
/workspace/phonepi/scripts/start-send-gateway.sh start >/dev/null 2>&1 9>&- || log "send gateway did not start"
(exec 3<>/dev/tcp/127.0.0.1/11044) 2>/dev/null && log "send gateway: listening" || log "send gateway: down"
exit 0
