# Earmark: talk to your audiobook

An Android app that reads a PDF, EPUB, Word document, web page, Markdown or text file aloud,
and lets you interact with the book while you listen:

- "**Bookmark this**", "bookmark this page", "bookmark the last sentence as Tobin's lie"
- "**Highlight that**", "highlight the last two sentences in green"
- "**Note:** check this against section 302", "**undo**"
- "**Go back 30 seconds**", "repeat that", "next chapter", "go to page 40", "faster", "sleep in 20 minutes"
- "**Who is Tobin?**", "what does *culpable* mean?", "explain that", "recap this chapter", "where am I?"
- **Double-tap your earbuds** to bookmark what you just heard, without speaking. A short tone confirms.

Tap any sentence to jump to it. Long-press a sentence to highlight, bookmark, annotate or have it explained.
Export highlights as Markdown, Anki flashcards or Readwise-style CSV.

## Voices

| Narrator | Sounds | Cost | Offline |
|---|---|---|---|
| On-device (Android TTS) | Depends on the phone; Google's neural voices are decent | Free | Yes |
| ElevenLabs | The most human-like option available | ~$0.05–0.10 per 1,000 characters (≈ $30–60 for a 300-page book) | No (audio is cached after the first listen) |
| OpenAI `gpt-4o-mini-tts` | Natural, steerable narration style | ≈ $15 per million characters | No (cached) |

Settings shows the estimated cost for the open book before you switch. Paid voices use **your own API key**.
Questions and recaps use Claude with **your own Anthropic API key**. Without one, the app falls back to
finding the most relevant passage offline.

## Layout

```
audiobook-app/
  core/   Plain Kotlin/JVM: parsing, sentence splitting, speech normalisation, playback state machine,
          voice-command parser, annotations, export, search and Q&A. No Android dependencies. Fully tested.
  app/    Android: Compose UI, TTS engines, foreground media service, speech recognition, PDF extraction.
  docs/TEST_REPORT.md  What was tested, what broke, what is still a known limitation.
```

## Build

```bash
cd audiobook-app
./gradlew :app:assembleDebug        # APK in app/build/outputs/apk/debug/
./gradlew -Pearmark.coreOnly=true :core:test   # core tests, no Android SDK needed
```

Requirements: JDK 17+, Android SDK 36 (Android Studio sets this up). CI (`.github/workflows/audiobook-app.yml`)
runs the core tests, builds the debug APK and uploads it as the `earmark-debug-apk` artifact.

Minimum Android version: 8.0 (API 26).

## Privacy

Books, positions, annotations and settings stay on the phone (app-private storage, excluded from backup).
Text leaves the device only when you choose a cloud voice (the text being narrated) or ask a question
(the passages needed to answer it, never past your position in spoiler-safe mode).
