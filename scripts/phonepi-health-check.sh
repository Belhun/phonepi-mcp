#!/usr/bin/env bash
# Read-only check of the PhonePi loopback health endpoint.
# Exit 0 phone connected, 1 phone offline, 2 server down.
# Never reads the token, calls /api/command, or dials the phone WebSocket.
set -euo pipefail
SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
if [[ -z "${PHONEPI_ENV_FILE:-}" ]]; then
  if [[ -f /etc/phonepi/env ]]; then
    PHONEPI_ENV_FILE=/etc/phonepi/env
  else
    PHONEPI_ENV_FILE="${HOME}/.phonepi/env"
  fi
fi
ENV_FILE="$PHONEPI_ENV_FILE"
if [[ -f "$ENV_FILE" ]]; then
  set -a
  # shellcheck disable=SC1090
  source "$ENV_FILE"
  set +a
fi
exec node "$SCRIPT_DIR/phonepi-health-check.mjs" "$@"
