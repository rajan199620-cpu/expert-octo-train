package com.rajan.mindfield.core

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Plain-text versions of things you might want to send or keep outside the app. */
object Texts {
    /**
     * A concept as a message for a friend. Explaining an idea to someone else is one of the
     * better ways to learn it (expecting to teach improves recall).
     */
    fun share(c: Concept): String = buildString {
        append("🧠 ").append(c.title)
        c.aka?.let { append(" (").append(it).append(")") }
        append("\n\n").append(c.hook)
        append("\n\n").append(c.what)
        append("\n\n🎯 Try it today: ").append(c.missionLine)
        append("\n\n🧪 Evidence: ").append(c.evidence.label).append(". ").append(c.proof)
        append("\n📚 ").append(c.source)
        append("\n\n— from Mindfield, a field guide to the human mind")
    }

    /** The whole field journal as readable text, newest day first, grouped by day. */
    fun journal(state: AppState, library: Library, today: LocalDate): String = buildString {
        val entries = state.liveEntries.sortedWith(compareByDescending<Entry> { it.day }.thenBy { it.createdAt })
        append("Mindfield field journal\n")
        append("Exported ").append(today.format(DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ENGLISH)))
        append(" · ").append(entries.size).append(if (entries.size == 1) " note" else " notes").append("\n")
        var day: LocalDate? = null
        for (e in entries) {
            if (e.day != day) {
                day = e.day
                append("\n## ").append(e.day.format(DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", Locale.ENGLISH))).append("\n")
            }
            val title = library[e.conceptId]?.title ?: e.conceptId
            append("\n").append(e.mode.emoji).append(" ").append(e.mode.label).append(" · ").append(title)
            e.outcome?.let { append(" (").append(it.label.lowercase()).append(")") }
            if (e.note.isNotBlank()) append("\n").append(e.note.lines().joinToString("\n") { "  $it" })
            append("\n")
        }
    }
}

/**
 * Is the phone delivering the daily notification? Some phones' battery managers silently stop
 * apps' alarms. [reference] is the latest of: the last morning notification that actually fired,
 * when the morning time was last set, and the first day of use. Two missed mornings in a row
 * (allowing half an hour's slack today) means something is blocking it.
 */
object Delivery {
    fun looksBlocked(morningOn: Boolean, reference: LocalDate?, today: LocalDate, minuteNow: Int, morningMinute: Int): Boolean {
        if (!morningOn || reference == null) return false
        val latestExpected = if (minuteNow >= morningMinute + 30) today else today.minusDays(1)
        return latestExpected >= reference.plusDays(2)
    }
}
