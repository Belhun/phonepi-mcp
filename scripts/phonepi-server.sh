#!/usr/bin/env bash
# Start the PhonePi server in the foreground.
# Sources PHONEPI_ENV_FILE, else /etc/phonepi/env (HP), else ~/.phonepi/env (box).
# Refuses to start if the env file or PHONEPI_WS_TOKEN is missing.
# Does not print the environment. Logs stay on the caller's stdout/stderr.
set -euo pipefail

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
if [[ -z "${PHONEPI_ENV_FILE:-}" ]]; then
  if [[ -f /etc/phonepi/env ]]; then
    PHONEPI_ENV_FILE=/etc/phonepi/env
  elif [[ -f "${HOME}/.phonepi/env" ]]; then
    PHONEPI_ENV_FILE="${HOME}/.phonepi/env"
  else
    PHONEPI_ENV_FILE="/home/box/.phonepi/env"
  fi
fi
ENV_FILE="$PHONEPI_ENV_FILE"
export PHONEPI_ENV_FILE

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

if ! "$SCRIPT_DIR/phonepi-env-check.sh"; then
  exit 1
fi

set -a
# shellcheck disable=SC1090
source "$ENV_FILE"
set +a

cd "$PHONEPI_SERVER_DIR"
if [[ ! -f dist/index.js ]]; then
  echo "phonepi dist/index.js is missing in $PHONEPI_SERVER_DIR (run npm run build)" >&2
  exit 1
fi

exec node dist/index.js "$@"
