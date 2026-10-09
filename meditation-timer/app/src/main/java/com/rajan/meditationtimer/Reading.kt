package com.rajan.meditationtimer

import java.time.LocalDate

/**
 * The day's reading: today's lesson with its research, then one common problem. It opens first
 * whenever the app is opened, until it has been read (or skipped) that day; a tap on Begin can
 * no longer skip past it. One a day keeps it from becoming a toll paid on every open.
 */
object Reading {
    /** Due when switched on and not yet read or skipped today. */
    fun due(enabled: Boolean, seenOn: String?, today: LocalDate): Boolean = enabled && seenOn != today.toString()

    /**
     * Which common problem today's reading shows: the next one on each new day the reading is
     * shown, the same one all day. Kept apart from the lesson, which only moves on with days sat,
     * so even a day without sitting brings something new.
     */
    fun problemIndex(lastIndex: Int, lastDay: String?, today: LocalDate): Int =
        if (lastDay == today.toString()) lastIndex.coerceAtLeast(0) else lastIndex + 1
}
