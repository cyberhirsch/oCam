#!/usr/bin/env bash
# Serve the debug APK over the local network so a phone can install it without a
# GitHub login. Run it on a machine on the same network as the phone; it prints a
# QR code pointing at this host and then serves the file until you stop it.
#
#   ./tools/serve-apk.sh                  # fetch the APK from the rolling release
#   APK=/path/to/app-debug.apk ./tools/serve-apk.sh
#   PORT=9000 ./tools/serve-apk.sh
set -euo pipefail

REPO="${REPO:-cyberhirsch/oCam}"
TAG="${TAG:-debug-latest}"
PORT="${PORT:-8000}"
APK="${APK:-}"
LOCAL_BUILD="app/build/outputs/apk/debug/app-debug.apk"

workdir="$(mktemp -d)"
trap 'rm -rf "$workdir"' EXIT

if [ -n "$APK" ]; then
    cp "$APK" "$workdir/app-debug.apk"
elif [ -f "$LOCAL_BUILD" ]; then
    echo "Using locally built APK: $LOCAL_BUILD"
    cp "$LOCAL_BUILD" "$workdir/app-debug.apk"
elif command -v gh >/dev/null 2>&1; then
    echo "Downloading $TAG from $REPO ..."
    gh release download "$TAG" -R "$REPO" -p app-debug.apk -D "$workdir" --clobber
else
    echo "No APK found. Build one with ./gradlew assembleDebug, pass APK=/path/to.apk," >&2
    echo "or install the gh CLI so this script can fetch the release." >&2
    exit 1
fi

# The address the phone has to reach, i.e. this machine's address on the LAN.
ip="$(ip route get 1.1.1.1 2>/dev/null |
    awk '{for (i = 1; i <= NF; i++) if ($i == "src") { print $(i + 1); exit }}')"
[ -n "${ip:-}" ] || ip="$(hostname -I 2>/dev/null | awk '{print $1}')"
[ -n "${ip:-}" ] || ip="localhost"

url="http://$ip:$PORT/app-debug.apk"

echo
echo "  $url"
echo
if command -v qrencode >/dev/null 2>&1; then
    qrencode -t ANSIUTF8 "$url"
elif python3 -c 'import qrcode' >/dev/null 2>&1; then
    python3 - "$url" <<'PY'
import sys
import qrcode

code = qrcode.QRCode(border=2)
code.add_data(sys.argv[1])
code.make(fit=True)
code.print_ascii(invert=True)
PY
else
    echo "  (install qrencode, or pip install qrcode, to get a scannable code here)"
fi
echo
echo "Serving on port $PORT - Ctrl-C to stop."
cd "$workdir"
exec python3 -m http.server "$PORT"
