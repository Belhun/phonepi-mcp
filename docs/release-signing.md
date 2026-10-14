# Release signing and signed updates

Keys stay **outside the repo**. Default directory `~/.phonepi/release` (mode 700). Private files mode 600. Never commit keystores, PEM keys, or passwords.

## One-time: generate keys on the box

```bash
scripts/generate-release-keys.sh
```

That writes:

- `~/.phonepi/release/phonepi-release.p12` — Android release keystore (PKCS12, alias `phonepi`)
- `~/.phonepi/release/manifest-ed25519.pem` — Ed25519 private key for `manifest.json`
- `~/.phonepi/release/manifest-ed25519.pub` — 32-byte public key as hex (embedded in the APK at build time)

Export (put passwords in your shell profile or a `chmod 600` env file that is not in git):

```bash
export PHONEPI_RELEASE_STORE_FILE="$HOME/.phonepi/release/phonepi-release.p12"
export PHONEPI_RELEASE_STORE_PASSWORD='…'
export PHONEPI_RELEASE_KEY_ALIAS=phonepi
export PHONEPI_UPDATE_PUBKEY_FILE="$HOME/.phonepi/release/manifest-ed25519.pub"
export PHONEPI_UPDATE_PRIVKEY_FILE="$HOME/.phonepi/release/manifest-ed25519.pem"
```

Gradle also reads the same names from `~/.gradle/gradle.properties`. Do not put passwords in this repository.

## Build a release APK

```bash
cd phonepi-android
./gradlew assembleRelease
```

`assembleRelease` fails if the keystore, pin, or update public key is missing. The pin is the SHA-256 of the release signing certificate, computed from the keystore at build time. Debug builds do not require the keystore.

Output: `phonepi-android/app/build/outputs/apk/release/app-release.apk`

## One-time debug → release reinstall

Android will **not** update a debug-signed PhonePi with a release-signed APK (different signing cert). In-app update / `phone_push_update` cannot cross that gap.

On the Pixel, once:

1. Note the saved host / token if you need them (prefs are wiped on uninstall).
2. Uninstall PhonePi.
3. Sideload the **0.2.6** (or newer) **release** APK.
4. Grant overlay, notification access, install-unknown-apps, and reconnect.

After that, in-app updates work because every later APK is signed with the same release cert.

## Drop and sign an update

```bash
cp app-release.apk ~/.phonepi/updates/phonepi-0.2.6-8.apk
scripts/sign-update-manifest.sh --dir ~/.phonepi/updates --min-version 8
```

`manifest.json` fields: `versionCode`, `versionName`, `sha256` (APK), `minVersion`, `size`, `fileName`, `signature` (Ed25519 over `v1\nversionCode\nversionName\nsha256\nminVersion\n`). The phone rejects a bad signature, a cert that is not the pinned release cert, a sha256 mismatch, and a version **downgrade**. **Force update** only reinstalls the **same** `versionCode`.
