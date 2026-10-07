#!/usr/bin/env bash
# Installs AnkiWatch on your phone and Galaxy Watch from a Mac/Linux PC.
#
# Put this script next to ankiwatch-phone.apk and ankiwatch-watch.apk, then:
#
#   ./install.sh 192.168.1.23:41235           # watch only (phone APK opened on the phone)
#   ./install.sh 192.168.1.23:41235 --phone   # watch + phone over USB debugging
#
# --yes skips the question asked before removing a watch app signed with another key.
#
# The address is the "IP address & Port" under Developer options > Wireless debugging on the
# watch. Pair once first:  adb pair <ip>:<pairing port> <pairing code>
# adb is taken from $ADB, else PATH, else an adb next to this script.
set -euo pipefail

PACKAGE=com.ankiwatch.cloze
HERE="$(cd "$(dirname "$0")" && pwd)"
if [ -z "${ADB:-}" ]; then
  if command -v adb >/dev/null 2>&1; then ADB=adb
  elif [ -x "$HERE/adb" ]; then ADB="$HERE/adb"
  else ADB=adb
  fi
fi
if [ $# -lt 1 ]; then
  echo "usage: install.sh <watch-ip:port> [--phone] [--yes]   (set ADB=/path/to/adb if adb isn't on PATH)" >&2
  exit 1
fi
WATCH="$1"
shift
PHONE=""
YES=""
for arg in "$@"; do
  case "$arg" in
    --phone) PHONE=--phone ;;
    --yes) YES=1 ;;
    *) echo "unknown option: $arg" >&2; exit 1 ;;
  esac
done

# "true" on a Wear OS watch, "false" on a phone; anything else if the device can't say.
watch_feature() {
  "$ADB" -s "$1" shell pm has-feature android.hardware.type.watch 2>/dev/null | tr -d '\r' | tail -n 1 || true
}

# --no-streaming copies the APK over first and then installs it on the device itself, so a
# Wi-Fi hiccup shows up as a failed copy (retried below) rather than a reasonless failure.
install_once() {
  local serial="$1" apk="$2" out
  out="$("$ADB" -s "$serial" install -r --no-streaming "$apk" 2>&1 || true)"
  if grep -qE "INSTALL_FAILED_UPDATE_INCOMPATIBLE|signatures do not match" <<<"$out"; then
    # A build signed with a different key: the old one has to go first.
    if ! confirm_removal "$serial"; then
      printf '%s' "$STOPPED"
      return
    fi
    "$ADB" -s "$serial" uninstall "$PACKAGE" >/dev/null 2>&1 || true
    out="$("$ADB" -s "$serial" install --no-streaming "$apk" 2>&1 || true)"
  fi
  printf '%s' "$out"
}

# What install_once prints when the user chose not to remove the old app.
STOPPED="(stopped before removing the old app)"

# Removing the watch app also deletes its offline downloads and any grades that haven't
# reached the phone yet, so ask first. On a phone nothing is lost: reviews live in AnkiDroid.
confirm_removal() {
  local serial="$1" answer=""
  if [ -n "$YES" ] || [ "$(watch_feature "$serial")" != "true" ]; then
    echo "  Signing key changed - removing the old app first (your reviews live in AnkiDroid)." >&2
    return 0
  fi
  echo "  This download is signed differently, so the old watch app has to be removed first." >&2
  echo "  That also deletes the watch's offline downloads and any grades still waiting for your phone." >&2
  echo '  On the watch, AnkiWatch > Offline review must not say "grades waiting for your phone".' >&2
  printf '  Remove it and install this one? Type y and press Enter: ' >&2
  read -r answer || true
  case "$answer" in
    y* | Y*) return 0 ;;
    *) return 1 ;;
  esac
}

stop_if_declined() {
  if [ "$1" = "$STOPPED" ]; then
    echo "Stopped. Nothing on the watch was changed. Once its grades are in AnkiDroid, run this again." >&2
    exit 1
  fi
}

device_answered() { grep -qE "Success|Failure \[|INSTALL_" <<<"$1"; }

install_apk() {
  local serial="$1" apk="$2" over_wifi="${3:-}" out
  if [ ! -f "$apk" ]; then
    echo "Missing $apk - copy all files from the download next to this script." >&2
    exit 1
  fi
  echo "Installing $(basename "$apk") ($(( $(wc -c <"$apk") / 1048576 )) MB) on $serial ..."
  out="$(install_once "$serial" "$apk")"
  stop_if_declined "$out"
  if ! device_answered "$out"; then
    # No answer from the device's installer: the transfer was cut off.
    echo "  The transfer was cut off; reconnecting and trying once more ..."
    if [ -n "$over_wifi" ]; then "$ADB" connect "$serial" >/dev/null 2>&1 || true; fi
    out="$(install_once "$serial" "$apk")"
    stop_if_declined "$out"
  fi
  if grep -q "INSTALL_FAILED_MISSING_SHARED_LIBRARY" <<<"$out"; then
    echo "$serial is not a Wear OS watch, so it refuses the watch app." >&2
    exit 1
  fi
  if ! grep -q "Success" <<<"$out"; then
    echo "Install failed on $serial:" >&2
    echo "$out" >&2
    if [ -n "$over_wifi" ] && ! device_answered "$out"; then
      echo "The Wi-Fi link to the watch dropped during the copy. Put the watch on its charger and keep" >&2
      echo "its screen on, turn the watch's Bluetooth off until the install is done (Wear OS may switch" >&2
      echo "Wi-Fi off while Bluetooth is connected), check the port under Wireless debugging, and run" >&2
      echo "this again. Turn Bluetooth back on afterwards." >&2
    fi
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
install_apk "$WATCH" "$HERE/ankiwatch-watch.apk" wifi

echo
echo "Done. Open AnkiWatch Phone on the phone once (it asks for AnkiDroid access), then AnkiWatch on the watch."
