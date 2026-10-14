#!/usr/bin/env bash
# Sign ~/.phonepi/updates/manifest.json with the Ed25519 key outside the repo.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT/server"
if [[ ! -f dist/sign-update-cli.js ]]; then
  npm run build
fi
exec node dist/sign-update-cli.js "$@"
