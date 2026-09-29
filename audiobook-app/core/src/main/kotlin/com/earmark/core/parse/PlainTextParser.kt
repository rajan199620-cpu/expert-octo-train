package com.earmark.core.parse

/**
 * Plain text and Markdown. Handles hard-wrapped text (Project Gutenberg wraps at ~70 columns)
 * by joining lines inside a paragraph, and detects "CHAPTER IV" style headings.
 */
object PlainTextParser {
    private val CHAPTER_HEADING = Regex(
        """^(?:chapter|book|part|volume|canto|act|section)\s+(?:\d+|[ivxlcdm]+|one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve|[a-z]+teen|twenty[\w-]*|thirty[\w-]*)\b.{0,80}$""",
        RegexOption.IGNORE_CASE,
    )
    private val MD_HEADING = Regex("""^(#{1,6})\s+(.*?)\s*#*$""")
    private val MD_IMAGE = Regex("""!\[[^\]]*]\([^)]*\)""")
    private val MD_LINK = Regex("""\[([^\]]+)]\([^)]*\)""")
    private val MD_EMPHASIS = Regex("""(\*\*|__|\*|_|~~|`)(\S(?:.*?\S)?)\1""")
    private val MD_LIST = Regex("""^\s*(?:[-*+]|\d{1,3}[.)])\s+""")
    private val MD_QUOTE = Regex("""^\s*>\s?""")
    private val MD_RULE = Regex("""^\s*(?:-{3,}|\*{3,}|_{3,})\s*$""")
    private val GUTENBERG_START = Regex("""\*\*\*\s*START OF (?:THE|THIS) PROJECT GUTENBERG.*""", RegexOption.IGNORE_CASE)
    private val GUTENBERG_END = Regex("""\*\*\*\s*END OF (?:THE|THIS) PROJECT GUTENBERG.*""", RegexOption.IGNORE_CASE)

    fun parse(text: String, markdown: Boolean): RawDocument {
        var body = text.replace("\r\n", "\n").replace('\r', '\n').removePrefix("﻿")
        // Skip Project Gutenberg licence boilerplate: nobody wants 20 minutes of legalese read aloud.
        GUTENBERG_START.find(body)?.let { body = body.substring(it.range.last + 1) }
        GUTENBERG_END.find(body)?.let { body = body.substring(0, it.range.first) }

        val sections = ArrayList<RawSection>()
        var sectionTitle: String? = null
        var blocks = ArrayList<RawBlock>()
        var docTitle: String? = null
        var inFence = false

        fun startSection(title: String) {
            if (blocks.isNotEmpty()) sections += RawSection(sectionTitle, blocks)
            blocks = ArrayList()
            sectionTitle = title
        }

        for (para in splitParagraphs(body, markdown)) {
            if (markdown && para.startsWith("```")) { inFence = !inFence; continue }
            if (inFence) continue
            if (markdown) {
                val h = MD_HEADING.matchEntire(para)
                if (h != null) {
                    val level = h.groupValues[1].length
                    val title = cleanMarkdown(h.groupValues[2])
                    if (level == 1 && docTitle == null && sections.isEmpty() && blocks.isEmpty()) docTitle = title
                    if (level <= 2) startSection(title)
                    blocks += RawBlock(title, isHeading = true)
                    continue
                }
                if (MD_RULE.matches(para)) continue
            } else if (para.length <= 90 && CHAPTER_HEADING.matches(para)) {
                startSection(para)
                blocks += RawBlock(para, isHeading = true)
                continue
            }
            val clean = if (markdown) cleanMarkdown(para) else para
            if (clean.isNotBlank()) blocks += RawBlock(clean)
        }
        if (blocks.isNotEmpty()) sections += RawSection(sectionTitle, blocks)
        return RawDocument(title = docTitle, author = null, sections = sections)
    }

    private fun splitParagraphs(body: String, markdown: Boolean): List<String> {
        val out = ArrayList<String>()
        val current = ArrayList<String>()
        fun flush() {
            if (current.isNotEmpty()) out += current.joinToString(" ") { it.trim() }.trim()
            current.clear()
        }
        for (line in body.split('\n')) {
            val trimmed = line.trim()
            when {
                trimmed.isEmpty() -> flush()
                markdown && (trimmed.startsWith("#") || trimmed.startsWith("```") || MD_LIST.containsMatchIn(line)) -> {
                    flush(); current += trimmed
                    if (trimmed.startsWith("#") || trimmed.startsWith("```")) flush()
                }
                else -> current += trimmed
            }
        }
        flush()
        return out.filter { it.isNotEmpty() }
    }

    private fun cleanMarkdown(s: String): String {
        var t = s.replace(MD_IMAGE, "").replace(MD_LINK, "$1")
        repeat(2) { t = t.replace(MD_EMPHASIS, "$2") }
        t = t.replace(MD_QUOTE, "").replace(MD_LIST, "")
        return t.replace(Regex("""<[^>]+>"""), "").trim()
    }
}
