package com.earmark.core.parse

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import java.io.InputStream
import java.util.zip.ZipInputStream

/** Word .docx: paragraphs from word/document.xml, chapters split at Title/Heading 1 styles. */
object DocxParser {
    fun parse(input: InputStream): RawDocument {
        var documentXml: String? = null
        var coreXml: String? = null
        try {
            ZipInputStream(input).use { zip ->
                while (true) {
                    val e = zip.nextEntry ?: break
                    when (e.name) {
                        "word/document.xml" -> documentXml = zip.readBytes().toString(Charsets.UTF_8)
                        "docProps/core.xml" -> coreXml = zip.readBytes().toString(Charsets.UTF_8)
                    }
                    zip.closeEntry()
                }
            }
        } catch (e: java.util.zip.ZipException) {
            throw DocumentParseException("This file is not a valid .docx document.", e)
        }
        val body = documentXml ?: throw DocumentParseException("This file is not a valid .docx document (no word/document.xml).")
        val doc = Jsoup.parse(body, "", Parser.xmlParser())
        val core = coreXml?.let { Jsoup.parse(it, "", Parser.xmlParser()) }

        val sections = ArrayList<RawSection>()
        var title: String? = null
        var blocks = ArrayList<RawBlock>()
        for (p in doc.getElementsByTag("w:p")) {
            if (p.parents().any { it.normalName() == "w:p" }) continue // text boxes nested in paragraphs
            val text = paragraphText(p).trim()
            if (text.isEmpty()) continue
            val style = p.getElementsByTag("w:pStyle").firstOrNull()?.attr("w:val").orEmpty().lowercase()
            val isHeading = style.startsWith("heading") || style == "title" || style == "subtitle"
            val isChapterBreak = style == "title" || style == "heading1" || style == "heading 1"
            if (isChapterBreak && blocks.isNotEmpty()) {
                sections += RawSection(title, blocks)
                blocks = ArrayList()
            }
            if (isChapterBreak) title = text
            blocks += RawBlock(text, isHeading = isHeading)
        }
        if (blocks.isNotEmpty()) sections += RawSection(title, blocks)
        return RawDocument(
            title = core?.getElementsByTag("dc:title")?.firstOrNull()?.text(),
            author = core?.getElementsByTag("dc:creator")?.firstOrNull()?.text(),
            sections = sections,
        )
    }

    private fun paragraphText(p: Element): String {
        val sb = StringBuilder()
        for (e in p.getAllElements()) {
            when (e.normalName()) {
                "w:t" -> sb.append(e.wholeText())
                "w:tab", "w:br", "w:cr" -> sb.append(' ')
                "w:noBreakHyphen" -> sb.append('-')
                "w:footnoteReference", "w:endnoteReference" -> {}
            }
        }
        return sb.toString()
    }
}
