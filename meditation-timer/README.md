# Meditation Timer (Android)

A quiet meditation app built around a synthesised singing bowl. Everything beyond the core
timer is opt-in, so the defaults behave exactly like the plain timer.

**Sit** (the core)
- **Opening bell** a few seconds after you tap Begin (default 5 s), so you know it is running
  without opening your eyes.
- **Closing bell** a few seconds before the end (default 10 s), so you can come back gently.
- Optional **final bell** at the exact end, and optional **interval bells** every 5/10/15 min.
- Cues as **bell, bell + vibration, or vibration only** (for sitting next to someone).
- Optional **auto Do Not Disturb** for the sit (Priority mode, restored afterwards).
- Optional one-tap **reflection** afterwards (Restless … Deep, plus a line of notes).

**Breathe**: paced breathing (Coherent 5.5/5.5, Box 4-4-4-4, 4-7-8) with an expanding circle
and a small vibration at each phase change, so you can follow it eyes-closed.

**Mala**: japa counter (27/54/108) with a ring of beads. Tap the circle or press **either volume
key** to count; a bell and a strong buzz mark each finished round. The count survives closing the app.

**History**: every sit logged with its date, time, minutes actually sat (early-ended sessions
count if at least 1 minute), rating and note. Current/longest streak, last-7-days and all-time
totals, a 12-week heatmap, and **Back up / Restore** to a CSV file (for a new phone or a reinstall).

**Daily principle**: a 64-lesson course in meditation technique in teaching order (why
practise → habit → attention → posture → breath → thoughts → body → emotions → open awareness →
kindness → daily life). The Sit screen shows today's lesson compactly (title + what to try);
tap *Why & research* for the explanation, the finding, the source (authors · journal · year)
and an **evidence tag** (meta-analysis, randomised trial, lab experiment, observational, small
study, theory). You move on **one lesson per day you sit**, so a missed day never skips a lesson;
progress comes from the session history, so Restore after a reinstall resumes it. Earlier
lessons are in the archive; later ones stay hidden. Findings were checked against
publisher/abstract pages; unsupported instructions were left out, and one claim that failed to
replicate (8-week grey-matter change) was replaced with the larger null result.

**Attention check** (Breathe tab): count breaths 1–9 for five minutes, volume-down on 1–8 and
volume-up on 9 (or the on-screen keys). The score is the share of rounds counted exactly — a
measure of skill rather than mood, adapted from a breath-counting task validated as a measure of
mindfulness (Levinson et al., 2014). Results are kept to compare over weeks.

**Mood chart** (History): one dot per rated sit over the last 12 weeks, Restless → Deep, plus
a line for the average of your last five ratings; tap to inspect a sit.

**Shortcuts**: long-press the app icon for *Start my usual sit* (starts your last settings
with no screens in between), *Breathe* or *Mala*.

A streak counts consecutive days with at least one logged sit. It stays alive until a whole
day is missed, so sitting yesterday but not yet today still shows your streak.

## Get the APK

Every push that touches `meditation-timer/` builds an APK in GitHub Actions
(**Actions → Meditation Timer APK → latest run → Artifacts → meditation-timer-apk**).
Unzip it, copy `app-release.apk` to the phone and open it (allow "install unknown apps").

### Keeping your history across updates

Android only installs an update over the existing app if both are signed with the same key;
otherwise you must uninstall first, which deletes the app's data. So CI signs with a permanent
key kept in two **GitHub Actions secrets** (repo Settings → Secrets and variables → Actions):

- `SIGNING_KEYSTORE_BASE64`: the keystore file, base64-encoded
- `SIGNING_PASSWORD`: its password (key alias `meditation`)

Without them the build still works but uses a throwaway key (the run shows a warning).
Every build also gets a higher version number, so it installs as an update.

Safety net that needs no setup: after every change the app mirrors the history — without
journal notes, since Downloads is shared storage — to
`Downloads/Meditation Timer/meditation-history.csv` (Android 10+). Downloads survive
uninstalling, so after any reinstall: History → **Restore** → pick that file.
**Back up** saves a full copy, notes included, anywhere you like (e.g. Drive).

Build locally with the Android SDK installed: `./gradlew assembleRelease`.

## Design notes

- **Screen off / locked:** the session runs in a foreground service holding a partial
  wake lock, so bells stay on time for long sits. A notification shows a live countdown with
  *Pause* and *End* actions.
- **Pause and a safe End**: Pause freezes the clock and holds the bells (Do Not Disturb lifts
  while paused, so calls get through). End doesn't quit at once: the sit pauses and shows
  "Ending in 5…" with *Keep sitting*, so a stray tap costs nothing. Early ends of a minute or
  more get the same reflection screen as a full sit.
- **Silent / Do Not Disturb:** bells play on the *alarm* stream, which DND lets through by
  default. That means the phone's alarm volume is the ceiling. Use the in-app volume slider
  and *Test* button to set a soft level (the volume keys adjust alarm volume while the app is open).
- **The sound:** `tools/make_bell.py` synthesises `app/src/main/res/raw/bell.wav`
  (D4 bowl, inharmonic partials, ~8 s decay). There's nothing to license. Edit the script and re-run it to change the tone.
- **Do Not Disturb:** needs a one-time grant ("Do Not Disturb access" in system settings; the
  app opens it for you). It never overrides a DND you turned on yourself, and if the app is killed
  mid-sit, DND is restored the next time you open it.
- **History storage:** one line per session in app-private storage (`sessions.csv`), included
  in Android's automatic backup. Breathing and mala practice are not logged there: history is
  sitting time.
- **Logic tests:** `BellSchedule` (when bells ring), `History` (streaks, totals, heatmap, CSV)
  and `Breathing` (breath phases, mala rounds) are pure Kotlin with JUnit tests:
  `./gradlew testReleaseUnitTest`.
