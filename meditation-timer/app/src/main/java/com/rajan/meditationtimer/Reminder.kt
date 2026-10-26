package com.rajan.meditationtimer

import java.time.LocalDate
import java.time.ZonedDateTime

/**
 * A daily nudge tied to a habit you already have. "After morning tea, I sit" is an if-then plan
 * (an implementation intention), which helps far more than a bare time-of-day alarm.
 */
data class Reminder(val enabled: Boolean, val minuteOfDay: Int, val cue: String) {
    val timeLabel: String
        get() = "%d:%02d %s".format(((minuteOfDay / 60 + 11) % 12) + 1, minuteOfDay % 60, if (minuteOfDay < 12 * 60) "am" else "pm")

    /** The next time to fire, strictly after [now]. */
    fun nextAt(now: ZonedDateTime): ZonedDateTime {
        fun on(day: LocalDate) = day.atStartOfDay(now.zone).plusMinutes(minuteOfDay.toLong())
        val today = on(now.toLocalDate())
        return if (today.isAfter(now)) today else on(now.toLocalDate().plusDays(1))
    }

    /** Notification text: the cue in your own words, then the plan. */
    fun message(usualMinutes: Int): Pair<String, String> {
        val habit = cue.trim().removeSuffix(".").trim()
        val title = if (habit.isEmpty()) "Time to sit" else habit.replaceFirstChar { it.uppercase() }
        val text = if (habit.isEmpty()) "Your usual $usualMinutes minutes, whenever you're ready." else "That's your cue: sit for $usualMinutes minutes."
        return title to text
    }

    companion object {
        /** No nudge on a day you've already sat. */
        fun shouldNotify(sitDays: Set<LocalDate>, now: ZonedDateTime): Boolean = now.toLocalDate() !in sitDays
    }
}
