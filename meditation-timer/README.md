# Meditation Timer (Android)

A quiet meditation app built around a synthesised singing bowl. Everything beyond the core
timer is opt-in, so the defaults behave exactly like the plain timer.

**Download:** [meditation-timer.apk](https://github.com/rajan199620-cpu/expert-octo-train/releases/download/apk/meditation-timer.apk)
always serves the newest build (CI republishes it on every push).

**Welcome and Guide** (first run, then whenever you want it)
- On first launch, a **three-step welcome** modelled on Headspace and Calm:
  1. "Have you meditated before?" sets where you start. New is 5 min and 3 days a week; a
     little is 10 min and 4 days; regularly is 20 min and 5 days.
  2. "When could you sit most days?" ties a reminder to a habit you already have, such as
     waking, lunch, getting home or bed. The notification permission is asked right here, in
     context, as Android's guidance says.
  3. **How to sit** in three points, then **Begin my first sit**. It goes through the usual
     check-in, so nothing starts until you tap Begin.
- **Skip** is on every step, and tapping an answer moves on. People who have used the app
  before (any history or saved settings) never see the welcome.
- There's **no tour of the screens**. NN/g's test of up-front tutorials (70 users) found they
  didn't make people faster or more successful, and the tasks felt harder. Each screen here
  explains itself where you use it instead.
- **Guide** (Sit screen → Guide) is reference for when you want it. It has ten short topics,
  and one opens at a time:
  - how to sit
  - how long and how often (citing Basso et al. 2019: 13 min a day helped after 8 weeks,
    not 4)
  - a sit step by step
  - noticing
  - bells and sound
  - Breathe and Mala
  - lessons
  - History
  - shortcuts
  - your data
- The Guide can also show the welcome again.

**Today's reading** (first thing each day)
- **What it is:** opening the app shows the day's lesson with its research in full (finding,
  source and strength of evidence). Next brings one **common problem**, then you continue to
  the sit.
- **How often:** it shows on every open until it has been read or skipped that day, then not
  again until tomorrow. It never interrupts a running sit or a one-tap sit from the widget or a
  shortcut. Switch it off in Practice tools.
- **Common problems:** 15 of them, including:
  - restlessness and drowsiness;
  - an idea you want to write down;
  - an itch, and numb legs;
  - noise, the phone, coughs;
  - missing a day;
  - anxiety, strong emotions, unusual sensations;
  - dizziness while breathing, and standing up.

  Each answers whether to act on it, what it does and what to do, with studies (e.g.
  Masicampo & Baumeister 2011, Scullin et al. 2018, Bowen & Marlatt 2009, Goldberg et al.
  2022). Evidence studied outside meditation is labelled "indirect". All 15 are in Guide →
  Common problems.

**Settle-in breaths and standing up**
- **Settle-in breaths** (Bells & breaths; on by default, 1 minute; Off/30 s/1/2/5 min): slow
  breathing, 4 s in and 6 s out, with a light buzz at each change. The opening bell rings as
  it ends and the sit uses the natural breath.
- **Why they're optional:** meditation doesn't require a breathing technique, and no trial
  shows one improves the sit that follows. But slow breathing reliably calms the body within a
  single session (Laborde et al. 2022 meta-analysis; Magnon et al. 2021; Van Diest et al.
  2014), so it's an option, on by default.
- **At the end:** there's no research case for closing breaths. The evidence is about
  standing up: blood pressure can dip in the first seconds, and tensing the legs first
  reduces it (Sheikh et al. 2022). Long cross-legged sits can press a nerve (Afacan et al.
  2025). So the finish screen says how to stand.

**How you compare** (History → Overview)
- **The headline:** how often you sat in the last 4 weeks against 1,120 experienced
  meditators (Vieten et al. 2018: 41% daily, 30% more than weekly, 11% weekly, 18% less), as
  a percentile. In the top band it shows "Top 41%", since no survey can rank people within it.
- **A tap shows:** Indian adults (Pew Research Center 2021: 32% daily, 48% weekly) and new
  meditation-app users (Adams et al. 2026: half do 16 minutes or less in their first month).

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
a line for the average of your last five ratings once you have five; tap to inspect a sit.

**History layout**: three short tabs instead of one long page. **Overview**: this week against
your goal, the month in review, and two headline numbers that open Trends. **Trends**: what a sit
changes, the mood chart, wandering caught, and the calendar. **Sessions**: the log (latest 30 days,
older on request). **Backup** (top right) opens Google backup, file backup/restore and the version.

**Looking back**:
- **On this day**: the Sit screen shows a journal note you wrote on this date a year, six months,
  three months, a month or a week ago (longest ago first). Hide puts it away until tomorrow.
- **Month in review** (History): time sat, days sat, best week, longest sit, what the sits changed
  (check-ins), the feeling rated most often, wandering caught per 10 minutes, and notes written,
  compared with the month before. Opens on last month; ‹ › step through months with sits. For the
  first three days of a month the Sit screen says last month's review is ready.

**Sit screen**: the duration, today's lesson, and Begin. Bells, sound and practice tools are three
rows that say what's set (e.g. "Bell · rain"); tapping one opens just that topic in a sheet from the
bottom, saved when you close it (the row-per-setting pattern of Insight Timer's timer screen).

**Check-in before a sit**: tapping a feeling only selects it (tap again to clear); the sit starts
when you tap **Begin** in the check-in, so there's a moment to settle. "Not now" goes back.

**Breathe tab rhythms**: Coherent, Box and 4-7-8, plus two traditional practices, each with how to
do it and what the evidence shows. **Bhramari** (humming bee: 4 s in, a soft hum for 8 s out; small
trials found lower heart rate and blood pressure and less anxiety, low to moderate certainty).
**Nadi Shodhana** (alternate nostril: 4 s in, 6 s out, the screen says which nostril; many small
trials found lower resting blood pressure and heart rate; effects on heart-rate variability are
mixed). No breath-holding in either; sessions end on a whole round.

**Background sound** (Sit → Sound & stillness, off by default): **Rain**, **Birds** (a
breeze, two whistlers, a dove's low coo, a gentle carol and a distant koel; no quick chirps or
trills: every note is at least 150 ms and glides slowly), **Rain & birds** together, or
**My recording** (any audio file on the phone, looped). Rain and birdsong are generated on the
phone as it plays, so they never loop or repeat and add nothing to the download. The sound has its
own volume (separate from the bell), fades in over 8 s, fades out on pause, dips under every bell
so the bell is always heard, and eases away over the final bell. *Listen* plays 10 s to set it.
Why these two: in a meta-analysis of 18 studies, natural sounds lowered stress and improved mood
and health measures; water sounds did most for health and positive mood, birdsong most for stress
(Buxton et al., PNAS 2021). Natural sounds beat quiet for heart rate, blood pressure and breathing
rate, though not for how stressed people said they felt (Fan & Baharum, Stress 2024). In a pilot
study, experienced meditators preferred silence and beginners preferred gentle sound (Liu & Rice,
Work 2019), so silence stays the default.

**Weekly goal and a forgiving streak** (Sit → Practice tools): set how many days a week you mean
to sit (3–7, default 5, or off). History shows "3 of 5 days this week" and how many weeks running
you've met it; the widget shows the same line. The streak forgives one missed day per Monday–Sunday
week (a rest day; a second miss that week ends it) and History says when a rest day was used:
broken streaks discourage, repairable ones much less (Silverman & Barasch, 2023).

**Practice tools** (Sit → Practice tools, each optional):
- **Count distractions**: during a sit, tap anywhere or press a volume key each time you notice the
  mind has wandered. The screen stays on (at your normal brightness) so taps register. The finish screen and History
  ("Catching the wandering mind", per 10 minutes) show the count, framed as a skill, not a score.
- **Check in before and after**: one tap for how you feel going in (Tense → Calm) and coming out.
  History shows "What a sit changes": the average shift and how often you came out calmer.
- **Daily reminder**: a time plus a habit cue ("After morning tea"), an if-then plan. Skipped on
  days you've already sat; the notification's button starts your usual sit.
- **Home-screen widget**: this week as seven dots and a "Sit · N min" button.

**Shortcuts**: long-press the app icon for *Start my usual sit* (starts your last settings
with no screens in between), *Breathe* or *Mala*.

A streak counts consecutive days with at least one logged sit. It stays alive until a whole
day is missed, so sitting yesterday but not yet today still shows your streak.

## Get the APK

Every push that touches `meditation-timer/` builds an APK and publishes it to the permanent
download link above.

### Keeping your history across updates

Android only installs an update over the existing app if both are signed with the same key.
Builds are signed with the committed test key `app/meditation-debug.keystore` (public Android
debug password, so it protects nothing: it only keeps the signature stable), and CI checks every
APK carries it. A private key in GitHub secrets (`SIGNING_KEYSTORE_BASE64`, `SIGNING_PASSWORD`,
alias `meditation`) replaces it if present, but changing keys breaks Google sign-in until that
key's SHA-1 is registered too. Every build gets a higher version number, so it installs as an update.

### Google account backup (History → Google account)

Backs up sits, journal notes and settings to a hidden app folder in your Google Drive
(`drive.appdata`, a non-sensitive permission: the app can't see anything else in Drive).
Connecting on a fresh install merges the backup back in. Google needs the app registered once:

1. [console.cloud.google.com](https://console.cloud.google.com) → create a project (any name).
2. **APIs & Services → Library** → enable **Google Drive API**.
3. **OAuth consent screen** → External → app name, your email → add your own Google account
   under **Test users** (Testing mode is fine for personal use).
4. **Credentials → Create credentials → OAuth client ID → Android**:
   package `com.rajan.meditationtimer`, SHA-1
   `06:AD:C4:19:CC:57:0D:B0:55:46:A3:F6:D1:62:E1:F0:92:42:44:2F`.

No client ID goes into the app: Google recognises it by package name and signing certificate.

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
