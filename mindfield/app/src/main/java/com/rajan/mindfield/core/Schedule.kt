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

    /** "8:05 am", or "08:05" on a phone set to the 24-hour clock. */
    fun label(minuteOfDay: Int, is24: Boolean = false): String {
        val h = minuteOfDay / 60
        val m = minuteOfDay % 60
        if (is24) return "%02d:%02d".format(h, m)
        val h12 = ((h + 11) % 12) + 1
        return "%d:%02d %s".format(h12, m, if (h < 12) "am" else "pm")
    }
}

/**
 * Which day a field report belongs to, with one rule for the notification buttons and the app:
 * until 4 am, writing up yesterday's concept counts for yesterday. The evening and spot-check
 * nudges are about that day too, and they expire at 4 am.
 */
object FieldDay {
    const val NIGHT_ENDS_HOUR = 4

    /** The day whose concept the evening is about at [now]: yesterday's until 4 am. */
    fun current(now: ZonedDateTime): LocalDate =
        if (now.hour < NIGHT_ENDS_HOUR) now.toLocalDate().minusDays(1) else now.toLocalDate()

    /** The day a report on [conceptId] made at [now] counts for. */
    fun of(state: AppState, conceptId: String, now: ZonedDateTime): LocalDate {
        val today = now.toLocalDate()
        if (now.hour >= NIGHT_ENDS_HOUR) return today
        val yesterday = today.minusDays(1)
        val shown = state.assignments.filterValues { it.conceptId == conceptId }.keys.filter { it <= today }.maxOrNull()
        return if (shown == yesterday) yesterday else today
    }

    /** When a nudge about the field day at [now] stops being useful: 4 am after it. */
    fun expires(now: ZonedDateTime): ZonedDateTime =
        current(now).plusDays(1).atTime(NIGHT_ENDS_HOUR, 0).atZone(now.zone)
}

/**
 * The phone's clock in whatever time zone the phone is in now. Clock.systemDefaultZone() fixes the
 * zone when it is created, so after travel "today" and every reminder would stay in the old zone.
 */
object SystemZoneClock : java.time.Clock() {
    override fun getZone(): java.time.ZoneId = java.time.ZoneId.systemDefault()
    override fun withZone(zone: java.time.ZoneId): java.time.Clock = java.time.Clock.system(zone)
    override fun instant(): java.time.Instant = java.time.Instant.now()
    override fun millis(): Long = System.currentTimeMillis()
}
