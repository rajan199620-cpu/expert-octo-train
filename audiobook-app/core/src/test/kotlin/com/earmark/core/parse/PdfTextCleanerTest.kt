package com.earmark.core.parse

import com.earmark.core.model.SourceFormat
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PdfTextCleanerTest {
    private fun pages(vararg texts: String) = texts.mapIndexed { i, t -> PdfTextCleaner.Page(i + 1, t) }
    private fun sentences(raw: RawDocument) = BookAssembler.assemble("x", "x", SourceFormat.PDF, raw).sentences.map { it.text }

    @Test
    fun `ligatures, nbsp and furniture variants`() {
        val p = (1..5).map { n ->
            "Journal of Things, Vol. 3\nThe ﬁnal ﬂow of ideas was clear and it continued for a while longer here.\nPage $n of 5"
        }
        val out = sentences(PdfTextCleaner.clean(pages(*p.toTypedArray())))
        assertTrue(out.all { !it.contains("Journal of Things") && !it.contains("Page ") }, out.toString())
        assertTrue(out.first().startsWith("The final flow of ideas was clear"), out.toString())
    }

    @Test
    fun `roman page numbers in front matter and dashed page numbers are removed`() {
        val out = sentences(PdfTextCleaner.clean(pages("Preface text starts here and is long enough.\nxii", "More preface text goes here now.\n- 14 -")))
        assertEquals(listOf("Preface text starts here and is long enough.", "More preface text goes here now."), out)
    }

    @Test
    fun `a lone I at the top of a page is kept when it is not page furniture`() {
        // "I" as first line is a roman numeral candidate; only drop it when digit-like at edges.
        val out = sentences(PdfTextCleaner.clean(pages("I\nwent home early that day because it rained.")))
        assertTrue(out.joinToString(" ").contains("went home"), out.toString())
    }

    @Test
    fun `all caps short line becomes a heading block`() {
        val raw = PdfTextCleaner.clean(pages("Intro sentence that is fairly long and ends here.\nTHE STORM\nThe sky turned black over the harbour that day."))
        val blocks = raw.sections.flatMap { it.blocks }
        assertTrue(blocks.any { it.isHeading && it.text == "THE STORM" }, blocks.toString())
    }

    @Test
    fun `empty input`() {
        val raw = PdfTextCleaner.clean(emptyList())
        assertTrue(raw.sections.isEmpty())
        assertFalse(raw.warnings.isEmpty())
    }
}
