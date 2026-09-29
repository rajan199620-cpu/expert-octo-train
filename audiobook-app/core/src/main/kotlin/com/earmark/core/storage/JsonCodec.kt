package com.earmark.core.storage

import com.earmark.core.annotations.Annotation
import com.earmark.core.model.Book
import com.earmark.core.model.BookSummary
import com.earmark.core.text.LexiconEntry
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * All persistence is plain JSON files. Decoding is tolerant: unknown fields (from a newer app
 * version) are ignored and a corrupt file yields a failure instead of a crash on startup.
 */
object JsonCodec {
    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        coerceInputValues = true
    }

    sealed interface Decoded<out T> {
        data class Ok<T>(val value: T) : Decoded<T>
        data class Failed(val reason: String) : Decoded<Nothing>
    }

    fun encodeBook(book: Book): String = json.encodeToString(Book.serializer(), book)
    fun decodeBook(text: String): Decoded<Book> = decode(Book.serializer(), text)

    fun encodeAnnotations(items: List<Annotation>): String = json.encodeToString(ListSerializer(Annotation.serializer()), items)
    fun decodeAnnotations(text: String): Decoded<List<Annotation>> = decode(ListSerializer(Annotation.serializer()), text)

    fun encodeLibrary(items: List<BookSummary>): String = json.encodeToString(ListSerializer(BookSummary.serializer()), items)
    fun decodeLibrary(text: String): Decoded<List<BookSummary>> = decode(ListSerializer(BookSummary.serializer()), text)

    fun encodeLexicon(items: List<LexiconEntry>): String = json.encodeToString(ListSerializer(LexiconEntry.serializer()), items)
    fun decodeLexicon(text: String): Decoded<List<LexiconEntry>> = decode(ListSerializer(LexiconEntry.serializer()), text)

    private fun <T> decode(serializer: KSerializer<T>, text: String): Decoded<T> = try {
        if (text.isBlank()) Decoded.Failed("empty file") else Decoded.Ok(json.decodeFromString(serializer, text))
    } catch (e: SerializationException) {
        Decoded.Failed(e.message ?: "invalid JSON")
    } catch (e: IllegalArgumentException) {
        Decoded.Failed(e.message ?: "invalid data")
    }
}
