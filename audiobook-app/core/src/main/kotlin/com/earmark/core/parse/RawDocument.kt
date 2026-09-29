package com.earmark.core.parse

/** Format-independent structure every parser produces before sentence splitting. */
data class RawBlock(
    val text: String,
    /** 1-based source page when the format has real pages (PDF). */
    val page: Int? = null,
    val isHeading: Boolean = false,
)

data class RawSection(val title: String?, val blocks: List<RawBlock>)

data class RawDocument(
    val title: String?,
    val author: String?,
    val sections: List<RawSection>,
    val warnings: List<String> = emptyList(),
    val hasRealPages: Boolean = false,
)

class DocumentParseException(message: String, cause: Throwable? = null) : Exception(message, cause)
