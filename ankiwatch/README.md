# AnkiWatch (cloze edition)

Review your AnkiDroid collection on a Wear OS watch, built for a **Galaxy Watch 6** and for
**Enhanced Cloze** decks. AnkiDroid on your phone stays in charge: it schedules the cards,
records your answers and syncs with AnkiWeb as usual. The watch is a remote for it, and can
take a deck along when the phone stays behind.

Based on [AnkiWatch](https://github.com/OleksandrShabaldas/AnkiWatch) by Oleksandr Shabaldas
(MIT, upstream commit `80d78a3`). See [What changed from upstream](#what-changed-from-upstream).

## Reviewing on the watch

| What you see / do | What happens |
|---|---|
| **Pink box** `[hint]` | The cloze this card tests. Tap it to peek. |
| **Blue box** `[hint]` | A sibling cloze from the same note. Tap to open or close it. |
| Plain text from a `#` cloze | Your anchor statement, shown as context on sibling cards (tested on its own card). |
| **Show answer** | Opens the tested cloze and shows the note's Note / Mnemonics / Extra. |
| **Again** / **Good** | Grade the card. **Hold Again = Hard**, **hold Good = Easy**. Each button shows its next interval; the Hard/Easy intervals are at the end of the answer. |
| **Bottom side button** | Press: show the answer, then press again for **Good**. Hold (½ s): **Again**. Ignored while the next cards load; on *Done!* it returns to the decks. |
| **Bury** | Under the card (scroll down). Hides this card until tomorrow, as Bury does in AnkiDroid; its sibling cards stay. |
| Whole note / Focus on cloze | Focus mode (default) shows only the part of a long note holding the tested cloze, plus its heading or parent list item. Either way a card opens scrolled so the tested cloze (then its answer) is fully on screen. |
| Bezel / swipe | Scroll. Swipe right to leave the review. |

The top (Home) button is reserved by Wear OS and is never sent to apps. Tip: Settings →
Advanced features → Customize keys → *Double press* → AnkiWatch opens the app with a
double press of Home.

If the bottom button doesn't grade, check that the same Customize keys screen has the Back
key set to its default *Go back*.

## Reviewing without your phone

For a walk, the gym or a class: download a deck while the phone is near, then review it on
the watch for as long as you like, with the phone at home or switched off.

1. **Download** (phone nearby): Decks → **Offline review** → **Download a deck**, and pick
   the deck (a parent deck brings its subdecks). It takes a few seconds.
2. **Review** (no phone needed): open AnkiWatch. The *Phone not connected* screen offers
   **Review offline**; the deck list has **Offline review**. Tap the deck and review as usual:
   buttons, side button, Bury.
3. **Back home**: your grades go into AnkiDroid by themselves, in the order you gave them.
   The Offline screen shows how many are still waiting for the phone, and says *All grades
   are in AnkiDroid* within moments of the phone confirming it has them. Opening AnkiWatch
   Phone, or the deck list on the watch, also applies any that are waiting.

How it behaves:

- Cards you press Again on, and new cards in their learning steps, come back after
  AnkiDroid's own delays (shown on the buttons the first time). When only those are left,
  the watch says when the next one is due; **Show it now** brings it early.
- The download is what AnkiDroid had due at that moment, within its daily limits (at most
  1,000 cards). Learning cards that fall due later aren't in it; download again to refresh.
- AnkiDroid schedules each card from your grades when they arrive, exactly as if you had
  answered on the phone. Review dates (days) come out the same; learning steps (minutes)
  count from when the grades reach the phone.
- Don't review the same cards on the phone while the watch has them offline: a card graded
  in both places counts twice. Cards you review on the watch while connected are crossed
  off its downloads automatically.
- A new download waits until the grades from earlier ones have reached the phone, so it
  never brings back cards you already did.

## Install (Galaxy Watch 6 + a PC)

You need: AnkiDroid on your Android phone (synced with your collection), the phone paired
with the watch in Galaxy Wearable, and a PC on the same Wi-Fi as the watch.

### 1. Download the apps

On GitHub open **Actions → AnkiWatch**, pick the newest green run of this branch, and
download the **ankiwatch-apks** artifact (you must be signed in). Unzip it. It contains
`ankiwatch-phone.apk`, `ankiwatch-watch.apk`, `install.ps1` (Windows), `install.sh`
(macOS/Linux) and `SHA256SUMS.txt`.

### 2. Get adb on the PC

Download **SDK Platform-Tools** from
<https://developer.android.com/tools/releases/platform-tools> and unzip it (for example to
`C:\platform-tools`). Then unzip the AnkiWatch download and copy **all** its files into that
same folder: running `dir` there must list `adb.exe`, `install.ps1` and
`ankiwatch-watch.apk` side by side. Open PowerShell in that folder (Shift + right-click in
the folder → *Open PowerShell window here*, or `cd` to it).

### 3. Phone app

Copy **`ankiwatch-phone.apk`** to the phone and open it (allow your file manager to install
apps when asked). Open **AnkiWatch Phone** once and allow access to AnkiDroid; it then lists
your decks. The phone app can stay closed after that.

Only the phone file goes on the phone. `ankiwatch-watch.apk` goes on the watch, from the PC,
in step 5: Galaxy Wearable does not pass sideloaded apps on to the watch, and the phone
refuses the watch file ("App not installed").

(Alternatively install the phone app from the PC with USB debugging on: add `-Phone` below.)

### 4. Watch: turn on wireless debugging

1. Watch: **Settings → About watch → Software information**, tap **Software version**
   repeatedly until developer mode is on.
2. **Settings → Developer options**: turn on **ADB debugging** and **Wireless debugging**.
   Connect the watch to the same Wi-Fi as the PC (Settings → Connections → Wi-Fi → On).
3. In **Wireless debugging → Pair new device** the watch shows a pairing code and an
   address like `192.168.1.23:37099`. On the PC, in the platform-tools folder:

   ```powershell
   .\adb pair 192.168.1.23:37099 123456
   ```

4. Back on the Wireless debugging screen, note the **IP address & Port** (a different port,
   e.g. `192.168.1.23:41235`).

### 5. Install on the watch

The watch app is about 19 MB, and a watch tends to drop Wi-Fi to save power while it is
linked to the phone over Bluetooth. So for the install: put the watch on its charger, keep
its screen on, and turn the watch's Bluetooth off (Settings → Connections) until it is done.
Then, in the platform-tools folder:

```powershell
powershell -ExecutionPolicy Bypass -File .\install.ps1 -Watch 192.168.1.23:41235
```

`-ExecutionPolicy Bypass` lets Windows run the downloaded script this once; it changes no
setting. (macOS/Linux: `./install.sh 192.168.1.23:41235`.) The script checks that the
address really is the watch, copies the app over before installing it, and retries once,
reconnecting, if the copy is cut off.

Without the script, the same thing by hand:

```powershell
.\adb.exe connect 192.168.1.23:41235
.\adb.exe -s 192.168.1.23:41235 install -r --no-streaming .\ankiwatch-watch.apk
```

When it says **Success**, turn the watch's Bluetooth back on (the app reaches the phone over
it) and open **AnkiWatch** on the watch: your decks appear; tap one to start.

Turn Wireless debugging off again afterwards if you like; it only matters for installing.

### Updating

Download the newer artifact and run the install script again. Both apps must always come
from the **same** download (they are signed together). If the signing key changed, the old
version has to be removed first. Your reviews are safe in AnkiDroid, but removing the watch
app also deletes its offline downloads and any grades that haven't reached the phone yet, so
the script asks first: check that the watch's Offline review screen doesn't say *grades
waiting for your phone*, then type `y`. (`-Yes`, or `--yes` for `install.sh`, skips the
question.)

To make updates install in place without that, give the builds a permanent key once:
create a keystore (`keytool -genkeypair -keystore ankiwatch.p12 -alias ankiwatch -keyalg RSA
-keysize 2048 -validity 10000`, use the same password for the store and the key), then add
two repository secrets under Settings → Secrets and variables → Actions:
`ANKIWATCH_KEYSTORE_BASE64` (the file, base64-encoded) and `ANKIWATCH_KEYSTORE_PASSWORD`.
Never commit the keystore; this repository is public.

## Troubleshooting

- **The phone shows "Error / Phone not connected / Retry"**: that is the watch app, so the
  watch file was opened on the phone (builds before 3 October 2026 allowed that, and it
  replaced the phone app, since both apps share one package name). On the phone, uninstall
  AnkiWatch, then install `ankiwatch-phone.apk`. The right app is called **AnkiWatch Phone**
  and lists your decks.
- **The phone's "Watch" row** (it rechecks every few seconds while open):
  - *Connected*: ready; open AnkiWatch on the watch.
  - *AnkiWatch missing on watch*: the watch is connected, but AnkiWatch isn't installed on
    it, or it comes from a different download. Each build is signed with its own key unless
    you set up a permanent one, and the two apps only talk when signed alike: install
    both files from the same download.
  - *No watch connected*: the phone can't see the watch at all. Check Bluetooth, and that
    Galaxy Wearable shows the watch as connected.
- **The watch says "Phone found, but AnkiWatch Phone isn't on it, or it's from a different
  download"**: same cause as above; reinstall both apps from one download.
- **The watch says "Your phone didn't answer"**: open AnkiWatch Phone and read its *Last
  watch request* line. *None yet* (or an old time): the watch's requests aren't reaching
  this app, so reinstall both apps from one download. A time with an error: that error is
  what went wrong on the phone (for example AnkiDroid access).
- **"Phone not connected" on the watch**: the phone must be within Bluetooth range of the
  watch (or both on Wi-Fi) with AnkiWatch Phone installed.
- **"AnkiWatch needs permission"**: open AnkiWatch Phone and allow AnkiDroid access.
- **`adb: failed to install ankiwatch-watch.apk:` with nothing after it**: the transfer to
  the watch was cut off mid-way (a reason after the colon would mean the watch refused the
  app). Charger, screen on, watch Bluetooth off, check the port, then install again with
  `--no-streaming` as in step 5.
- **`The term '.\install.ps1' is not recognized`** or **`-File ... does not exist`**: the
  script isn't in the folder you are in; copy all files from the download next to
  `adb.exe` (step 2).
- **`running scripts is disabled on this system`**: start it as
  `powershell -ExecutionPolicy Bypass -File .\install.ps1 ...` (step 5).
- **Cards look like plain text**: only cloze note types (Enhanced Cloze, Cloze) get the
  cloze layout; other notes show their template with formatting kept.
- Images show as `[image]` and audio is skipped; the watch has no web view to run card
  JavaScript, which is why cloze notes are laid out natively instead.

## How it is tested

- `core` (plain Kotlin): unit tests plus randomised stress tests: ~200,000 generated cards
  checked against an answer-visibility oracle (a covered answer never appears, an open one
  always does), mutation fuzzing of real card layouts, a sanitizer that must not change what
  is shown, truncation that must never uncover an answer, 1 MB and 50,000-deep inputs.
  Offline review: the download format (exact round trips, damaged, oversized and unpacking
  bombs refused), AnkiDroid's interval labels, and the offline queue (AnkiDroid's default
  learning steps, learn-ahead, waiting, random sessions with restarts that must end).
  Deliberately injected bugs are caught by the suite.
- Watch UI on a round Wear OS emulator: covered answers absent from the screen while walking
  the whole note, the tested cloze and its answer on screen when each side opens (checked by
  position inside the display circle, not just presence), peeking, focus mode, grading buttons
  and holds, real side-button key events, Bury on either side (once only, even when tapped
  together with a grade or the side button), 120 random cards with random Bury and focus
  mode, a 40-section note, malformed input. Every control on 192, 204 and 227 dp round
  screens at font sizes from 0.85× to 2×: labels whole and inside the circle up to 1.3×
  (the largest watch setting), the chips under the card wrapping instead of overlapping at
  any size. Offline review with no phone and a fake clock (learning cards coming back,
  waiting, *Show it now*, side button, Bury, a restart mid-session), a 100-card random
  offline session, the Offline screen's states, and the watch's download store (restarts,
  damaged files, overlapping decks, 300 random operations). On the watch's own Data Layer:
  grades the phone acknowledges stop counting as waiting at once, and nothing else does
  (1,250 grades in split acks). Screenshots captured.
- Phone code against the real AnkiDroid app on an emulator: cloze fields and numbers, deck
  counts, answers landing in AnkiDroid's scheduler, Bury (the card leaves today's queue, its
  siblings stay; burying a whole deck empties today's queue; a stale or bogus bury fails
  cleanly), oversized notes trimmed under the Bluetooth payload limit, and a multi-deck
  answer loop with buries mixed in. Offline review: a download is the deck in AnkiDroid's
  own order with labels the watch can read; the same grades given live on one deck and
  offline on its twin (applied later, delivered shuffled) leave identical schedules; a whole
  offline session asks for as many answers per card as AnkiDroid does live; duplicated,
  out-of-order and unreadable grades; 200 offline grades from ten decks in one go; every
  grade the phone is done with acknowledged to the watch before it is deleted, and none it
  isn't; grades kept queued, and not applied twice, while the watch can't be told.
- The downloadable APKs themselves: on a phone emulator the watch APK must be refused and
  the phone APK must install and keep running; on the Wear OS emulator the watch APK must.
  The install scripts are run against a fake adb (watch, phone, wrong address, a watch on
  USB, the question before removing a watch app: yes, no, no answer, `-Yes`) in bash and
  PowerShell.

Run the core suite locally with `./gradlew :core:test` (`-Pstress.iterations=20000` for a
longer run).

## What changed from upstream

- **Cloze layout on the watch** (`core/`): the phone sends the raw cloze field and the tested
  cloze number; the watch lays the note out with the Enhanced Cloze rules (hints, `#`
  anchors, sibling boxes, lists, headings, hidden elements). Upstream sent template text,
  which for Enhanced Cloze showed the answers on the question side.
- Two big buttons (Again/Good, hold for Hard/Easy) and side-button grading.
- Bluetooth payloads kept under the 100 KiB Data Layer limit (big notes no longer hang the
  watch); images' inline data stripped before sending.
- Package visibility for AnkiDroid declared (Android 11+).
- Answer de-duplication keeps true insertion order and writes synchronously.
- Removed: the GitHub self-updater, and deleting notes from the watch (one long press could
  delete a whole note with all its cloze cards).

## Project layout

- `core/`: card parsing and layout rules, no Android dependencies.
- `mobile/`: phone companion (AnkiDroid ContentProvider + Wearable Data Layer).
- `wear/`: watch app (Compose for Wear OS).
- `install/`: install scripts copied next to the APKs by CI.
- CI: `.github/workflows/ankiwatch.yml` at the repository root.
