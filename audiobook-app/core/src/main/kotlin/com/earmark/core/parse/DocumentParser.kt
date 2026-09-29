package com.earmark.core.parse

import com.earmark.core.model.Book
import com.earmark.core.model.SourceFormat
import com.earmark.core.text.SentenceSegmenter
import org.jsoup.Jsoup
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest

/**
 * Entry point for everything except PDF (whose text extraction needs a platform PDF library;
 * the app extracts pages and hands them to [PdfTextCleaner]).
 */
object DocumentParser {
    fun detectFormat(fileName: String?, mimeType: String?, head: ByteArray): SourceFormat? {
        val ext = fileName?.substringAfterLast('.', "")?.lowercase().orEmpty()
        val mime = mimeType?.lowercase().orEmpty()
        return when {
            head.startsWith("%PDF".toByteArray()) -> SourceFormat.PDF
            mime == "application/epub+zip" || ext == "epub" -> SourceFormat.EPUB
            ext == "docx" || mime.contains("wordprocessingml") -> SourceFormat.DOCX
            head.startsWith(byteArrayOf(0x50, 0x4B, 0x03, 0x04)) && String(head, Charsets.ISO_8859_1).contains("epub") -> SourceFormat.EPUB
            ext in setOf("md", "markdown") || mime == "text/markdown" -> SourceFormat.MARKDOWN
            ext in setOf("html", "htm", "xhtml") || mime.contains("html") -> SourceFormat.HTML
            ext in setOf("txt", "text") || mime.startsWith("text/") -> SourceFormat.TEXT
            ext == "pdf" || mime == "application/pdf" -> SourceFormat.PDF
            ext in setOf("doc", "mobi", "azw", "azw3", "kfx", "djvu") -> null
            else -> if (looksLikeText(head)) SourceFormat.TEXT else null
        }
    }

    fun parse(bytes: ByteArray, format: SourceFormat, fileName: String): Book =
        parse({ ByteArrayInputStream(bytes) }, format, fileName, idFor(bytes))

    /**
     * Streaming variant used by the app: zip formats are read entry by entry, so a large
     * EPUB full of images never has to fit in memory.
     */
    fun parse(open: () -> InputStream, format: SourceFormat, fileName: String, id: String): Book {
        val raw = when (format) {
            SourceFormat.EPUB -> open().use { EpubParser.parse(it) }
            SourceFormat.DOCX -> open().use { DocxParser.parse(it) }
            SourceFormat.HTML -> {
                val doc = Jsoup.parse(decodeText(readLimited(open)))
                RawDocument(doc.title().takeIf { it.isNotBlank() }, doc.selectFirst("meta[name=author]")?.attr("content"),
                    listOf(RawSection(null, HtmlTextExtractor.extract(doc.body()))))
            }
            SourceFormat.TEXT -> PlainTextParser.parse(decodeText(readLimited(open)), markdown = false)
            SourceFormat.MARKDOWN -> PlainTextParser.parse(decodeText(readLimited(open)), markdown = true)
            SourceFormat.PDF -> throw IllegalArgumentException("PDF text must be extracted by the platform; use PdfTextCleaner.")
        }
        return BookAssembler.assemble(id, titleFromFileName(fileName), format, raw, SentenceSegmenter())
    }

    fun titleFromFileName(fileName: String) =
        fileName.substringAfterLast('/').substringBeforeLast('.').replace('_', ' ').trim().ifEmpty { "Untitled" }

    private const val MAX_TEXT_BYTES = 64L * 1024 * 1024

    private fun readLimited(open: () -> InputStream): ByteArray = open().use { input ->
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            total += n
            if (total > MAX_TEXT_BYTES) throw DocumentParseException("This text file is larger than 64 MB.")
            out.write(buf, 0, n)
        }
        out.toByteArray()
    }

    fun idFor(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).take(12).joinToString("") { "%02x".format(it) }

    /** UTF-8 if valid, otherwise Windows-1252 (the usual culprit for old .txt books). */
    fun decodeText(bytes: ByteArray): String {
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) return String(bytes, Charsets.UTF_16LE).drop(1)
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) return String(bytes, Charsets.UTF_16BE).drop(1)
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return try {
            decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString().removePrefix("﻿")
        } catch (_: CharacterCodingException) {
            String(bytes, charset("windows-1252"))
        }
    }

    private fun looksLikeText(head: ByteArray): Boolean {
        if (head.isEmpty()) return false
        val controls = head.count { b -> val v = b.toInt() and 0xFF; v < 0x09 || (v in 0x0E..0x1F) }
        return controls.toDouble() / head.size < 0.01
    }

    private fun ByteArray.startsWith(prefix: ByteArray) = size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }
}
