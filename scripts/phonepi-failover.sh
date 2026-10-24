#!/usr/bin/env bash
# Supervise the PhonePi bot-API failover front (127.0.0.1:11043 -> HP or box :11045). No root.
# usage: phonepi-failover.sh {run|start|stop|status}
set -euo pipefail
SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
PP="$HOME/.phonepi"
LOG="${PHONEPI_FAILOVER_LOG:-$PP/failover.log}"
PIDFILE="$PP/failover-watchdog.pid"
CHILD_PIDFILE="$PP/failover.pid"
PY="${PHONEPI_FAILOVER_PY:-$PP/venv/bin/python}"
running() { [[ -f "$PIDFILE" ]] && kill -0 "$(cat "$PIDFILE" 2>/dev/null)" 2>/dev/null; }
ensure_venv() {
  if ! "$PY" -c 'import paramiko, socks' 2>/dev/null; then
    python3 -m venv "$PP/venv" && "$PP/venv/bin/pip" install -q paramiko pysocks
  fi
}
cmd_run() {
  echo $$ > "$PIDFILE"
  local child=0
  trap 'rm -f "$PIDFILE" "$CHILD_PIDFILE"; [[ "${child:-0}" != 0 ]] && kill "$child" 2>/dev/null; exit 0' INT TERM
  while true; do
    "$PY" "$SCRIPT_DIR/phonepi-failover.py" >> "$LOG" 2>&1 &
    child=$!; echo "$child" > "$CHILD_PIDFILE"
    set +e; wait "$child"; code=$?; set -e
    echo "$(date -u +%Y-%m-%dT%H:%M:%SZ) failover front exited ($code); restarting in 2s" >> "$LOG"
    sleep 2
  done
}
case "${1:-}" in
  run) cmd_run ;;
  start)
    if running; then echo "failover running (pid $(cat "$PIDFILE"))"; exit 0; fi
    ensure_venv
    nohup "$SCRIPT_DIR/phonepi-failover.sh" run >> "$LOG" 2>&1 </dev/null &
    echo "failover started" ;;
  stop)
    running && kill "$(cat "$PIDFILE")" 2>/dev/null || true
    [[ -f "$CHILD_PIDFILE" ]] && kill "$(cat "$CHILD_PIDFILE")" 2>/dev/null || true
    rm -f "$PIDFILE" "$CHILD_PIDFILE"; echo "failover stopped" ;;
  status)
    if running; then echo "failover running (pid $(cat "$PIDFILE"))"; else echo "failover not running"; fi ;;
  *) echo "usage: $0 {run|start|stop|status}" >&2; exit 2 ;;
esac
