#!/usr/bin/env bash
# Installs the downloadable release APKs on the running emulator, the way a user would.
#
#   check_release_apks.sh <dir with ankiwatch-*.apk> phone|watch
#
# Both apps share one package name (the Wearable Data Layer requires it), so a phone that
# accepted the watch APK would silently swap its phone app for the watch app. On a phone,
# the watch APK must therefore be refused; the APK meant for the device must install and
# start without crashing.
set -u
dir="$1"
role="$2"
pkg=com.ankiwatch.cloze

fail() { echo "::error::$*"; exit 1; }

adb uninstall "$pkg" >/dev/null 2>&1 || true
echo "Device reports android.hardware.type.watch: $(adb shell pm has-feature android.hardware.type.watch | tr -d '\r')"
echo "Wear OS library: $(adb shell pm list libraries | tr -d '\r' | grep -i 'com.google.android.wearable' || echo none)"

if [ "$role" = phone ]; then
  out="$(adb install "$dir/ankiwatch-watch.apk" 2>&1)"
  echo "watch APK on a phone: $out"
  grep -q INSTALL_FAILED_MISSING_SHARED_LIBRARY <<<"$out" ||
    fail "a phone did not refuse the watch APK; it would replace the phone app"
fi

apk="$dir/ankiwatch-$role.apk"
out="$(adb install "$apk" 2>&1)"
echo "$(basename "$apk"): $out"
grep -q Success <<<"$out" || fail "the release $role APK did not install"

component="$(adb shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.LAUNCHER "$pkg" | tr -d '\r' | tail -n 1)"
echo "Launching $component"
adb logcat -c || true
adb shell am start -W -n "$component" | tr -d '\r'
sleep 5
crash="$(adb logcat -d -b crash 2>/dev/null | grep -A20 "Process: $pkg" || true)"
if [ -n "$crash" ] || [ -z "$(adb shell pidof "$pkg" | tr -d '\r')" ]; then
  echo "$crash"
  fail "the release $role app did not stay running after launch"
fi
echo "OK: the release $role app installs and runs"
adb uninstall "$pkg" >/dev/null 2>&1 || true
