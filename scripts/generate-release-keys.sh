#!/usr/bin/env bash
# Generate the release keystore and Ed25519 manifest key outside the repo.
# Never commit the outputs. Directory mode 700, private files mode 600.
set -euo pipefail

DIR="${PHONEPI_RELEASE_DIR:-$HOME/.phonepi/release}"
mkdir -p "$DIR"
chmod 700 "$DIR"

STORE="$DIR/phonepi-release.p12"
ALIAS="${PHONEPI_RELEASE_KEY_ALIAS:-phonepi}"
PRIV="$DIR/manifest-ed25519.pem"
PUB="$DIR/manifest-ed25519.pub"

if [[ -e "$STORE" ]]; then
  echo "Keystore already exists at $STORE (not overwriting)"
else
  if [[ -z "${PHONEPI_RELEASE_STORE_PASSWORD:-}" ]]; then
    read -r -s -p "Keystore password (min 6 chars, not echoed): " PHONEPI_RELEASE_STORE_PASSWORD
    echo
    read -r -s -p "Confirm password: " CONFIRM
    echo
    if [[ "$PHONEPI_RELEASE_STORE_PASSWORD" != "$CONFIRM" ]]; then
      echo "Passwords did not match" >&2
      exit 1
    fi
  fi
  if [[ ${#PHONEPI_RELEASE_STORE_PASSWORD} -lt 6 ]]; then
    echo "Password must be at least 6 characters" >&2
    exit 1
  fi
  keytool -genkeypair -v \
    -keystore "$STORE" \
    -storetype PKCS12 \
    -alias "$ALIAS" \
    -keyalg RSA \
    -keysize 4096 \
    -validity 10000 \
    -storepass "$PHONEPI_RELEASE_STORE_PASSWORD" \
    -keypass "$PHONEPI_RELEASE_STORE_PASSWORD" \
    -dname "CN=PhonePi Release, O=PhonePi, C=US"
  chmod 600 "$STORE"
  echo "Wrote $STORE"
fi

if [[ -e "$PRIV" ]]; then
  echo "Ed25519 private key already exists at $PRIV (not overwriting)"
else
  node --input-type=module -e "
import { generateKeyPairSync } from 'crypto';
import { writeFileSync } from 'fs';
const { publicKey, privateKey } = generateKeyPairSync('ed25519');
writeFileSync(process.argv[1], privateKey.export({ type: 'pkcs8', format: 'pem' }), { mode: 0o600 });
const der = publicKey.export({ type: 'spki', format: 'der' });
const hex = Buffer.from(der.subarray(der.length - 32)).toString('hex') + '\n';
writeFileSync(process.argv[2], hex, { mode: 0o644 });
" "$PRIV" "$PUB"
  chmod 600 "$PRIV"
  echo "Wrote $PRIV and $PUB"
fi

echo
echo "Keys stay in $DIR (outside git). Export these for assembleRelease:"
echo "  export PHONEPI_RELEASE_STORE_FILE=\"$STORE\""
echo "  export PHONEPI_RELEASE_STORE_PASSWORD='(the password you just set)'"
echo "  export PHONEPI_RELEASE_KEY_ALIAS=\"$ALIAS\""
echo "  export PHONEPI_UPDATE_PUBKEY_FILE=\"$PUB\""
echo "  export PHONEPI_UPDATE_PRIVKEY_FILE=\"$PRIV\""
echo
echo "Then: cd phonepi-android && ./gradlew assembleRelease"
echo "Sign a dropped APK with: scripts/sign-update-manifest.sh"
echo "See docs/release-signing.md for the one-time debug → release uninstall."
