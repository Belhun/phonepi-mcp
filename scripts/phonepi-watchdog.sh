#!/usr/bin/env bash
# Restart the PhonePi server when it exits. No root.
# Boot persistence is a crontab @reboot line (`install`), used when
# `loginctl enable-linger` is not available for the systemd user unit.
set -euo pipefail

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
LOG="${PHONEPI_LOG:-/home/box/.phonepi/server.log}"
PIDFILE="${PHONEPI_PIDFILE:-/home/box/.phonepi/watchdog.pid}"
CHILD_PIDFILE="${PHONEPI_CHILD_PIDFILE:-/home/box/.phonepi/server.pid}"

mkdir -p "$(dirname "$LOG")" "$(dirname "$PIDFILE")"

watchdog_running() {
  [[ -f "$PIDFILE" ]] || return 1
  local pid
  pid=$(cat "$PIDFILE" 2>/dev/null || true)
  [[ -n "${pid}" ]] && kill -0 "$pid" 2>/dev/null
}

cmd_run() {
  echo $$ > "$PIDFILE"
  local child=0
  trap 'rm -f "$PIDFILE" "$CHILD_PIDFILE"; if [[ "${child:-0}" != "0" ]]; then kill "$child" 2>/dev/null || true; fi; exit 0' INT TERM
  while true; do
    "$SCRIPT_DIR/phonepi-server.sh" >> "$LOG" 2>&1 &
    child=$!
    echo "$child" > "$CHILD_PIDFILE"
    set +e
    wait "$child"
    local code=$?
    set -e
    echo "$(date -u +%Y-%m-%dT%H:%M:%SZ) phonepi server exited (${code}); restarting in 3s" >> "$LOG"
    sleep 3
  done
}

cmd_start() {
  if watchdog_running; then
    echo "phonepi watchdog already running (pid $(cat "$PIDFILE"))"
    exit 0
  fi
  nohup "$SCRIPT_DIR/phonepi-watchdog.sh" run >> "$LOG" 2>&1 &
  echo "phonepi watchdog started"
}

cmd_stop() {
  if watchdog_running; then
    kill "$(cat "$PIDFILE")" 2>/dev/null || true
  fi
  if [[ -f "$CHILD_PIDFILE" ]]; then
    kill "$(cat "$CHILD_PIDFILE")" 2>/dev/null || true
  fi
  rm -f "$PIDFILE" "$CHILD_PIDFILE"
  echo "phonepi watchdog stopped"
}

cmd_status() {
  if watchdog_running; then
    echo "watchdog running (pid $(cat "$PIDFILE"))"
  else
    echo "watchdog not running"
  fi
  "$SCRIPT_DIR/phonepi-health-check.sh" || true
}

cmd_install() {
  local line="@reboot $SCRIPT_DIR/phonepi-watchdog.sh start"
  (crontab -l 2>/dev/null | grep -v 'phonepi-watchdog.sh' || true; echo "$line") | crontab -
  echo "installed crontab: $line"
}

cmd_uninstall() {
  (crontab -l 2>/dev/null | grep -v 'phonepi-watchdog.sh' || true) | crontab -
  echo "removed phonepi watchdog crontab entry"
}

case "${1:-}" in
  run) cmd_run ;;
  start) cmd_start ;;
  stop) cmd_stop ;;
  status) cmd_status ;;
  install) cmd_install ;;
  uninstall) cmd_uninstall ;;
  *)
    echo "usage: $0 {run|start|stop|status|install|uninstall}" >&2
    exit 2
    ;;
esac
