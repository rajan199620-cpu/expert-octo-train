package com.earmark.app.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.earmark.core.annotations.Annotation
import com.earmark.core.annotations.AnnotationStore
import com.earmark.core.model.Book
import com.earmark.core.model.BookSummary
import com.earmark.core.model.PARSER_VERSION
import com.earmark.core.model.SourceFormat
import com.earmark.core.model.summary
import com.earmark.core.parse.DocumentParseException
import com.earmark.core.parse.DocumentParser
import com.earmark.core.storage.JsonCodec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.security.DigestInputStream
import java.security.MessageDigest

sealed interface ImportResult {
    data class Imported(val book: BookSummary, val warnings: List<String>, val alreadyInLibrary: Boolean) : ImportResult
    data class Failed(val message: String) : ImportResult
}

/**
 * The on-device library: parsed books as JSON in filesDir/books, a summary index with
 * reading positions, and per-book annotation files. Imported source files are not kept.
 */
class Library(private val context: Context) {
    private val booksDir = File(context.filesDir, "books").apply { mkdirs() }
    private val notesDir = File(context.filesDir, "annotations").apply { mkdirs() }
    private val indexFile = File(context.filesDir, "library.json")
    private val lock = Any()
    private val state = MutableStateFlow(loadIndex())
    val books: StateFlow<List<BookSummary>> = state.asStateFlow()

    suspend fun importDocument(uri: Uri, onProgress: (String) -> Unit = {}): ImportResult = withContext(Dispatchers.IO) {
        val tmp = File(context.cacheDir, "import-${System.nanoTime()}")
        try {
            val name = displayName(uri) ?: uri.lastPathSegment ?: "Untitled"
            val mime = context.contentResolver.getType(uri)
            onProgress("Copying $name…")
            // Copy once while hashing: the hash is the book id, so re-importing the same file is detected.
            val digest = MessageDigest.getInstance("SHA-256")
            val input = context.contentResolver.openInputStream(uri) ?: return@withContext ImportResult.Failed("Couldn't open that file.")
            DigestInputStream(input, digest).use { src -> tmp.outputStream().use { src.copyTo(it) } }
            if (tmp.length() == 0L) return@withContext ImportResult.Failed("That file is empty.")
            val id = digest.digest().take(12).joinToString("") { "%02x".format(it) }

            synchronized(lock) { state.value.firstOrNull { it.id == id } }?.let { existing ->
                if (loadBook(id)?.parserVersion == PARSER_VERSION) return@withContext ImportResult.Imported(existing, emptyList(), alreadyInLibrary = true)
            }

            val head = tmp.inputStream().use { s -> ByteArray(512).let { b -> val n = s.read(b); if (n <= 0) ByteArray(0) else b.copyOf(n) } }
            val format = DocumentParser.detectFormat(name, mime, head)
                ?: return@withContext ImportResult.Failed(unsupportedMessage(name))
            onProgress("Reading ${format.name.lowercase()}…")
            val book = if (format == SourceFormat.PDF) {
                PdfExtractor.extract(tmp, id, name) { page, total -> onProgress("Extracting text: page $page of $total") }
            } else {
                DocumentParser.parse({ tmp.inputStream() }, format, name, id)
            }
            if (book.isEmpty) {
                return@withContext ImportResult.Failed(book.warnings.firstOrNull() ?: "No readable text was found in this document.")
            }
            onProgress("Saving…")
            saveBook(book)
            // Re-import with a newer parser: move existing bookmarks to their text.
            annotationStore(book).let { store -> if (store.all().isNotEmpty()) { store.reanchor(book); saveAnnotations(book.id, store.all()) } }
            val summary = book.summary(position = positionOf(id), lastOpenedMillis = System.currentTimeMillis())
            synchronized(lock) {
                state.value = listOf(summary) + state.value.filterNot { it.id == id }
                saveIndex()
            }
            ImportResult.Imported(summary, book.warnings, alreadyInLibrary = false)
        } catch (e: DocumentParseException) {
            ImportResult.Failed(e.message ?: "This document couldn't be read.")
        } catch (e: OutOfMemoryError) {
            ImportResult.Failed("This document is too large for this phone's memory.")
        } catch (e: SecurityException) {
            ImportResult.Failed("Earmark isn't allowed to read that file.")
        } catch (e: Exception) {
            ImportResult.Failed("Import failed: ${e.message ?: e.javaClass.simpleName}")
        } finally {
            tmp.delete()
        }
    }

    fun loadBook(id: String): Book? {
        val f = File(booksDir, "$id.json")
        if (!f.exists()) return null
        return (JsonCodec.decodeBook(f.readText()) as? JsonCodec.Decoded.Ok)?.value
    }

    fun delete(id: String) {
        File(booksDir, "$id.json").delete()
        File(notesDir, "$id.json").delete()
        synchronized(lock) {
            state.value = state.value.filterNot { it.id == id }
            saveIndex()
        }
    }

    fun positionOf(id: String): Int = state.value.firstOrNull { it.id == id }?.position ?: 0

    fun savePosition(id: String, position: Int) {
        synchronized(lock) {
            val now = System.currentTimeMillis()
            state.value = state.value.map { if (it.id == id) it.copy(position = position, lastOpenedMillis = now) else it }
                .sortedByDescending { it.lastOpenedMillis }
            saveIndex()
        }
    }

    /** Annotation store for [book], persisted on every change. */
    fun annotationStore(book: Book): AnnotationStore {
        val f = File(notesDir, "${book.id}.json")
        val initial = if (f.exists()) {
            when (val d = JsonCodec.decodeAnnotations(f.readText())) {
                is JsonCodec.Decoded.Ok -> d.value
                is JsonCodec.Decoded.Failed -> {
                    // Never silently lose someone's highlights: keep the unreadable file aside.
                    f.copyTo(File(notesDir, "${book.id}.corrupt-${System.currentTimeMillis()}.json"), overwrite = true)
                    emptyList()
                }
            }
        } else emptyList()
        return AnnotationStore(initial).apply { onChange = { saveAnnotations(book.id, all()) } }
    }

    private fun saveAnnotations(id: String, items: List<Annotation>) = atomicWrite(File(notesDir, "$id.json"), JsonCodec.encodeAnnotations(items))

    private fun saveBook(book: Book) = atomicWrite(File(booksDir, "${book.id}.json"), JsonCodec.encodeBook(book))

    private fun loadIndex(): List<BookSummary> =
        if (indexFile.exists()) (JsonCodec.decodeLibrary(indexFile.readText()) as? JsonCodec.Decoded.Ok)?.value.orEmpty() else emptyList()

    private fun saveIndex() = atomicWrite(indexFile, JsonCodec.encodeLibrary(state.value))

    /** Write to a temp file and rename, so a crash mid-write can't corrupt the library. */
    private fun atomicWrite(target: File, text: String) {
        val tmp = File(target.parentFile, "${target.name}.tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(target)) {
            target.delete()
            tmp.renameTo(target)
        }
    }

    private fun displayName(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()

    private fun unsupportedMessage(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "mobi", "azw", "azw3", "kfx" -> "Kindle files ($ext) are DRM-protected or proprietary. Export the book as EPUB (for example with Calibre) and import that."
            "doc" -> "Old Word .doc files aren't supported. Save it as .docx or PDF and try again."
            "djvu" -> "DjVu isn't supported. Convert it to PDF first."
            else -> "Earmark can read PDF, EPUB, DOCX, HTML, Markdown and plain text. \"$name\" isn't one of those."
        }
    }
}
