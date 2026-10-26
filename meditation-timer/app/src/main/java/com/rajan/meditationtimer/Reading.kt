package com.rajan.meditationtimer

import java.time.LocalDate

/**
 * The day's reading: one page a day, today's lesson with its research one day and a common
 * problem the next. It opens first whenever the app is opened, until it has been read (or
 * skipped) that day; a tap on Begin can no longer skip past it. One page a day keeps it from
 * becoming a toll paid on every open.
 */
object Reading {
    /** Due when switched on and not yet read or skipped today. */
    fun due(enabled: Boolean, seenOn: String?, today: LocalDate): Boolean = enabled && seenOn != today.toString()

    /**
     * Whether today's reading is a common problem (else the research): the other kind from the
     * last day it was shown, the same all day. The first day is the research.
     */
    fun problemToday(lastWasProblem: Boolean, lastDay: String?, today: LocalDate): Boolean =
        if (lastDay == today.toString()) lastWasProblem else !lastWasProblem

    /**
     * Which common problem a problem day shows: the next one on each new problem day, the same
     * one all day. Kept apart from the lesson, which only moves on with days sat.
     */
    fun problemIndex(lastIndex: Int, lastDay: String?, today: LocalDate): Int =
        if (lastDay == today.toString()) lastIndex.coerceAtLeast(0) else lastIndex + 1
}
