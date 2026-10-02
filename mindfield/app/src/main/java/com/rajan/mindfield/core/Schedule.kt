package com.rajan.mindfield.core

import java.time.LocalDate
import java.time.ZonedDateTime

/** When each daily notification fires. Pure, so time zones and clock changes can be tested. */
object Schedule {
    /** Spot checks land somewhere between noon and 6 pm, at a different minute each day. */
    const val SPOT_FROM = 12 * 60
    const val SPOT_TO = 18 * 60

    /** The next time strictly after [now] that the clock reads [minuteOfDay]. */
    fun next(minuteOfDay: Int, now: ZonedDateTime): ZonedDateTime {
        fun on(day: LocalDate) = day.atStartOfDay(now.zone).plusMinutes(minuteOfDay.toLong())
        val today = on(now.toLocalDate())
        return if (today.isAfter(now)) today else on(now.toLocalDate().plusDays(1))
    }

    /** Today's surprise spot-check minute: random-looking, but the same all day. */
    fun spotMinute(day: LocalDate): Int {
        val rnd = kotlin.random.Random(day.toEpochDay() * 7919 + 17)
        return rnd.nextInt(SPOT_FROM, SPOT_TO)
    }

    /** The next spot check after [now], skipping a day whose slot has already passed. */
    fun nextSpot(now: ZonedDateTime): ZonedDateTime {
        val today = now.toLocalDate()
        val at = today.atStartOfDay(now.zone).plusMinutes(spotMinute(today).toLong())
        if (at.isAfter(now)) return at
        val tomorrow = today.plusDays(1)
        return tomorrow.atStartOfDay(now.zone).plusMinutes(spotMinute(tomorrow).toLong())
    }

    fun label(minuteOfDay: Int): String {
        val h = minuteOfDay / 60
        val m = minuteOfDay % 60
        val h12 = ((h + 11) % 12) + 1
        return "%d:%02d %s".format(h12, m, if (h < 12) "am" else "pm")
    }
}
