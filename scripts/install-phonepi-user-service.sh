#!/usr/bin/env bash
# Install or remove the systemd --user unit for PhonePi.
# Does not need root. Boot-without-login needs `loginctl enable-linger`,
# which some hosts refuse without polkit. Use scripts/phonepi-watchdog.sh then.
set -euo pipefail

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
UNIT_DIR="${XDG_CONFIG_HOME:-$HOME/.config}/systemd/user"
UNIT_PATH="$UNIT_DIR/phonepi-server.service"
LOG="${PHONEPI_LOG:-/home/box/.phonepi/server.log}"

if [[ -z "${PHONEPI_SERVER_DIR:-}" ]]; then
  if [[ -f "$SCRIPT_DIR/../server/package.json" ]]; then
    PHONEPI_SERVER_DIR=$(cd "$SCRIPT_DIR/../server" && pwd)
  else
    PHONEPI_SERVER_DIR="/workspace/phonepi/server"
  fi
fi

write_unit() {
  mkdir -p "$UNIT_DIR" "$(dirname "$LOG")"
  cat > "$UNIT_PATH" <<EOF
[Unit]
Description=PhonePi phone WebSocket server
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
WorkingDirectory=$PHONEPI_SERVER_DIR
ExecStart=$SCRIPT_DIR/phonepi-server.sh
Restart=on-failure
RestartSec=3
StandardOutput=append:$LOG
StandardError=append:$LOG

[Install]
WantedBy=default.target
EOF
}

cmd_install() {
  if ! command -v systemctl >/dev/null 2>&1; then
    echo "systemctl is not available. Use scripts/phonepi-watchdog.sh install instead." >&2
    exit 1
  fi
  write_unit
  systemctl --user daemon-reload
  systemctl --user enable --now phonepi-server.service
  echo "installed $UNIT_PATH"
  echo "status: systemctl --user status phonepi-server.service"
  echo "logs:   tail -f $LOG"
  echo "stop:   systemctl --user disable --now phonepi-server.service"
  echo
  echo "To start at boot with no login session:"
  echo "  loginctl enable-linger \"\$USER\""
  echo "If linger is denied, remove this unit and use the watchdog:"
  echo "  $SCRIPT_DIR/install-phonepi-user-service.sh uninstall"
  echo "  $SCRIPT_DIR/phonepi-watchdog.sh install"
  echo "  $SCRIPT_DIR/phonepi-watchdog.sh start"
}

cmd_uninstall() {
  if command -v systemctl >/dev/null 2>&1; then
    systemctl --user disable --now phonepi-server.service >/dev/null 2>&1 || true
    systemctl --user daemon-reload || true
  fi
  rm -f "$UNIT_PATH"
  echo "removed $UNIT_PATH"
}

cmd_status() {
  if command -v systemctl >/dev/null 2>&1; then
    systemctl --user status phonepi-server.service --no-pager || true
  else
    echo "systemctl is not available"
  fi
  echo "log file: $LOG"
}

case "${1:-install}" in
  install) cmd_install ;;
  uninstall) cmd_uninstall ;;
  status) cmd_status ;;
  *)
    echo "usage: $0 {install|uninstall|status}" >&2
    exit 2
    ;;
esac
