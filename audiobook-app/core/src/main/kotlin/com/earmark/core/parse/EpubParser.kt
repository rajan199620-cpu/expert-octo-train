package com.earmark.core.parse

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.parser.Parser
import java.io.InputStream
import java.net.URLDecoder
import java.nio.charset.Charset
import java.util.zip.ZipInputStream

/**
 * EPUB 2/3 reader. Streams the zip and keeps only text entries, so a 200 MB comic-heavy EPUB
 * doesn't need 200 MB of heap. Chapter titles come from the EPUB3 nav, the EPUB2 NCX, or the
 * first heading of each spine document, in that order.
 */
object EpubParser {
    private const val MAX_ENTRY_BYTES = 20L * 1024 * 1024
    private val TEXT_EXTENSIONS = setOf("xhtml", "html", "htm", "xml", "opf", "ncx")

    fun parse(input: InputStream): RawDocument {
        val entries = readTextEntries(input)
        if (entries.isEmpty()) throw DocumentParseException("This file is not a valid EPUB (no readable entries).")
        entries["META-INF/encryption.xml"]?.let { enc ->
            // Font obfuscation also lives in encryption.xml; it is only DRM if chapter files are encrypted.
            val encrypted = xml(enc).getElementsByTag("CipherReference").map { it.attr("URI").lowercase() }
            if (encrypted.any { it.endsWith(".xhtml") || it.endsWith(".html") || it.endsWith(".htm") }) {
                throw DocumentParseException("This EPUB is DRM-protected. Only DRM-free books can be read aloud.")
            }
        }
        val container = entries["META-INF/container.xml"] ?: throw DocumentParseException("EPUB is missing META-INF/container.xml.")
        val opfPath = xml(container).selectFirst("rootfile")?.attr("full-path")?.takeIf { it.isNotBlank() }
            ?: throw DocumentParseException("EPUB container does not point to a package file.")
        val opfBytes = entries[opfPath] ?: throw DocumentParseException("EPUB package file '$opfPath' is missing.")
        val opf = xml(opfBytes)
        val baseDir = opfPath.substringBeforeLast('/', "")

        val title = opf.getElementsByTag("dc:title").firstOrNull()?.text()
        val author = opf.getElementsByTag("dc:creator").joinToString(", ") { it.text() }.takeIf { it.isNotBlank() }

        data class Item(val id: String, val href: String, val mediaType: String, val properties: String)
        val manifest = opf.getElementsByTag("item").map {
            Item(it.attr("id"), resolve(baseDir, it.attr("href")), it.attr("media-type"), it.attr("properties"))
        }.associateBy { it.id }

        val spine = opf.getElementsByTag("itemref")
            .filter { it.attr("linear").lowercase() != "no" }
            .mapNotNull { manifest[it.attr("idref")] }
            .filter { it.mediaType.contains("html") || it.href.substringAfterLast('.').lowercase() in setOf("xhtml", "html", "htm") }
        if (spine.isEmpty()) throw DocumentParseException("EPUB has no readable chapters in its spine.")

        val tocTitles = HashMap<String, String>()
        manifest.values.firstOrNull { it.properties.split(' ').contains("nav") }?.let { nav ->
            entries[nav.href]?.let { bytes -> tocTitles += navTitles(html(bytes), nav.href.substringBeforeLast('/', "")) }
        }
        if (tocTitles.isEmpty()) {
            val ncxId = opf.getElementsByTag("spine").firstOrNull()?.attr("toc")
            val ncx = manifest[ncxId] ?: manifest.values.firstOrNull { it.mediaType == "application/x-dtbncx+xml" }
            ncx?.let { item -> entries[item.href]?.let { tocTitles += ncxTitles(xml(it), item.href.substringBeforeLast('/', "")) } }
        }

        val warnings = ArrayList<String>()
        val sections = spine.mapNotNull { item ->
            val bytes = entries[item.href]
            if (bytes == null) {
                warnings += "Missing chapter file ${item.href}"
                return@mapNotNull null
            }
            val doc = html(bytes)
            val blocks = HtmlTextExtractor.extract(doc.body())
            if (blocks.isEmpty()) null else RawSection(tocTitles[item.href], blocks)
        }
        return RawDocument(title, author, sections, warnings)
    }

    private fun readTextEntries(input: InputStream): Map<String, ByteArray> {
        val out = HashMap<String, ByteArray>()
        try {
            ZipInputStream(input).use { zip ->
                while (true) {
                    val e = zip.nextEntry ?: break
                    val name = e.name.trimStart('/')
                    if (!e.isDirectory && name.substringAfterLast('.').lowercase() in TEXT_EXTENSIONS) {
                        out[name] = readLimited(zip, name)
                    }
                    zip.closeEntry()
                }
            }
        } catch (e: java.util.zip.ZipException) {
            if (out.isEmpty()) throw DocumentParseException("This file is not a valid EPUB (corrupt zip).", e)
        } catch (e: java.io.EOFException) {
            if (out.isEmpty()) throw DocumentParseException("This EPUB is truncated.", e)
        }
        return out
    }

    private fun readLimited(zip: ZipInputStream, name: String): ByteArray {
        val buf = java.io.ByteArrayOutputStream()
        val chunk = ByteArray(16 * 1024)
        var total = 0L
        while (true) {
            val n = zip.read(chunk)
            if (n < 0) break
            total += n
            if (total > MAX_ENTRY_BYTES) throw DocumentParseException("EPUB entry '$name' is unreasonably large (zip bomb?).")
            buf.write(chunk, 0, n)
        }
        return buf.toByteArray()
    }

    private fun navTitles(nav: Document, dir: String): Map<String, String> {
        val tocNav = nav.select("nav").firstOrNull { it.attr("epub:type").contains("toc") } ?: nav.selectFirst("nav") ?: return emptyMap()
        val out = LinkedHashMap<String, String>()
        for (a in tocNav.select("a[href]")) {
            val href = resolve(dir, a.attr("href").substringBefore('#'))
            val text = a.text().trim()
            if (text.isNotEmpty() && href !in out) out[href] = text
        }
        return out
    }

    private fun ncxTitles(ncx: Document, dir: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (point in ncx.getElementsByTag("navPoint")) {
            val text = point.getElementsByTag("navLabel").firstOrNull()?.text()?.trim().orEmpty()
            val src = point.getElementsByTag("content").firstOrNull()?.attr("src").orEmpty()
            val href = resolve(dir, src.substringBefore('#'))
            if (text.isNotEmpty() && src.isNotEmpty() && href !in out) out[href] = text
        }
        return out
    }

    /** Resolves an href relative to [dir] inside the zip, handling "../" and %-encoding. */
    internal fun resolve(dir: String, href: String): String {
        val decoded = try { URLDecoder.decode(href.replace("+", "%2B"), "UTF-8") } catch (_: IllegalArgumentException) { href }
        val parts = ArrayList<String>()
        if (!decoded.startsWith("/") && dir.isNotEmpty()) parts += dir.split('/')
        for (seg in decoded.trimStart('/').split('/')) {
            when (seg) {
                "", "." -> {}
                ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.lastIndex)
                else -> parts += seg
            }
        }
        return parts.joinToString("/")
    }

    private fun xml(bytes: ByteArray): Document = Jsoup.parse(decode(bytes), "", Parser.xmlParser())
    private fun html(bytes: ByteArray): Document = Jsoup.parse(decode(bytes), "")

    private fun decode(bytes: ByteArray): String {
        val head = String(bytes, 0, minOf(bytes.size, 200), Charsets.ISO_8859_1)
        val declared = Regex("""encoding=["']([\w-]+)["']""").find(head)?.groupValues?.get(1)
        val charset = declared?.let { runCatching { Charset.forName(it) }.getOrNull() } ?: Charsets.UTF_8
        return String(bytes, charset).removePrefix("﻿")
    }
}
