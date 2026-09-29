# Earmark test report

## What is tested, and how

All app logic lives in `core/` (plain Kotlin/JVM) so it runs under JUnit without a phone.
The Android layer (`app/`) is thin glue over platform APIs and is covered by the CI build and lint, not by unit tests.

| Area | Tests | How |
|---|---|---|
| Sentence splitting | 34 | Table of tricky cases (abbreviations, initials, legal citations, quotes, ellipses, Hindi danda, CJK), 3,000-input fuzz test that checks no text is lost or invented, clause-level splitting of 1,000-character legal sentences |
| Speech normalisation | 40 | § and §§, ₹/Rs./$/£ amounts, Indian digit grouping (1,00,000), lists like "sections 302,304", citation markers, footnote numbers, URLs, Roman numerals after "Chapter/Part/War", u/s and r/w, ALL-CAPS headings, ligatures; plus a fuzz test |
| Numbers, Roman numerals, pronunciation dictionary | 5 | Spoken numbers ("one and a half", "a couple", "twenty-five"), all Roman numerals 1-3999, longest-match lexicon without re-replacement |
| Document parsing | 13 | Generated EPUB 3 (nav, %20 file names, footnotes, non-linear notes, scripts), EPUB 2 (NCX, ../ paths), DRM vs font obfuscation, corrupt and truncated zips, DOCX (styles, runs, tabs), Project Gutenberg boilerplate, Markdown, HTML, format sniffing, UTF-8/Windows-1252/UTF-16 decoding, virtual pages |
| Real PDFs | 12 | PDFs generated with PDFBox and extracted exactly as the app does: running headers and page numbers, hyphenation vs real hyphenated words, paragraphs across pages, outline-based and heading-based chapters, scanned and partly scanned PDFs |
| Playback engine | 16 | Lookahead queueing, full book read-through, stale callbacks after seeks, pause/resume, mic "anchor" grace period, sleep timer at sentence boundaries, stop at end of chapter (including after seeking), speed clamping, navigation, time-based skipping, engine errors, 4,000 concurrent actions from 8 threads |
| Voice commands | 131 | 120 phrasings across all 30 commands, 11 questions that contain command words ("what does bookmark mean", "is the captain going to stop") and must stay questions, mumbles, 5,000-input fuzz test |
| Annotations & export | 16 | Duplicate bookmarks, labels, page/chapter bookmarks, highlight merging and undo, re-anchoring after the text shifts, repeated sentences, orphaned text, Markdown/CSV/Anki escaping, JSON round trips, corrupt and newer-version files |
| Search & Q&A | 16 | BM25 ranking, stemming, **spoiler safety (no sentence past your position ever appears in any prompt type)**, prompt structure, answer parsing, offline fallback, Claude request shape against a local fake server (model, effort, refusal fallbacks, 1-hour cached full-book block), 401/403/404/400/429/500/529 errors, refusals, cut-off and empty answers, missing key, network down |
| Cloud voices | 6 | Chunking at paragraph boundaries, character-timestamp → sentence timing, cost estimates, ElevenLabs and OpenAI request/response shapes and error classes against a fake server |
| Assistant (end to end) | 9 | Transcript → command → annotation/player/Q&A → spoken reply, including a full listen → interrupt → command → resume session |
| Robustness & performance | 8 | 1.5-million-character book, 2 MB paragraph with no punctuation, 5,000-deep nested HTML on a 256 KB thread stack, zip bomb, 800-chapter EPUB, 10,000-line PDF page, hostile transcripts, 200 KB paragraph normalisation |
| Script detection | 2 | Hindi, Tamil, Japanese, Chinese, Russian vs English; streaming vs in-memory parsing |
| **Stress & fuzz** | 10 | 16,000 concurrent annotation edits from 8 threads; 10,000-annotation export and re-anchoring on a 50,000-sentence book; Unicode torture (emoji ZWJ sequences, flags, skin tones, combining marks, Arabic, Devanagari conjuncts, unspaced Chinese); 800 random/bit-flipped/truncated EPUB and DOCX files; random bytes into every text format; 1,500 random PDF page sets; 3,000 corrupted JSON files; a 20,000-sentence listening marathon with random seeks, speed changes and pauses; spoiler safety at 150 random positions; 18,000 command parses |
| **UI screenshots** | 7 | Robolectric renders the real Compose screens (library, empty library, night library, reader in Paper/Sepia/Night, answer card, listening card, settings) on every push; PNGs in `docs/screenshots/` |

Run them: `./gradlew -Pearmark.coreOnly=true :core:test` (318 tests, all passing). Screenshots: `./gradlew :app:testDebugUnitTest`.

## Bugs the tests found (all fixed)

1. **"§ 302" was read as "sections 302."** The regex counted the space after § as a second §.
2. **"Set speed to one and a half" set 2.5×.** The "a" in "a half" was added to "one".
3. **"Go back a couple of sentences" went back 3.** Same article bug ("a" + "couple").
4. **A "Chapter 2" heading at the top of a page was glued onto the previous page's paragraph**, so the chapter never started and the heading was read mid-sentence.
5. **Repeated lines on short PDF pages were deleted as "running headers"**, which could empty a whole document (slides, poetry, refrains).
6. **"Bookmark the page" went to the AI as a question**, and speech recognisers' "book mark this" / "high light that" (two words) weren't understood.
7. **A deeply nested EPUB crashed the app** (stack overflow on Android-sized thread stacks). The HTML walk is now iterative.
8. **A 2 MB paragraph without full stops took 4.6 s to split** (quadratic). Now 0.35 s.
9. **Re-importing a big, heavily annotated book would have frozen the app:** re-anchoring 10,000 annotations on a 50,000-sentence book ran for over 10 minutes (every annotation regex-normalised every sentence). Now 0.18 s.
10. **A corrupted EPUB showed the raw error "malformed input off : 2"** (found by bit-flip fuzzing). Damaged files now fail with "This EPUB is damaged and can't be read."
11. **Long text without spaces (Chinese, Japanese, URLs) could be cut in the middle of an emoji or character**, producing garbage glyphs and TTS noise. Cuts are now grapheme-safe and prefer CJK/Arabic commas.

UI review from the rendered screenshots (fixed): cover titles broke mid-word and collided with the ribbon; covers were too large (2 per row, now 3); the current-sentence colour was indistinguishable from yellow highlights; the bookmark marker looked like a quotation mark (now an inline icon); the mic gradient rendered muddy olive; duplicate "Page 1" source chips; "1 chapters"; a flashlight icon standing in for "highlight"; Settings sat on "Loading voices…" forever when a phone has no extra voices.

## Measured performance (JVM, this container; expect a mid-range phone to be 3-5× slower)

| Operation | Time |
|---|---|
| Parse a 1.5M-character book (15,707 sentences) | ~0.25–0.4 s |
| Re-anchor 10,000 annotations (50k-sentence book) | ~0.18 s |
| Export 10,000 annotations (Markdown + CSV + Anki) | ~0.17 s |
| 18,000 voice-command parses | ~1.8 s (0.1 ms each) |
| Build the search index | ~0.2 s |
| 20 searches | ~50 ms |
| Split a 2 MB unpunctuated paragraph | ~0.35 s |
| Parse an 800-chapter EPUB | ~0.2 s |

## Known limitations (not bugs; decisions or things only a device can tell)

- **"Exactly like a human" is not achievable on-device.** The free voice is only as good as the phone's TTS engine. ElevenLabs is close to human for narration but costs money and needs a connection. The cache makes re-listening free.
- **Scanned PDFs have no text.** The app detects them and says so; OCR is not built in.
- **Two-column PDFs, tables, footnotes and equations** come out in whatever order the PDF stores them. Cleaning is heuristic.
- **You must press the mic (or use the earbud gesture).** There is no always-on wake word: that needs echo cancellation against the narrator's own voice and a wake-word engine, and it drains the battery.
- **One command per utterance.** "Bookmark this and go back" is sent to the AI as a question.
- **Word-level highlighting** is not implemented; the current *sentence* is highlighted.
- **API keys are stored in app-private storage**, not in a hardware keystore.
- **Not yet tested on a physical phone.** Screenshots are rendered by Robolectric, not a device. Platform behaviour (TTS engines, speech recognisers, audio focus, OEM battery killers such as those on Xiaomi, Oppo and Vivo phones) can only be verified on devices. CI proves the APK compiles and passes lint.
