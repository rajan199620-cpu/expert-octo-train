package com.earmark.core

import com.earmark.core.model.Book
import com.earmark.core.model.SourceFormat
import com.earmark.core.parse.BookAssembler
import com.earmark.core.parse.RawBlock
import com.earmark.core.parse.RawDocument
import com.earmark.core.parse.RawSection

object TestBooks {
    /** chapters -> paragraphs; chapter title is the map key. */
    fun book(vararg chapters: Pair<String, List<String>>, id: String = "book-1", title: String = "Test Book"): Book =
        BookAssembler.assemble(
            id, title, SourceFormat.TEXT,
            RawDocument(title, "A. Author", chapters.map { (t, paras) -> RawSection(t, paras.map { RawBlock(it) }) }),
        )

    /** A small novel-like book: 3 chapters, 3 paragraphs each, 3 sentences per paragraph. */
    fun novel(): Book = book(
        "The Arrival" to listOf(
            "Mira reached the harbour at dawn. The fog hid the boats. She counted the bells.",
            "An old sailor waved at her. His name was Tobin. He sold maps to travellers.",
            "Mira bought a map of the northern islands. It cost two silver coins. Tobin smiled.",
        ),
        "The Storm" to listOf(
            "By noon the sky turned black. Thunder rolled over the water. The boats rocked hard.",
            "Tobin warned her about the lighthouse. Nobody had lit it for years. Ships were lost there.",
            "Mira climbed the lighthouse stairs. The lamp was cracked. She lit it anyway.",
        ),
        "The Secret" to listOf(
            "The next morning a ship arrived safely. Its captain was Mira's missing brother. He had been lost for ten years.",
            "Tobin revealed that he had kept the lamp broken on purpose. He wanted the wreckers to profit. Mira was furious.",
            "She reported Tobin to the harbour guard. The guard arrested him. The lighthouse burned every night after that.",
        ),
    )
}
