#!/usr/bin/env bash
# Start the PhonePi server in the foreground.
# Sources /home/box/.phonepi/env when that file exists (export lines are fine).
# Does not print the environment. Logs stay on the caller's stdout/stderr.
set -euo pipefail

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
ENV_FILE="${PHONEPI_ENV_FILE:-/home/box/.phonepi/env}"

if [[ -z "${PHONEPI_SERVER_DIR:-}" ]]; then
  if [[ -f "$SCRIPT_DIR/../server/package.json" ]]; then
    PHONEPI_SERVER_DIR=$(cd "$SCRIPT_DIR/../server" && pwd)
  else
    PHONEPI_SERVER_DIR="/workspace/phonepi/server"
  fi
fi

if [[ ! -d "$PHONEPI_SERVER_DIR" ]]; then
  echo "phonepi server dir not found: $PHONEPI_SERVER_DIR" >&2
  exit 1
fi

if [[ -f "$ENV_FILE" ]]; then
  set -a
  # shellcheck disable=SC1090
  source "$ENV_FILE"
  set +a
fi

cd "$PHONEPI_SERVER_DIR"
if [[ ! -f dist/index.js ]]; then
  echo "phonepi dist/index.js is missing in $PHONEPI_SERVER_DIR (run npm run build)" >&2
  exit 1
fi

exec node dist/index.js "$@"
