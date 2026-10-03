# AnkiWatch (cloze edition)

Review your AnkiDroid collection on a Wear OS watch, built for a **Galaxy Watch 6** and for
**Enhanced Cloze** decks. AnkiDroid on your phone stays in charge: it schedules the cards,
records your answers and syncs with AnkiWeb as usual. The watch is a remote for it.

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
| Whole note / Focus on cloze | Focus mode (default) shows only the part of a long note holding the tested cloze, plus its heading or parent list item. Either way a card opens scrolled so the tested cloze (then its answer) is fully on screen. |
| Bezel / swipe | Scroll. Swipe right to leave the review. |

The top (Home) button is reserved by Wear OS and is never sent to apps. Tip: Settings →
Advanced features → Customize keys → *Double press* → AnkiWatch opens the app with a
double press of Home.

If the bottom button doesn't grade, check that the same Customize keys screen has the Back
key set to its default *Go back*.

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
<https://developer.android.com/tools/releases/platform-tools>, unzip it (for example to
`C:\platform-tools`) and copy the unzipped AnkiWatch files into that folder.

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

```powershell
.\install.ps1 -Watch 192.168.1.23:41235 -Adb .\adb.exe
```

(macOS/Linux: `ADB=./adb ./install.sh 192.168.1.23:41235`.) The script checks that the
address really is the watch before installing anything. Open **AnkiWatch** on the watch:
your decks appear; tap one to start.

Turn Wireless debugging off again afterwards if you like; it only matters for installing.

### Updating

Download the newer artifact and run the install script again. Both apps must always come
from the **same** download (they are signed together). If the signing key changed, the
script removes the old version first; nothing is lost, because your reviews live in
AnkiDroid.

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
- **"Phone not connected" on the watch**: the phone must be within Bluetooth range of the
  watch (or both on Wi-Fi) with AnkiWatch Phone installed.
- **"AnkiWatch needs permission"**: open AnkiWatch Phone and allow AnkiDroid access.
- **Cards look like plain text**: only cloze note types (Enhanced Cloze, Cloze) get the
  cloze layout; other notes show their template with formatting kept.
- Images show as `[image]` and audio is skipped; the watch has no web view to run card
  JavaScript, which is why cloze notes are laid out natively instead.

## How it is tested

- `core` (plain Kotlin): unit tests plus randomised stress tests: ~200,000 generated cards
  checked against an answer-visibility oracle (a covered answer never appears, an open one
  always does), mutation fuzzing of real card layouts, a sanitizer that must not change what
  is shown, truncation that must never uncover an answer, 1 MB and 50,000-deep inputs.
  Deliberately injected bugs are caught by the suite.
- Watch UI on a round Wear OS emulator: covered answers absent from the screen while walking
  the whole note, the tested cloze and its answer on screen when each side opens (checked by
  position inside the display circle, not just presence), peeking, focus mode, grading buttons
  and holds, real side-button key events, 120 random cards, a 40-section note, malformed
  input; screenshots captured.
- Phone code against the real AnkiDroid app on an emulator: cloze fields and numbers, deck
  counts, answers landing in AnkiDroid's scheduler, oversized notes trimmed under the
  Bluetooth payload limit, and a multi-deck answer loop.
- The downloadable APKs themselves: on a phone emulator the watch APK must be refused and
  the phone APK must install and keep running; on the Wear OS emulator the watch APK must.
  The install scripts are run against a fake adb (watch, phone, wrong address, a watch on
  USB) in bash and PowerShell.

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
