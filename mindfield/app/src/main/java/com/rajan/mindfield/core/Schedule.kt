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
        val today = at(now.toLocalDate(), minuteOfDay, now.zone)
        return if (today.isAfter(now)) today else at(now.toLocalDate().plusDays(1), minuteOfDay, now.zone)
    }

    /** The wall-clock time on [day]: not midnight plus minutes, which is off by an hour on DST days. */
    private fun at(day: LocalDate, minuteOfDay: Int, zone: java.time.ZoneId): ZonedDateTime =
        day.atTime(minuteOfDay / 60, minuteOfDay % 60).atZone(zone)

    /** Today's surprise spot-check minute: random-looking, but the same all day. */
    fun spotMinute(day: LocalDate): Int {
        val rnd = kotlin.random.Random(day.toEpochDay() * 7919 + 17)
        return rnd.nextInt(SPOT_FROM, SPOT_TO)
    }

    /** The next spot check after [now], skipping a day whose slot has already passed. */
    fun nextSpot(now: ZonedDateTime): ZonedDateTime {
        val today = now.toLocalDate()
        val slot = at(today, spotMinute(today), now.zone)
        if (slot.isAfter(now)) return slot
        val tomorrow = today.plusDays(1)
        return at(tomorrow, spotMinute(tomorrow), now.zone)
    }

    fun label(minuteOfDay: Int): String {
        val h = minuteOfDay / 60
        val m = minuteOfDay % 60
        val h12 = ((h + 11) % 12) + 1
        return "%d:%02d %s".format(h12, m, if (h < 12) "am" else "pm")
    }
}
