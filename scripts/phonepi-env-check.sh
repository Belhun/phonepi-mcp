#!/usr/bin/env bash
# Fail closed if the PhonePi env file or WS token is missing.
# Prints a reason to stderr. Never prints the token value.
set -euo pipefail

if [[ -z "${PHONEPI_ENV_FILE:-}" ]]; then
  if [[ -f /etc/phonepi/env ]]; then
    PHONEPI_ENV_FILE=/etc/phonepi/env
  else
    PHONEPI_ENV_FILE="${HOME}/.phonepi/env"
  fi
fi
ENV_FILE="$PHONEPI_ENV_FILE"
MIN_CHARS="${PHONEPI_WS_TOKEN_MIN_CHARS:-32}"

if [[ ! -f "$ENV_FILE" ]]; then
  echo "phonepi env file missing: $ENV_FILE (refusing to start open)" >&2
  exit 1
fi

set -a
# shellcheck disable=SC1090
source "$ENV_FILE"
set +a

token="${PHONEPI_WS_TOKEN:-}"
if [[ -z "$token" ]]; then
  echo "PHONEPI_WS_TOKEN missing in $ENV_FILE (refusing to start open)" >&2
  exit 1
fi
if [[ ${#token} -lt $MIN_CHARS ]]; then
  echo "PHONEPI_WS_TOKEN is too short (need ${MIN_CHARS}+ chars); refusing to start" >&2
  exit 1
fi
exit 0
