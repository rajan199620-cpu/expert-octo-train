package com.earmark.core.text

import com.earmark.core.TestBooks
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ScriptDetectorTest {
    @Test
    fun `detects dominant scripts`() {
        assertEquals("hi", ScriptDetector.guessLanguage("भारतीय न्याय संहिता की धारा 103 में हत्या के लिए दंड का प्रावधान है।"))
        assertEquals("ta", ScriptDetector.guessLanguage("தமிழ் ஒரு அழகான மொழி."))
        assertEquals("ja", ScriptDetector.guessLanguage("これは日本語の文章です。漢字もあります。"))
        assertEquals("zh", ScriptDetector.guessLanguage("这是一个中文句子。"))
        assertEquals("ru", ScriptDetector.guessLanguage("Это русский текст."))
        assertNull(ScriptDetector.guessLanguage("Plain English text with a few words."))
        assertNull(ScriptDetector.guessLanguage("English with one हिंदी word in it, mostly English words here."))
        assertNull(ScriptDetector.guessLanguage(""))
        assertNull(ScriptDetector.guessLanguage(TestBooks.novel()))
    }

    @Test
    fun `streaming parse gives the same book as the byte-array parse`() {
        val bytes = com.earmark.core.parse.ZipFixtures.epub3()
        val a = com.earmark.core.parse.DocumentParser.parse(bytes, com.earmark.core.model.SourceFormat.EPUB, "x.epub")
        val b = com.earmark.core.parse.DocumentParser.parse({ bytes.inputStream() }, com.earmark.core.model.SourceFormat.EPUB, "dir/x.epub", a.id)
        assertEquals(a, b)
        assertEquals("My Book", com.earmark.core.parse.DocumentParser.titleFromFileName("/storage/My_Book.pdf"))
    }
}
