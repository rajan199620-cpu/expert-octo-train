package com.earmark.core.parse

import com.earmark.core.text.RomanNumerals

/**
 * Turns per-page text extracted from a PDF into paragraphs.
 *
 * PDFs have no paragraphs, only positioned lines, so a naive reader says "Page 12. Chapter
 * Three. The Storm." between every page and pronounces "impor- tant". This removes running
 * headers/footers and page numbers, rejoins hyphenated words, stitches paragraphs across page
 * breaks, splits chapters on the PDF outline (or "Chapter N" lines) and flags scanned PDFs.
 */
object PdfTextCleaner {
    data class Page(val number: Int, val text: String)
    data class OutlineEntry(val title: String, val page: Int)

    private const val SCANNED_CHARS_PER_PAGE = 25
    private val PAGE_NUMBER_LINE = Regex("""^\s*(?:page\s+)?(?:\d{1,4}|[ivxlcdm]{1,7})(?:\s*(?:of|/)\s*\d{1,4})?\s*$""", RegexOption.IGNORE_CASE)
    private val DASHED_PAGE_NUMBER = Regex("""^\s*[-–—]\s*\d{1,4}\s*[-–—]\s*$""")
    private val CHAPTER_LINE = Regex("""^(?:chapter|part|book)\s+(?:\d{1,3}|[ivxlcdm]{1,7}|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve)\b.{0,70}$""", RegexOption.IGNORE_CASE)
    private val TERMINAL = Regex("""[.!?:"”’)।]$""")
    private val LIGATURES = mapOf('ﬀ' to "ff", 'ﬁ' to "fi", 'ﬂ' to "fl", 'ﬃ' to "ffi", 'ﬄ' to "ffl")

    fun clean(
        pages: List<Page>,
        outline: List<OutlineEntry> = emptyList(),
        title: String? = null,
        author: String? = null,
    ): RawDocument {
        val warnings = ArrayList<String>()
        if (pages.isEmpty()) return RawDocument(title, author, emptyList(), listOf("The PDF has no pages."), hasRealPages = true)

        val pageLines = pages.map { p -> p.number to splitLines(p.text) }
        val lowTextPages = pageLines.count { (_, lines) -> lines.sumOf { it.length } < SCANNED_CHARS_PER_PAGE }
        if (lowTextPages * 2 >= pages.size) {
            warnings += if (lowTextPages == pages.size) {
                "This PDF has no text layer (it is probably a scan). Run it through OCR first."
            } else {
                "$lowTextPages of ${pages.size} pages have no text layer (scanned pages are skipped)."
            }
        }

        val furniture = detectRunningFurniture(pageLines.map { it.second })
        val vocabulary = buildVocabulary(pageLines.flatMap { it.second })

        // Lines with their page, minus headers/footers/page numbers.
        data class Line(val text: String, val page: Int)
        val lines = ArrayList<Line>()
        val pageBreaksBefore = HashSet<Int>() // indices in `lines` where a new page starts
        for ((number, raw) in pageLines) {
            val kept = raw.filterIndexed { i, line -> !isFurniture(line, i, raw.size, furniture) }
            if (kept.isNotEmpty()) pageBreaksBefore += lines.size
            kept.forEach { lines += Line(it, number) }
        }

        val typicalLength = median(lines.map { it.text.length }.filter { it > 20 }) ?: 60
        val outlineByPage = outline.filter { it.title.isNotBlank() }.groupBy { it.page }.mapValues { it.value.first().title.trim() }
        val useChapterLines = outlineByPage.isEmpty()

        val sections = ArrayList<RawSection>()
        var sectionTitle: String? = null
        var blocks = ArrayList<RawBlock>()
        val para = StringBuilder()
        var paraPage = 0
        var lastPageSeen = -1

        fun flushPara(asHeading: Boolean = false) {
            val t = para.toString().trim()
            if (t.isNotEmpty()) blocks += RawBlock(t, page = paraPage, isHeading = asHeading)
            para.setLength(0)
        }
        fun startSection(title: String?) {
            flushPara()
            if (blocks.isNotEmpty()) sections += RawSection(sectionTitle, blocks)
            blocks = ArrayList()
            sectionTitle = title
        }

        for ((i, line) in lines.withIndex()) {
            val text = line.text
            if (line.page != lastPageSeen) {
                lastPageSeen = line.page
                outlineByPage[line.page]?.let { startSection(it) }
            }
            if (para.isEmpty() && isChapterLine(text, typicalLength)) {
                if (useChapterLines) startSection(text) else flushPara()
                blocks += RawBlock(text, page = line.page, isHeading = true)
                continue
            }
            val headingLine = para.isEmpty() && lines.getOrNull(i + 1)?.let { looksLikeHeading(text, it.text) } == true
            if (headingLine) {
                paraPage = line.page
                para.append(text)
                flushPara(asHeading = true)
                continue
            }
            if (para.isEmpty()) {
                paraPage = line.page
                para.append(text)
            } else {
                appendLine(para, text, vocabulary)
            }
            val next = lines.getOrNull(i + 1)
            val endsParagraph = when {
                next == null -> true
                i + 1 in pageBreaksBefore ->
                    (TERMINAL.containsMatchIn(text) && startsNewParagraph(next.text) && text.length < typicalLength * 0.9) ||
                        isChapterLine(next.text, typicalLength) || looksLikeHeading(next.text, lines.getOrNull(i + 2)?.text ?: "A")
                else -> (TERMINAL.containsMatchIn(text) && text.length < typicalLength * 0.75) ||
                    isChapterLine(next.text, typicalLength) || looksLikeHeading(next.text, lines.getOrNull(i + 2)?.text ?: "A")
            }
            if (endsParagraph) flushPara()
        }
        startSection(null)

        return RawDocument(title?.takeIf { it.isNotBlank() }, author?.takeIf { it.isNotBlank() }, sections, warnings, hasRealPages = true)
    }

    private fun isChapterLine(text: String, typicalLength: Int) =
        text.length <= 80 && text.length < typicalLength * 0.8 && CHAPTER_LINE.matches(text)

    private fun splitLines(text: String): List<String> = text
        .replace("\r\n", "\n").replace('\r', '\n')
        .split('\n')
        .map { line -> line.map { LIGATURES[it] ?: it.toString() }.joinToString("").replace(' ', ' ').trim() }
        .filter { it.isNotEmpty() }

    /** Lines that repeat at the top or bottom of many pages, compared with digits masked. */
    private fun detectRunningFurniture(pages: List<List<String>>): Set<String> {
        if (pages.size < 3) return emptySet()
        val counts = HashMap<String, Int>()
        for (lines in pages) {
            // Prose (a long line ending a sentence) is never furniture, even if it repeats: a
            // refrain or a short page must not be deleted.
            val candidates = (lines.take(2) + lines.takeLast(2))
                .filterNot { it.length > 50 && it.last() in ".!?" }
                .map { mask(it) }.toSet()
            candidates.forEach { counts[it] = (counts[it] ?: 0) + 1 }
        }
        val threshold = maxOf(3, (pages.size * 0.3).toInt())
        return counts.filter { it.value >= threshold && it.key.length in 2..120 }.keys
    }

    private fun isFurniture(line: String, index: Int, count: Int, furniture: Set<String>): Boolean {
        val atEdge = index < 2 || index >= count - 2
        if (!atEdge) return false
        if (PAGE_NUMBER_LINE.matches(line) || DASHED_PAGE_NUMBER.matches(line)) {
            // A lone roman numeral could be a real line ("I"); only drop it at the edges.
            val token = line.trim()
            return token.any { it.isDigit() } || RomanNumerals.toInt(token) != null
        }
        return mask(line) in furniture
    }

    private fun mask(line: String) = line.lowercase().replace(Regex("""\d+"""), "#").replace(Regex("""\s+"""), " ").trim()

    private fun buildVocabulary(lines: List<String>): Set<String> {
        val words = HashSet<String>()
        val re = Regex("""[\p{L}]+(?:-[\p{L}]+)*""")
        for (l in lines) for (m in re.findAll(l)) words += m.value.lowercase()
        return words
    }

    /**
     * Joins [line] onto the paragraph. A trailing hyphen is a line-break hyphen unless the
     * hyphenated form appears elsewhere in the document ("self-evident").
     */
    private fun appendLine(para: StringBuilder, line: String, vocabulary: Set<String>) {
        val last = para.lastOrNull()
        if ((last == '-' || last == '­') && line.firstOrNull()?.isLowerCase() == true) {
            val prefix = para.takeLastWhile { it.isLetter() || it == '-' || it == '­' }.dropLast(1).toString()
            val suffix = line.takeWhile { it.isLetter() }
            val joined = (prefix + suffix).lowercase()
            val hyphenated = "$prefix-$suffix".lowercase()
            para.setLength(para.length - 1)
            if (hyphenated in vocabulary && joined !in vocabulary) para.append('-')
            para.append(line)
        } else {
            para.append(' ').append(line)
        }
    }

    private fun startsNewParagraph(next: String): Boolean {
        val c = next.firstOrNull() ?: return true
        return c.isUpperCase() || c.isDigit() || c in "\"“‘'(—" || (c.isLetter() && !c.isLowerCase())
    }

    private fun looksLikeHeading(line: String, next: String): Boolean {
        if (line.length > 60 || TERMINAL.containsMatchIn(line) || line.endsWith(",")) return false
        val letters = line.filter { it.isLetter() }
        if (letters.length < 3) return false
        val allCaps = letters.all { it.isUpperCase() }
        return allCaps && startsNewParagraph(next)
    }

    private fun median(xs: List<Int>): Int? = if (xs.isEmpty()) null else xs.sorted()[xs.size / 2]
}
