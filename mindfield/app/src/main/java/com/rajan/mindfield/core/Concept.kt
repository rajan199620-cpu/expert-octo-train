package com.rajan.mindfield.core

/** The nine areas of psychology the guide covers. [key] is what the library file uses. */
enum class Category(val key: String, val label: String, val short: String, val blurb: String) {
    MEMORY("memory", "Memory & perception", "Perception", "What we notice, what we miss, and how memory rewrites itself."),
    SELF("self", "The self", "Self", "How we see ourselves, explain ourselves and guard our self-image."),
    THINKING("thinking", "Thinking traps", "Thinking", "Mental shortcuts that are usually useful and sometimes badly wrong."),
    INFLUENCE("influence", "Influence & persuasion", "Influence", "How people move each other: asks, gifts, pressure and proof."),
    FEELINGS("feelings", "Emotions & wellbeing", "Feelings", "How feelings work, how they mislead us and how to steer them."),
    CONNECTION("connection", "Relationships", "Connection", "Liking, trust and closeness, and the small moments that build them."),
    DECISIONS("decisions", "Choices & money", "Choices", "Why the way options are framed changes what we pick."),
    HABITS("habits", "Motivation & habits", "Habits", "What gets us moving, keeps us going and makes behaviour stick."),
    GROUPS("groups", "Groups & society", "Groups", "What crowds, teams and tribes do to the people inside them.");

    companion object {
        fun of(key: String): Category? = entries.firstOrNull { it.key == key }
    }
}

/** How well a finding has held up. Shown on every concept so a myth is never taught as fact. */
enum class Evidence(val key: String, val label: String, val meaning: String) {
    SOLID("solid", "Solid", "Replicated many times; large studies or meta-analyses agree."),
    GOOD("good", "Good", "Real, but smaller or more conditional than the famous version."),
    DEBATED("debated", "Debated", "Mixed replications. Treat it as a maybe."),
    BUSTED("busted", "Busted", "Failed to replicate or was misreported. Learn it as a myth.");

    companion object {
        fun of(key: String): Evidence? = entries.firstOrNull { it.key == key }
    }
}

/**
 * "Predict first": guess the study's result before reading it (the pretesting effect).
 * [answer] and stored guesses index [options] in library order; screens show [order].
 */
data class Prediction(val question: String, val options: List<String>, val answer: Int) {
    /** The options' display order: shuffled, but the same every time for a concept. */
    fun order(conceptId: String): List<Int> = options.indices.shuffled(kotlin.random.Random(conceptId.hashCode()))
}

data class Concept(
    val id: String,
    /** Specimen number: the concept's place in the default daily order, from 1. */
    val number: Int,
    val title: String,
    val aka: String?,
    val category: Category,
    val evidence: Evidence,
    /** One line: the notification text. */
    val hook: String,
    val what: String,
    val study: String,
    /** Why it got its evidence label. */
    val proof: String,
    val spot: String,
    /** Today's mission: one concrete thing to try. */
    val use: String,
    val guard: String,
    val predict: Prediction,
    /** A short everyday story for the "Name it" review: which concept is this? */
    val scenario: String,
    val source: String,
    val related: List<String>,
) {
    val isMyth: Boolean get() = evidence == Evidence.BUSTED

    /** The mission's first sentence, short enough for a notification line. */
    val missionLine: String get() = firstSentence(use)

    val numberLabel: String get() = "No. %03d".format(number)

    companion object {
        /** Up to the first full stop, question or exclamation mark (and any closing quote) before a space. */
        fun firstSentence(text: String): String {
            val end = Regex("""[.!?][”’)]?(?=\s|$)""").find(text) ?: return text
            return text.substring(0, end.range.last + 1)
        }
    }
}
