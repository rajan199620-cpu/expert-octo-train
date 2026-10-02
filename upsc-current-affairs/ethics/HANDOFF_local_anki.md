# Context handoff: add ethics examples to my existing Anki notes

## Me
I'm preparing for UPSC CSE. You are running on my own PC, next to Anki. AnkiConnect is at `http://127.0.0.1:8765` (API version 6). The earlier work was done in a cloud session that could not reach my Anki, which is why this is a new chat.

## How I want you to work
1. **Never change Anki without my explicit go-ahead for that specific change.** Read-only calls are fine anytime.
2. **No bloat.** Do not create any new notes for this task. Add at most one example per note. If nothing in my collection fits a theme, skip that example; don't make a standalone note.
3. **Keep it to one line.** Use the example text below as given. You may shorten it; don't expand it.
4. Tag every note you modify with `current_affairs_October_2026` (Anki tags can't contain spaces).
5. Order of work: show me a plan, wait for my approval, apply, then show me proof.

## The task
Put each example below into the most relevant **existing** note in my ethics (GS-IV) material: the note whose topic is that value or theme. Append it to that note's answer-side field.

Source: The Hindu (Delhi), 2 Oct 2026. Facts were checked.
1. **Moral courage / presence of mind under duress:** Capt. Smit Machchhar (2026): stabbed by his co-pilot mid-air, he still unlocked the cockpit so passengers could stop the attacker; all 174 landed safely.
2. **Institutional accountability vs individual blame:** Indore water deaths (2026): the inquiry called them preventable, the result of decades of delayed tenders, sewer lines over water pipes and missing records.
3. **Constitutional morality / conscientious objection:** Vande Mataram case (2026): an SC judge noted that standing respectfully without singing shouldn't be criminal, applying Bijoe Emmanuel (1986).
4. **Empathy, compassion, dignity in public service:** Bhadohi, UP (2026): a Musahar woman was refused hospital admission and gave birth by the roadside; an inquiry found the doctors seriously negligent.
5. **Procurement ethics / probity (lowest cost vs quality):** Delhi fake cancer-drug racket (2026): doctors blamed lowest-bidder (L1) buying for letting spurious medicines into hospitals.
6. **Ethics in science and technology:** Stanford 'xenocortication' (2026): researchers consulted bioethicists before transplanting human brain tissue into mice.
7. **VIP culture vs citizen-first service:** Assam (2026): an ambulance carrying a critical patient was held up for the CM's convoy; he then said ministers' movement must never inconvenience people.

Deliberately excluded: a conflict-of-interest example (an SC judge's alleged non-disclosure). It is an unresolved allegation and unsafe to cite. Do not add it.

## Steps
1. **Health check:** call `version` and expect 6. If the connection is refused, Anki or AnkiConnect isn't running; stop and tell me.
2. **Back up before any write:** run `exportPackage` (with `includeSched: true`) on the target deck(s) to an `.apkg` in my Documents folder, and confirm the file exists. Or ask me to do File → Create Backup.
3. **Discover (read-only):**
   - Call `deckNames` and identify my ethics deck(s). Ask me if it's ambiguous.
   - Run `findNotes` with `deck:"<name>"`, then `notesInfo` in batches.
   - Also search by keyword for each theme: courage, accountability, constitutional morality, conscientious, empathy, compassion, dignity, procurement, probity, science, technology, bioethics, VIP, public service.
4. **Propose a mapping table**, one row per example, with these columns:
   - note ID
   - deck
   - the note's front (shortened)
   - target field
   - the exact text to append
   
   Mark a row "no suitable note → skip" where nothing fits. Flag any note that already has a contemporary example and ask me whether to replace or skip. **Wait for my go-ahead.**
5. **Apply approved rows only:**
   - Before appending, check the field doesn't already contain the example. Never double-append.
   - Call `updateNoteFields` with the field's existing content plus `<br>Example (2026): …`. Append only; never overwrite, and keep the existing HTML exactly as it is.
   - Then call `addTags` with `current_affairs_October_2026`.
6. **Verify:** call `notesInfo` on every changed note and show me the target field before and after.

## Hard-won constraints: do not re-derive
1. `updateNoteFields` silently fails to save if that note is open in Anki's Browser or editor. Close the Browser before applying, then re-check with `notesInfo`.
2. Field names vary by note type (Front/Back, Text/Back Extra, or custom). Read `modelName` and the field names from `notesInfo`; never assume "Back".
3. Field content is HTML, so append with `<br>`, not a newline.
4. For a Cloze note, never put the example inside a cloze. Append it to Back Extra or another non-cloze field.
5. In search queries, quote terms and escape `* _ " \`.

## Status and what's unverified
- **Produced:** the 7 examples above.
  - In the GitHub repo `rajan199620-cpu/expert-octo-train` (branch `ccr-144390aa-ntyev1`), `upsc-current-affairs/ethics/anki_ethics_bank.tsv` holds them as **standalone** notes, and `upsc-current-affairs/push_to_anki.py` is a push script tested only against a mock AnkiConnect.
  - **Do not use either for this task.** Both create standalone notes.
- **Applied:** nothing from this project has been imported into my Anki. This is UNCONFIRMED: before starting, search `tag:current_affairs_October_2026` and expect 0 notes. If it returns notes, tell me before doing anything.
- **Unknown:** my deck names, note types and field names. Discover them in step 3.
- **Separate, not this task:** an 18-card prelims patch in the same repo. Don't touch it unless I ask.

## Where I left off
Nothing is in progress. First, run steps 1–3 and show me the mapping table.
