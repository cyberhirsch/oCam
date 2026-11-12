#!/usr/bin/env bash
# Makes the one key every published build is signed with, and hands it to CI as repository secrets.
# Run it once. Keep the keystore and its password file: losing them means every phone has to
# uninstall oCam once more, and uninstalling clears its settings and film simulations.
#
#   ./tools/make-signing-key.sh [path-to-new-keystore]
#
# Needs keytool (from a JDK) and gh, signed in with access to the repository.
set -euo pipefail

KEYSTORE="${1:-$HOME/.ocam/ocam-signing.keystore}"
REPO="cyberhirsch/oCam"
ALIAS="ocam"

if [ -e "$KEYSTORE" ]; then
    echo "$KEYSTORE already exists. Refusing to replace a signing key." >&2
    exit 1
fi

mkdir -p "$(dirname "$KEYSTORE")"
PASSWORD="$(head -c 32 /dev/urandom | base64 | tr -d '/+=\n')"

keytool -genkeypair \
    -keystore "$KEYSTORE" \
    -storetype PKCS12 \
    -alias "$ALIAS" \
    -keyalg RSA -keysize 4096 \
    -validity 36500 \
    -dname "CN=oCam" \
    -storepass "$PASSWORD" \
    -keypass "$PASSWORD"

base64 < "$KEYSTORE" | tr -d '\n' | gh secret set OCAM_KEYSTORE_BASE64 -R "$REPO"
printf '%s' "$PASSWORD" | gh secret set OCAM_KEYSTORE_PASSWORD -R "$REPO"
printf '%s' "$ALIAS" | gh secret set OCAM_KEY_ALIAS -R "$REPO"

( umask 077; printf '%s\n' "$PASSWORD" > "$KEYSTORE.password" )

keytool -list -keystore "$KEYSTORE" -storepass "$PASSWORD" -alias "$ALIAS" | grep -i 'SHA-256' || true
echo
echo "Secrets set on $REPO. Back up both files somewhere that is not this repository:"
echo "  $KEYSTORE"
echo "  $KEYSTORE.password"
