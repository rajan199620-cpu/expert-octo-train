# Meditation Timer (Android)

A quiet meditation timer that rings a synthesised singing bowl:

- **Opening bell** a few seconds after you tap Begin (default 5 s), so you know it is running
  without opening your eyes.
- **Closing bell** a few seconds before the end (default 10 s), so you can come back gently.
- Optional **final bell** at the exact end.
- **History**: every sit is logged with its date, time of day and minutes actually sat
  (sessions ended early count for the time sat, if at least 1 minute). Shows current and
  longest streak, last-7-days and all-time totals, and a dot per day for the past week.

A streak counts consecutive days with at least one logged session. It stays alive until a
whole day is missed, so sitting yesterday but not yet today still shows your streak.

## Get the APK

Every push that touches `meditation-timer/` builds an APK in GitHub Actions
(**Actions → Meditation Timer APK → latest run → Artifacts → meditation-timer-apk**).
Unzip it, copy `app-release.apk` to the phone and open it (allow "install unknown apps").
It is signed with a debug key: fine for your own phone, not for the Play Store.

Build locally with the Android SDK installed: `./gradlew assembleRelease`.

## Design notes

- **Screen off / locked:** the session runs in a foreground service holding a partial
  wake lock, so bells stay on time for long sits. A notification shows a live countdown and
  an *End session* action.
- **Silent / Do Not Disturb:** bells play on the *alarm* stream, which DND lets through by
  default. That means the phone's alarm volume is the ceiling. Use the in-app volume slider
  and *Test* button to set a soft level (the volume keys adjust alarm volume while the app is open).
- **The sound:** `tools/make_bell.py` synthesises `app/src/main/res/raw/bell.wav`
  (D4 bowl, inharmonic partials, ~8 s decay). There's nothing to license. Edit the script and re-run it to change the tone.
- **History storage:** one CSV line per session in app-private storage (`sessions.csv`),
  included in Android's automatic backup.
- **Logic tests:** `BellSchedule` (when bells ring) and `History` (streaks, totals) are pure
  Kotlin with JUnit tests: `./gradlew testReleaseUnitTest`.
