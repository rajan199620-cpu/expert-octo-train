# Mindfield: a field guide to the human mind (Android)

One psychology concept every morning. Spot it in the wild by evening. Log how it showed up.

**Download:** [mindfield.apk](https://github.com/rajan199620-cpu/expert-octo-train/releases/download/mindfield-apk/mindfield.apk)
always serves the newest build (CI republishes it on every push). Screenshots of every screen
are in [`docs/screenshots`](docs/screenshots).

## A day with Mindfield

| When | What happens | Why (the research) |
|---|---|---|
| Morning notification | Today's concept, its one-line hook and today's mission | Spaced, bite-sized exposure; the notification puts the idea "on your radar" (the frequency illusion is Day 1 on purpose) |
| Open the app | **Predict first**: guess the study's result. The study stays locked until you guess | Pretesting: guessing, even wrongly, improves memory for the answer (meta-analyses 2023) |
| | **Today's mission** + an **if-then plan** box ("When …, I'll …") | Implementation intentions (Gollwitzer & Sheeran 2006, d = 0.65) |
| Evening notification | **Field report** in one tap from the notification: 👀 Spotted it · 🙋 Caught myself · 🎯 Used it. Then a reply box to add a line without opening the app | Low-friction logging (Daylio-style); linking ideas to your own life (self-reference effect) |
| Optional, mid-day | A **surprise spot check** at a random time between noon and 6 pm | BeReal-style unpredictability keeps attention fresh; off by default (max 2 nudges/day otherwise) |
| Later | **Review**: "Name it" (an everyday story: which concept is this?) or "Remember the study", returning after 1, 3, 7, 16, 35, 90 days | Retrieval practice + spacing, the two "high-utility" study techniques (Dunlosky et al. 2013) |

## The library: 180 concepts, honestly labelled

Nine areas, dealt round-robin so neighbouring days differ (interleaving): memory & perception,
the self, thinking traps, influence & persuasion, emotions & wellbeing, relationships, choices &
money, motivation & habits, groups & society.

Every concept has: a hook, what's going on, **the key study with its numbers**, an **evidence
label**, **"In the real world"**, how to spot it, a mission, a "watch out", a predict-first
question, an everyday scenario for review, sources and "see also" links.

**In the real world** is a second, applied study for every concept, taken from the research
itself rather than invented: colonoscopy patients and the peak-end rule, German judges anchored
by loaded dice, lay counsellors treating depression in Goa (behavioural activation), Chicago
Heights teachers paid upfront (loss aversion), heart-surgery rates either side of an 80th
birthday (left-digit bias), Christian and Muslim football teammates in northern Iraq (contact),
the Challenger launch decision (groupthink), and so on. Each one gives the setting, who was
studied and the numbers, then ends with **"The nuance"**: where the effect stops, what the study
can't show, or how it was later corrected. Like the study, it opens only after you predict, so it
can't give the answer away. Every figure was checked against the original paper or a reliable
summary; where a number couldn't be confirmed, the text stays qualitative.

Evidence labels come from the replication literature (Many Labs, registered replication reports,
meta-analyses): **Solid** (59) · **Good** (91) · **Debated** (17) · **Busted** (13). The busted
ones are taught as myths with a "plot twist" card: ego depletion, power posing, the Stanford
Prison Experiment, the hungry-judge effect, learning styles, the Mozart effect, the backfire
effect, the 7-38-55 rule, the Hawthorne effect, the Zeigarnik memory effect, behavioural priming
(the "Florida effect"), spotting liars from body language, and the Romeo and Juliet effect. Several famous
findings are shown with their modern corrections (the bystander effect vs. CCTV footage, the
"hot hand" maths error, the marshmallow test with family background controlled, Dunning-Kruger's
statistical debate).

After about six months every concept has been seen; the app then brings back the ones you've
spotted least.

### From five popular books (October 2026)

29 concepts were added after checking the principles in *The Gift of Fear* (de Becker),
*Thinking, Fast and Slow* (Kahneman), *The small BIG* (Martin, Goldstein & Cialdini),
*Pre-Suasion* and *Influence* (Cialdini) against the research. Only principles with real
evidence went in, labelled honestly: the halo effect, the law of small numbers, outcome bias,
the affect heuristic, when to trust your gut (Kahneman & Klein), consider the opposite, formulas
beat expert judgement, denominator neglect, the certainty and possibility effects, narrow
framing, less is better, opportunity cost neglect, the premortem, round numbers as goals,
what's focal seems causal, most worries never happen, money and happiness (the revised
plateau), people say yes more than you think, charm that fades, the panic myth, hidden
profiles, basking in reflected glory, and, as *debated*, de Becker's warning signs ("forced
teaming"), broken windows, implicit egotism and moral licensing. Three are taught as myths
(above). Checking the books' sources also moved one existing concept: "But you are free" is now
*debated*, because a 2023 re-examination found its best-controlled studies show no clear effect and
much of the early work came from a researcher with several retracted papers. Principles that
are already in the library, unsupported, or better left to professionals (violence-prediction
checklists, restraining-order advice) were left out; the full verdict for every principle is in
the report that accompanied this change.

New concepts sit at the end of each area, so the order for anyone already using the app is
unchanged up to day 135; people who had seen everything meet the new ones before any repeat.

## Progress, the way the big learning apps do it (October 2026)

Taken from Duolingo, Headway, Elevate, Strava and the Meditation Timer, keeping only what has
research behind it:

- **Today's path** at the top of Today: Predict · Plan · Report (and Review when cards are due).
  Each step is ticked off as it's done, and tapping one scrolls to it, so the long page has a
  map. Showing progress already made helps people finish (the goal-gradient effect: Kivetz,
  Urminsky & Zheng, *JMR* 2006).
- **A forgiving streak**, like Duolingo's streak freeze: one missed day a week doesn't break
  it.
  - A highlighted broken streak makes people less likely to carry on (Silverman & Barasch,
    *JCR* 2023).
  - One missed day doesn't harm a forming habit (Lally et al. 2010).
  - Checked against a brute-force definition on 1,500 random calendars.
- **A weekly goal** (default 5 days with a field report; Off or 3–7 in Settings): room for a
  busy day. It's shown under the week's dots.
- **Month in review** on You: reports, days, discoveries (and myths), sightings, predictions
  right, the area you noticed most and the most-spotted concept. Browse months with ‹ ›.
  - In the first week of a month it opens on last month, since month starts are "fresh
    starts" when people recommit (Dai, Milkman & Riis 2014).
- **How you compare** on You:
  - **Your predictions against blind guessing.** For context, laypeople judging whether 27
    famous findings would replicate were right 59% of the time against 50% for guessing
    (Hoogeveen et al. 2020).
  - **Keeping going against the usual drop-off.** A median 3.3% of people who install a
    mental-health app still use it 30 days later (Baumel et al. 2019).
- **Settings in sheets**: You ends with one short list (notifications, weekly goal, focus areas,
  appearance, Google account, backup file, about the evidence). Each opens its own sheet
  instead of six long cards at the foot of the page. The sign-in notification and the "backup
  paused" banner open the Google sheet directly.

## Screens

- **Today**: the specimen plate (each concept has its own generative emblem in its area's
  colour), predict first, the study, in the real world, mission + plan, field report, review
  nudge, and a floating "Log today" button.
- **Guide**: the collection. Undiscovered concepts stay sealed until their day (or turn on
  "show undiscovered"). Search, filter by area, myths, or what you've spotted.
- **Review**: spaced questions, with a "how well they're sticking" chart.
- **Journal**: every field note, filters, search, "On this day" (a week, a month, three and six
  months, a year ago), edit and delete.
- **You**: day count, streak, this week, discovered/spotted counts, prediction score, how
  concepts show up (spotted/in me/used), where you notice psychology (radar by area), most
  spotted, how deliberate uses went (worked/mixed/backfired), a 12-week heatmap, and settings.
- **Widget**: today's concept on the home screen with a "Log it" button.
- Long-press the app icon: *Log a sighting*, *Review*.

## Google account (like the Meditation Timer)

You → Settings → **Google account → Connect**. Your journal, predictions, review schedule and
settings are backed up to the hidden app-data folder in your Google Drive (`drive.appdata`,
which can't see the rest of your Drive), and restored automatically on a new phone or after a
reinstall. Every change uploads a few seconds later, after first merging in whatever is
already in Drive, so two phones on one account never wipe each other's notes.

**Switching accounts:** **Unlink** forgets the account on this phone *and* releases Google's
permission (the Drive backup stays), so the next **Connect** shows Google's account chooser.
Without that, Google silently hands back the account it last granted and you could never
switch. Connecting a different account brings your journal with you, merges in anything already
saved there, and says so; the old account's backup stays as it was. Anything still syncing when
you unlink stops without uploading or relinking. Because Mindfield shares its Google Cloud
project with the Meditation Timer, Google may treat the two as one app, so unlinking one can ask
the other to sign in again; nothing is lost either way.

**One-time step** (2 minutes), because Google identifies apps by package name and signing key:
in the same Google Cloud project you set up for the Meditation Timer, go to
**APIs & Services → Credentials → Create credentials → OAuth client ID → Android** and enter:

- Package name: `com.rajan.mindfield`
- SHA-1: `06:AD:C4:19:CC:57:0D:B0:55:46:A3:F6:D1:62:E1:F0:92:42:44:2F`

That's the same signing key as the Meditation Timer, so the Drive API and consent screen (with
you as a test user) are already in place. If you connect before doing this, the app shows these
exact values with copy buttons.

**Recommended:** in **Google Auth Platform → Audience**, press **Publish app** to move the consent
screen from "Testing" to "In production". In Testing, Google expires the sign-in after 7 days and
backups pause until you sign in again (the app warns you with a banner and a notification when
that happens). `drive.appdata` is a non-sensitive scope, so publishing needs no Google review.

Without Google: You → Settings → **Backup file** saves or merges a JSON file you keep yourself.
Restores and syncs always merge, never delete: two phones that sync at different times end up
with the same journal (merging is tested to be order-independent).

## Reminders and Google Play

- **On time where Android allows it without asking:** exact alarms on Android 12 and older.
  On newer phones the reminders are inexact and may arrive a few minutes late.
- **Why not exact everywhere:** Google Play allows the exact-alarm permission only for
  alarm-clock and calendar apps.
- **Battery saving:** if two mornings pass without a notification, a banner opens the
  phone's battery-optimisation list, where Mindfield can be set to "Don't optimise". Play
  allows the one-tap exemption prompt only for apps such as navigation and calls, so the app
  doesn't request it.
- **Android version:** the app targets Android 16 (API 36), which Play has required for new
  apps and updates since 31 August 2026.

## Building and testing

CI (`.github/workflows/mindfield.yml`) runs every test, builds a release APK signed with the
committed test key (`app/mindfield-debug.keystore`, public debug password, which keeps the
signature stable so updates install over each other and Google sign-in keeps working), checks
the certificate, publishes the APK to the link above and commits screenshots.

- `core/` is pure Kotlin (library parsing, daily scheduling, spaced review, stats, merge,
  backup format, account linking) with content checks on all 180 concepts and randomised
  stress tests: years of irregular use never repeat a concept early; merges are commutative,
  idempotent and associative over hundreds of random states; backups round-trip awkward text;
  alarms keep the wall-clock time across daylight-saving changes; three phones and two Google
  accounts through 75,000 random steps of writing, deleting, connecting, unlinking and
  switching never lose a note or upload to an unlinked account; and upgrading the library never
  changes a day someone has already had.
- `android/` tests run the real alarms, notifications (one-tap logging, the reply box,
  after-midnight taps), widget, storage through simulated process death and a corrupt file, a
  year of random use, and every screen at normal size and at 150% font on a small phone, with
  screenshots.

Fonts: Fraunces and Inter (SIL Open Font License).
