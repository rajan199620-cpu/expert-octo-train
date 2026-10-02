package com.ankiwatch.core

/**
 * Decides how the watch should show a card, from the note's type, field names and field
 * values as AnkiDroid's API reports them.
 *
 * Cloze notes (the Enhanced Cloze types and Anki's stock Cloze) are sent as their raw cloze
 * field plus the tested cloze number, and the watch lays them out itself. Rendering the
 * card template instead is what made the original app show the answers: the Enhanced Cloze
 * template keeps the raw `{{Content}}` field in a hidden span and builds the card in
 * JavaScript, which a watch cannot run.
 */
object NotePlanner {

    const val MODEL_TYPE_STANDARD = 0
    const val MODEL_TYPE_CLOZE = 1

    /** Field holding the cloze text, in order of preference. */
    private val CONTENT_FIELDS = listOf("content", "text")

    /** Fields worth showing under the answer, matched case-insensitively. */
    private val EXTRA_FIELDS = setOf(
        "note", "notes", "mnemonics", "mnemonic", "extra", "back extra", "remarks", "explanation"
    )

    private val ANY_CLOZE = Regex("""\{\{c\d+::""")

    sealed class Plan {
        data class Cloze(
            val contentField: String,
            val content: String,
            /** The cloze this card tests: card ord + 1. */
            val clozeNumber: Int,
            /** (field name, HTML) pairs for the answer side, non-blank only, in model order. */
            val extras: List<Pair<String, String>>
        ) : Plan()

        /** Show the template-rendered question and answer. */
        object Text : Plan()
    }

    fun plan(modelType: Int?, fieldNames: List<String>, fieldValues: List<String>, cardOrd: Int): Plan {
        if (cardOrd < 0) return Plan.Text
        val count = minOf(fieldNames.size, fieldValues.size)
        if (count == 0) return Plan.Text
        val names = fieldNames.take(count).map { it.trim() }
        val values = fieldValues.take(count)

        val isCloze = when (modelType) {
            MODEL_TYPE_CLOZE -> true
            MODEL_TYPE_STANDARD -> false
            else -> values.any { ANY_CLOZE.containsMatchIn(it) }
        }
        if (!isCloze) return Plan.Text

        val clozeNumber = cardOrd + 1
        val lower = names.map { it.lowercase() }
        var contentIndex = -1
        for (preferred in CONTENT_FIELDS) {
            val idx = lower.indexOf(preferred)
            if (idx >= 0 && values[idx].contains("{{c")) {
                contentIndex = idx
                break
            }
        }
        if (contentIndex < 0) contentIndex = values.indexOfFirst { it.contains("{{c$clozeNumber::") }
        if (contentIndex < 0) contentIndex = values.indexOfFirst { ANY_CLOZE.containsMatchIn(it) }
        if (contentIndex < 0) return Plan.Text

        val extras = ArrayList<Pair<String, String>>()
        for (k in 0 until count) {
            if (k == contentIndex || lower[k] !in EXTRA_FIELDS) continue
            if (!HtmlSanitizer.isBlank(values[k])) extras.add(names[k] to values[k])
        }
        return Plan.Cloze(names[contentIndex], values[contentIndex], clozeNumber, extras)
    }

    /** AnkiDroid joins field names and values with the unit separator. */
    fun splitFields(joined: String?): List<String> =
        if (joined == null) emptyList() else joined.split('\u001f')
}
