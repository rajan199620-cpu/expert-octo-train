package com.earmark.app

import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.earmark.app.data.Library
import com.earmark.app.data.ReaderTheme
import com.earmark.app.playback.AssistantUi
import com.earmark.app.ui.AssistantOverlay
import com.earmark.app.ui.EarmarkTheme
import com.earmark.app.ui.LibraryScreen
import com.earmark.app.ui.NowPlayingSheet
import com.earmark.app.ui.ReaderText
import com.earmark.app.ui.PlayerActions
import com.earmark.app.ui.ReaderPalette
import com.earmark.app.ui.SerifFamily
import com.earmark.app.ui.SettingsScreen
import com.earmark.app.ui.paragraphRanges
import com.earmark.app.ui.readerPalette
import com.earmark.core.annotations.HighlightColor
import com.earmark.core.assistant.AssistantReply
import com.earmark.core.model.Book
import com.earmark.core.model.SourceFormat
import com.earmark.core.model.summary
import com.earmark.core.parse.BookAssembler
import com.earmark.core.parse.RawBlock
import com.earmark.core.parse.RawDocument
import com.earmark.core.parse.RawSection
import com.earmark.core.storage.JsonCodec
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Renders the real Compose UI on the JVM so the design can be reviewed without a phone.
 * CI commits the PNGs to audiobook-app/docs/screenshots.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
class ScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private val app get() = RuntimeEnvironment.getApplication() as EarmarkApp

    private fun render(name: String, content: @Composable () -> Unit) {
        compose.mainClock.autoAdvance = false // equalizer and pulse animations never go idle
        compose.setContent { EarmarkTheme { content() } }
        compose.mainClock.advanceTimeBy(700)
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/$name.png")
    }

    private fun book(title: String = "The Lighthouse Keeper", author: String? = "Asha Verma"): Book {
        val raw = RawDocument(
            title, author,
            listOf(
                RawSection("The Storm", listOf(
                    RawBlock("Chapter 2 · The Storm", isHeading = true),
                    RawBlock("By noon the sky over the harbour had turned the colour of slate. Thunder rolled across the water, and the fishing boats strained against their ropes like dogs that had caught a scent."),
                    RawBlock("Tobin warned her about the lighthouse. Nobody had lit it in eleven years, he said, not since the night the keeper walked into the sea. Ships were lost on those rocks every winter."),
                    RawBlock("Mira climbed the spiral stairs anyway. The lamp was cracked and furred with salt. She struck a match, cupped it against the wind, and held it to the wick."),
                    RawBlock("For a moment nothing happened. Then the glass caught the flame and threw it, enormous and gold, across the black water."),
                )),
            ),
        )
        return BookAssembler.assemble("demo", title, SourceFormat.EPUB, raw)
    }

    /** One page-long paragraph, as PDFs often produce: the case where the highlight used to walk off screen. */
    private fun longParagraphBook(): Book {
        val sentences = (1..40).joinToString(" ") { n ->
            "Sentence $n of the long paragraph explains, in careful and deliberate detail, why the harbour master kept the lamp dark."
        }
        val raw = RawDocument("Notes on the Harbour", "R. Singh", listOf(RawSection("Evidence", listOf(RawBlock("Evidence", isHeading = true), RawBlock(sentences)))))
        return BookAssembler.assemble("long", "long", SourceFormat.PDF, raw)
    }

    private fun wordOf(book: Book, sentence: Int, word: String): IntRange {
        val t = book.sentences[sentence].text
        val i = t.indexOf(word)
        return i until i + word.length
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun ReaderPreview(book: Book, palette: ReaderPalette, position: Int, assistant: AssistantUi, word: IntRange? = null) {
        val charsBefore = LongArray(book.sentences.size + 1).also { a -> for (i in book.sentences.indices) a[i + 1] = a[i] + book.sentences[i].text.length }
        val highlights = mapOf(5 to HighlightColor.YELLOW, 6 to HighlightColor.YELLOW)
        val paragraphs = paragraphRanges(book)
        val itemOfSentence = IntArray(book.sentences.size).also { arr -> paragraphs.forEachIndexed { i, r -> for (k in r) arr[k] = i } }
        Scaffold(
            containerColor = palette.background,
            topBar = {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = palette.background, titleContentColor = palette.text),
                    title = {
                        Column {
                            Text(book.title, style = MaterialTheme.typography.titleMedium, fontFamily = SerifFamily)
                            Text("The Storm", style = MaterialTheme.typography.labelMedium, color = palette.readText)
                        }
                    },
                )
            },
            bottomBar = {
                NowPlayingSheet(
                    book = book, position = position, playing = true, speed = 1.25f, charsBefore = charsBefore,
                    sleepDeadline = null, sleepAtChapterEnd = true, listening = assistant is AssistantUi.Listening,
                    bookmarkedHere = false, actions = object : PlayerActions {}, onFollow = {}, onMic = {}, onAsk = {}, onBookmark = {},
                )
            },
        ) { padding ->
            Box(Modifier.padding(padding).fillMaxSize()) {
                ReaderText(
                    book = book, paragraphs = paragraphs, itemOfSentence = itemOfSentence, position = position, word = word,
                    playing = true, highlights = highlights, bookmarked = setOf(3), noted = setOf(9), palette = palette,
                    fontScale = 1.0f, follow = true, onFollowChange = {}, onTap = {}, onLongPress = {},
                )
                if (assistant !is AssistantUi.Idle) {
                    Box(Modifier.align(Alignment.BottomCenter)) { AssistantOverlay(assistant, book, {}, {}, {}, {}) }
                }
            }
        }
    }

    @Test
    fun readerPaper() = render("reader-paper") {
        val b = book()
        ReaderPreview(b, readerPalette(ReaderTheme.PAPER, false), position = 7, assistant = AssistantUi.Idle, word = wordOf(b, 7, "cracked"))
    }

    @Test
    fun readerFollowsInsideLongParagraph() = render("reader-follow-long-paragraph") {
        val b = longParagraphBook()
        ReaderPreview(b, readerPalette(ReaderTheme.PAPER, false), position = 34, assistant = AssistantUi.Idle, word = wordOf(b, 34, "deliberate"))
    }

    @Test
    fun readerSepiaWithAnswer() = render("reader-sepia-answer") {
        val reply = AssistantReply(
            "Tobin is the old sailor who sells maps at the harbour. So far he has warned Mira away from the lighthouse, saying ships are lost on its rocks.",
            resume = true, sources = listOf(4, 5),
        )
        ReaderPreview(book(), readerPalette(ReaderTheme.SEPIA, false), position = 8, assistant = AssistantUi.Replied("Who is Tobin?", reply, isAnswer = true))
    }

    @Test
    @Config(qualifiers = "+night")
    fun readerNightListening() = render("reader-night-listening") {
        ReaderPreview(book(), readerPalette(ReaderTheme.NIGHT, true), position = 10, assistant = AssistantUi.Listening("bookmark this as the turning"))
    }

    @Test
    fun libraryEmpty() {
        File(app.filesDir, "library.json").delete()
        val library = Library(app)
        render("library-empty") { LibraryScreen(library, MutableStateFlow<Uri?>(null), { _, _ -> }, {}) }
    }

    @Test
    fun library() {
        val books = listOf(
            book("The Lighthouse Keeper", "Asha Verma").summary(position = 41, lastOpenedMillis = 5),
            book("Bharatiya Nyaya Sanhita, 2023", "Government of India").summary(position = 3, lastOpenedMillis = 4),
            book("Thinking in Systems", "Donella Meadows").summary(position = 0, lastOpenedMillis = 3),
            book("Notes on Criminal Procedure", "R. Singh").summary(position = 12, lastOpenedMillis = 2),
            book("Meditations", "Marcus Aurelius").summary(position = 30, lastOpenedMillis = 1),
        ).mapIndexed { i, s -> s.copy(id = "b$i", sentenceCount = 60) }
        File(app.filesDir, "library.json").writeText(JsonCodec.encodeLibrary(books))
        val library = Library(app)
        render("library") { LibraryScreen(library, MutableStateFlow<Uri?>(null), { _, _ -> }, {}) }
    }

    @Test
    @Config(qualifiers = "+night")
    fun libraryNight() {
        val books = listOf(
            book("The Lighthouse Keeper", "Asha Verma").summary(position = 41, lastOpenedMillis = 5),
            book("Sapiens", "Yuval Noah Harari").summary(position = 3, lastOpenedMillis = 4),
            book("The Constitution of India", null).summary(position = 0, lastOpenedMillis = 3),
        ).mapIndexed { i, s -> s.copy(id = "n$i", sentenceCount = 60) }
        File(app.filesDir, "library.json").writeText(JsonCodec.encodeLibrary(books))
        val library = Library(app)
        render("library-night") { LibraryScreen(library, MutableStateFlow<Uri?>(null), { _, _ -> }, {}) }
    }

    @Test
    fun settings() = render("settings") { SettingsScreen(app.settings, app.hub, onBack = {}) }
}
