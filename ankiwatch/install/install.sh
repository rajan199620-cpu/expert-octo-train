#!/usr/bin/env bash
# Installs AnkiWatch on your phone and Galaxy Watch from a Mac/Linux PC.
#
# Put this script next to ankiwatch-phone.apk and ankiwatch-watch.apk, then:
#
#   ./install.sh 192.168.1.23:41235           # watch only (phone APK opened on the phone)
#   ./install.sh 192.168.1.23:41235 --phone   # watch + phone over USB debugging
#
# The address is the "IP address & Port" under Developer options > Wireless debugging on the
# watch. Pair once first:  adb pair <ip>:<pairing port> <pairing code>
set -euo pipefail

PACKAGE=com.ankiwatch.cloze
HERE="$(cd "$(dirname "$0")" && pwd)"
ADB="${ADB:-adb}"
if [ $# -lt 1 ]; then
  echo "usage: install.sh <watch-ip:port> [--phone]   (set ADB=/path/to/adb if adb isn't on PATH)" >&2
  exit 1
fi
WATCH="$1"
PHONE="${2:-}"

# "true" on a Wear OS watch, "false" on a phone; anything else if the device can't say.
watch_feature() {
  "$ADB" -s "$1" shell pm has-feature android.hardware.type.watch 2>/dev/null | tr -d '\r' | tail -n 1 || true
}

install_apk() {
  local serial="$1" apk="$2" out
  if [ ! -f "$apk" ]; then
    echo "Missing $apk - run this script from the unzipped download." >&2
    exit 1
  fi
  echo "Installing $(basename "$apk") on $serial ..."
  out="$("$ADB" -s "$serial" install -r "$apk" 2>&1 || true)"
  if grep -qE "INSTALL_FAILED_UPDATE_INCOMPATIBLE|signatures do not match" <<<"$out"; then
    # A build signed with a different key: remove the old one first.
    echo "  Signing key changed - reinstalling (review history lives in AnkiDroid, nothing is lost)."
    "$ADB" -s "$serial" uninstall "$PACKAGE" >/dev/null || true
    out="$("$ADB" -s "$serial" install "$apk" 2>&1 || true)"
  fi
  if grep -q "INSTALL_FAILED_MISSING_SHARED_LIBRARY" <<<"$out"; then
    echo "$serial is not a Wear OS watch, so it refuses the watch app." >&2
    exit 1
  fi
  if ! grep -q "Success" <<<"$out"; then
    echo "Install failed on $serial:" >&2
    echo "$out" >&2
    exit 1
  fi
  echo "  OK"
}

"$ADB" version >/dev/null

if [ "$PHONE" = "--phone" ]; then
  phone_serial="$("$ADB" devices | awk -F'\t' '$2 == "device" && $1 !~ /:/ { print $1; exit }')"
  if [ -z "$phone_serial" ]; then
    echo "No phone found over USB. Enable USB debugging on the phone and accept the prompt." >&2
    exit 1
  fi
  if [ "$(watch_feature "$phone_serial")" = "true" ]; then
    echo "$phone_serial (USB) is a watch, not a phone. Connect the phone with USB debugging." >&2
    exit 1
  fi
  install_apk "$phone_serial" "$HERE/ankiwatch-phone.apk"
  # Same permission the app asks for on first launch.
  "$ADB" -s "$phone_serial" shell pm grant "$PACKAGE" com.ichi2.anki.permission.READ_WRITE_DATABASE 2>/dev/null || true
fi

"$ADB" connect "$WATCH"
# Both apps share one package name, so the watch app on a phone would replace the phone app.
if [ "$(watch_feature "$WATCH")" = "false" ]; then
  echo "$WATCH is not a watch (it looks like a phone). Use the address shown on the WATCH" >&2
  echo "under Settings > Developer options > Wireless debugging > IP address & Port." >&2
  exit 1
fi
install_apk "$WATCH" "$HERE/ankiwatch-watch.apk"

echo
echo "Done. Open AnkiWatch Phone on the phone once (it asks for AnkiDroid access), then AnkiWatch on the watch."
