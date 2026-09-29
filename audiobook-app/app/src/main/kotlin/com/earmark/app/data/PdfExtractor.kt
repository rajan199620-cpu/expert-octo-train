package com.earmark.app.data

import com.earmark.core.model.Book
import com.earmark.core.model.SourceFormat
import com.earmark.core.parse.BookAssembler
import com.earmark.core.parse.DocumentParseException
import com.earmark.core.parse.DocumentParser
import com.earmark.core.parse.PdfTextCleaner
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.File
import java.io.IOException

/**
 * Page-by-page text extraction with pdfbox-android, then [PdfTextCleaner]. Mirrors the
 * extraction exercised by the core PdfIntegrationTest with desktop PDFBox 2.0.27.
 */
object PdfExtractor {
    fun extract(file: File, id: String, fileName: String, onPage: (Int, Int) -> Unit): Book {
        val doc = try {
            PDDocument.load(file, MemoryUsageSetting.setupTempFileOnly())
        } catch (e: InvalidPasswordException) {
            throw DocumentParseException("This PDF is password protected. Remove the password and import it again.", e)
        } catch (e: IOException) {
            throw DocumentParseException("This PDF is damaged or not really a PDF (${e.message}).", e)
        }
        doc.use {
            if (!doc.currentAccessPermission.canExtractContent()) {
                throw DocumentParseException("This PDF's permissions forbid text extraction.")
            }
            val total = doc.numberOfPages
            val stripper = PDFTextStripper()
            val pages = ArrayList<PdfTextCleaner.Page>(total)
            for (n in 1..total) {
                stripper.startPage = n
                stripper.endPage = n
                val text = try { stripper.getText(doc) } catch (e: IOException) { "" }
                pages += PdfTextCleaner.Page(n, text)
                if (n % 5 == 0 || n == total) onPage(n, total)
            }
            val info = doc.documentInformation
            val raw = PdfTextCleaner.clean(pages, outline(doc), info?.title, info?.author)
            return BookAssembler.assemble(id, DocumentParser.titleFromFileName(fileName), SourceFormat.PDF, raw)
        }
    }

    /** Top-level outline entries; if there is a single root (the book title), use its children. */
    private fun outline(doc: PDDocument): List<PdfTextCleaner.OutlineEntry> {
        val root = doc.documentCatalog.documentOutline ?: return emptyList()
        var items = siblings(root.firstChild)
        if (items.size == 1 && items[0].firstChild != null) items = siblings(items[0].firstChild)
        return items.mapNotNull { item ->
            val page = runCatching { item.findDestinationPage(doc) }.getOrNull() ?: return@mapNotNull null
            val index = doc.pages.indexOf(page)
            if (index < 0) null else PdfTextCleaner.OutlineEntry(item.title.orEmpty(), index + 1)
        }
    }

    private fun siblings(first: PDOutlineItem?): List<PDOutlineItem> {
        val out = ArrayList<PDOutlineItem>()
        var item = first
        while (item != null && out.size < 2000) {
            out += item
            item = item.nextSibling
        }
        return out
    }
}
