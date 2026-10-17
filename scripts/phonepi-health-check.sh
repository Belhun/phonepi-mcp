#!/usr/bin/env bash
# Read-only check of the PhonePi loopback health endpoint.
# Exit 0 phone connected, 1 phone offline, 2 server down.
# Never reads the token, calls /api/command, or dials the phone WebSocket.
set -euo pipefail
SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
exec node "$SCRIPT_DIR/phonepi-health-check.mjs" "$@"
