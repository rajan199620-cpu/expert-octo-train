package com.rajan.meditationtimer

import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.roundToInt

/** One answer to "how often do you meditate?" in a published survey, as days a week. */
data class FrequencyBand(val label: String, val share: Double, val fromPerWeek: Double, val toPerWeek: Double)

/** A published survey of how often a group of people meditate, least often first. */
data class Survey(val who: String, val detail: String, val source: String, val bands: List<FrequencyBand>)

/** Where a practice frequency falls in one survey. */
data class Placement(
    val band: FrequencyBand,
    /** Estimated share of the group who meditate less often than you, 0–100. */
    val percentile: Int,
    /** In the most frequent band, where a survey can't tell people apart: [percentile] is a floor. */
    val inTopBand: Boolean,
)

/** How someone's recent practice compares with published figures. */
data class Standing(
    val daysSat: Int,
    /** Usually the last 28 days; shorter for someone who started less than four weeks ago. */
    val windowDays: Int,
    val perWeek: Double,
    val minutesLast30: Long,
    val experienced: Placement,
    val india: Placement,
)

/**
 * Comparisons with other people who meditate, from published data only.
 *
 * - **Experienced meditators**: 1,120 people with an average of 15 years of regular practice
 *   said how often they had meditated in the last six months (Vieten et al., PLOS ONE 2018).
 * - **Adults in India**: a 29,999-person national survey (Pew Research Center, 2021) found 32%
 *   meditate daily and 48% at least weekly. Its "meditation" includes religious practice.
 * - **People starting a meditation app**: logged use of the Medito app (Adams et al., JMIR
 *   mHealth and uHealth 2026): half of 655 new users meditated 16 minutes or less in their whole
 *   first month, a quarter more than 75 minutes, and fewer than 1 in 5 used it past two weeks.
 *
 * Within a survey band people are assumed to be spread evenly (the usual way to estimate a
 * percentile from grouped data); in the top band no survey can rank people, so the percentile
 * given there is a floor ("top 41%"), never a claim to beat everyone.
 */
object Compare {
    const val WINDOW_DAYS = 28
    /** Less than a week of history says little about a habit. */
    const val MIN_HISTORY_DAYS = 7
    const val APP_MEDIAN_MINUTES = 16.11
    const val APP_P75_MINUTES = 74.51
    /** Once a month, as days a week. */
    private const val MONTHLY = 7.0 / 30.0

    val EXPERIENCED = Survey(
        "experienced meditators",
        "1,120 meditators with an average of 15 years of practice",
        "Vieten et al. · PLOS ONE · 2018",
        listOf(
            FrequencyBand("not at all", 0.02, 0.0, 0.0),
            FrequencyBand("less than monthly", 0.04, 0.0, MONTHLY),
            FrequencyBand("more than monthly, less than weekly", 0.12, MONTHLY, 1.0),
            FrequencyBand("weekly", 0.11, 1.0, 1.5),
            FrequencyBand("more than weekly, less than daily", 0.30, 1.5, 6.0),
            FrequencyBand("daily", 0.41, 6.0, 7.0),
        ),
    )

    val INDIA = Survey(
        "adults in India",
        "29,999 adults across India",
        "Pew Research Center · Religion in India · 2021",
        listOf(
            FrequencyBand("less than weekly or never", 0.52, 0.0, 1.0),
            FrequencyBand("weekly, not daily", 0.16, 1.0, 6.0),
            FrequencyBand("daily", 0.32, 6.0, 7.0),
        ),
    )

    const val APP_SOURCE = "Adams et al. · JMIR mHealth and uHealth · 2026"

    /** Null until there's a week of history, and while there are no sits in the window. */
    fun standing(records: List<SessionRecord>, zone: ZoneId, today: LocalDate): Standing? {
        val days = records.map { it.day(zone) }.filter { !it.isAfter(today) }
        val first = days.minOrNull() ?: return null
        val history = ChronoUnit.DAYS.between(first, today).toInt() + 1
        if (history < MIN_HISTORY_DAYS) return null
        val window = minOf(WINDOW_DAYS, history)
        val from = today.minusDays(window - 1L)
        val daysSat = days.filter { !it.isBefore(from) }.toSet().size
        if (daysSat == 0) return null
        val perWeek = daysSat * 7.0 / window
        val monthFrom = today.minusDays(29)
        val minutes = records.filter { val d = it.day(zone); !d.isBefore(monthFrom) && !d.isAfter(today) }
            .sumOf { it.actualSec.toLong() } / 60
        return Standing(daysSat, window, perWeek, minutes, place(EXPERIENCED, perWeek), place(INDIA, perWeek))
    }

    /** The band [perWeek] falls in, and the estimated share of the group below it. */
    fun place(survey: Survey, perWeek: Double): Placement {
        val bands = survey.bands
        val i = bands.indexOfLast { it.share > 0 && perWeek >= it.fromPerWeek && it.toPerWeek > it.fromPerWeek }
            .coerceAtLeast(bands.indexOfFirst { it.toPerWeek > it.fromPerWeek })
        val band = bands[i]
        val below = bands.take(i).sumOf { it.share }
        val top = i == bands.lastIndex
        val within = if (top) 0.0 else ((perWeek - band.fromPerWeek) / (band.toPerWeek - band.fromPerWeek)).coerceIn(0.0, 1.0)
        return Placement(band, ((below + band.share * within) * 100).roundToInt().coerceIn(0, 99), top)
    }

    /** "Top 41%" in the top band, otherwise "47th percentile". */
    fun headline(p: Placement): String =
        if (p.inTopBand) "Top ${(p.band.share * 100).roundToInt()}%" else "${ordinal(p.percentile)} percentile"

    /** One sentence on how often you sit and where that puts you among experienced meditators. */
    fun summary(s: Standing): String {
        val sat = "You sat on ${s.daysSat} of the last ${s.windowDays} days"
        val p = s.experienced
        return if (p.inTopBand) {
            "$sat: ${p.band.label}, like the most regular ${(p.band.share * 100).roundToInt()}% of ${EXPERIENCED.who}."
        } else {
            "$sat, more often than about ${p.percentile}% of ${EXPERIENCED.who}."
        }
    }

    /** Where you stand among adults in India, by the survey's own bands. */
    fun indiaLine(s: Standing): String = when (s.india.band) {
        INDIA.bands.last() -> "Like you, 32% of adults in India say they meditate daily; 48% at least weekly."
        INDIA.bands[1] -> "48% of adults in India say they meditate at least weekly, like you; 32% daily."
        else -> "48% of adults in India say they meditate at least weekly, and 32% daily."
    }

    /** Your last 30 days against a new meditation-app user's whole first month. */
    fun appLine(s: Standing): String {
        val you = "You meditated ${s.minutesLast30} ${if (s.minutesLast30 == 1L) "minute" else "minutes"} in the last 30 days"
        return when {
            s.minutesLast30 > APP_P75_MINUTES ->
                "$you. Three in four people new to a meditation app do under 75 minutes in their whole first month."
            s.minutesLast30 > APP_MEDIAN_MINUTES ->
                "$you, more than half of people new to a meditation app do in their whole first month (16 minutes or less)."
            else ->
                "$you. Half of people new to a meditation app do 16 minutes or less in their first month, and fewer than 1 in 5 keep going past two weeks."
        }
    }

    fun ordinal(n: Int): String {
        val suffix = if (n % 100 in 11..13) "th" else when (n % 10) { 1 -> "st"; 2 -> "nd"; 3 -> "rd"; else -> "th" }
        return "$n$suffix"
    }
}
