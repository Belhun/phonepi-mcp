#!/usr/bin/env bash
# PhonePi send gateway (127.0.0.1:11044): the only path that can send texts through the
# Google Messages bridge. Only the Home · Phone & Texts bot should call it.
#   start-send-gateway.sh start   # idempotent; creates secrets if missing, starts a respawn loop
#   start-send-gateway.sh stop | status
# Secrets (never printed): ~/.phonepi/send-gateway.token (callers), ~/.phonepi/bridge-send.key (bridge unlock).
# Delete bridge-send.key to re-lock sending immediately (the bridge re-reads it every request).
set -euo pipefail
umask 077

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
SERVER_DIR="$(cd "$SCRIPT_DIR/../server" && pwd)"
DIR="${HOME:-/home/box}/.phonepi"
TOKEN="$DIR/send-gateway.token"
KEY="$DIR/bridge-send.key"
LOG="$DIR/send-gateway.log"
PIDFILE="$DIR/send-gateway.pid"
PORT="${PHONEPI_SEND_GATEWAY_PORT:-11044}"

mkdir -p "$DIR"; chmod 700 "$DIR"

make_secret() { # $1 path
  if [[ ! -s "$1" ]]; then
    ( umask 077; python3 -c 'import secrets;print(secrets.token_hex(32))' > "$1.tmp" && mv "$1.tmp" "$1" )
  fi
  chmod 600 "$1"
}

running() { [[ -f "$PIDFILE" ]] && kill -0 "$(cat "$PIDFILE" 2>/dev/null)" 2>/dev/null; }
listening() { (exec 3<>/dev/tcp/127.0.0.1/"$PORT") 2>/dev/null; }

cmd_run() {
  echo $$ > "$PIDFILE"
  local child=0
  trap 'rm -f "$PIDFILE"; [[ "$child" != 0 ]] && kill "$child" 2>/dev/null; exit 0' INT TERM
  while true; do
    node "$SERVER_DIR/dist/send-gateway-cli.js" >> "$LOG" 2>&1 &
    child=$!
    set +e; wait "$child"; code=$?; set -e
    echo "$(date -u +%Y-%m-%dT%H:%M:%SZ) send gateway exited ($code); restarting in 5s" >> "$LOG"
    sleep 5
  done
}

cmd_start() {
  make_secret "$TOKEN"
  make_secret "$KEY"
  if [[ ! -f "$SERVER_DIR/dist/send-gateway-cli.js" ]]; then
    (cd "$SERVER_DIR" && npx tsc) >> "$LOG" 2>&1
  fi
  if running || listening; then
    echo "send gateway already running on 127.0.0.1:$PORT"
    return 0
  fi
  nohup "$SCRIPT_DIR/start-send-gateway.sh" run </dev/null >> "$LOG" 2>&1 9>&- &
  for _ in $(seq 1 20); do listening && break; sleep 0.5; done
  if listening; then echo "send gateway listening on 127.0.0.1:$PORT"; else echo "send gateway did not start (see $LOG)"; return 1; fi
}

cmd_stop() {
  running && kill "$(cat "$PIDFILE")" 2>/dev/null || true
  rm -f "$PIDFILE"
  echo "send gateway stopped"
}

cmd_status() {
  if listening; then echo "send gateway: listening on 127.0.0.1:$PORT"; else echo "send gateway: down"; fi
  [[ -s "$TOKEN" ]] && echo "token file: present ($TOKEN)" || echo "token file: MISSING"
  [[ -s "$KEY" ]] && echo "bridge key: present" || echo "bridge key: MISSING (sending locked)"
}

case "${1:-start}" in
  run) cmd_run ;; start) cmd_start ;; stop) cmd_stop ;; status) cmd_status ;;
  *) echo "usage: $0 start|stop|status" >&2; exit 2 ;;
esac
