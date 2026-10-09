package com.rajan.meditationtimer

import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.roundToInt

/** Which way your practice is heading: the last 7 days against your 4-week average. */
enum class Trend(val label: String) { BUILDING("Building"), STEADY("Steady"), EASING("Easing off") }

/** A daily amount of practice a study used or found, said plainly, with its source. */
data class DoseMark(val minutesPerDay: Double, val finding: String, val source: String)

/** An amount of lifetime practice a study linked with a result. */
data class HoursMark(val hours: Double, val what: String, val source: String)

/** Where your practice stands now; see [Progress]. */
data class ProgressReport(
    /** Average minutes a day over [windowDays], days without a sit counted as 0. */
    val perDay: Double,
    /** 28, or fewer for someone who started less than four weeks ago. */
    val windowDays: Int,
    /** The last 7 days' average, from the second week on; it sets the [trend]. */
    val thisWeekPerDay: Double?,
    val trend: Trend?,
    /** Days in the current run of practice; 0 once a full week has passed without a sit. */
    val runDays: Int,
    /** Average minutes a day over the run, or its last 8 weeks. */
    val runPerDay: Double,
    val lifetimeMinutes: Long,
    /** The next lifetime marker not yet reached, and about how many days off at [perDay]. */
    val nextMark: HoursMark?,
    val daysToNext: Int?,
    /** [perDay] as it stood on each of the last (up to) 84 days, oldest first. */
    val curve: List<Double>,
)

/**
 * Where your practice stands, worked out afresh from your sits every day, so it rises when you
 * sit more or longer and eases when you sit less, rather than a percentile that stops moving
 * once you sit daily. No made-up score: every number is your own timed minutes, set against
 * what published studies used or found:
 *
 * - **Your level**: minutes a day over the last 4 weeks, days off counted as 0, with this week
 *   against it (Building, Steady, Easing off).
 * - **Your run**: how long you've kept going, against the 8 weeks of 13 minutes a day after which
 *   beginners' attention, memory and mood improved in a trial (Basso et al., 2019). A missed day
 *   or two doesn't end a run: a missed day didn't slow habit-forming either (Lally et al., 2010).
 *   A full week without a sit does.
 * - **Your hours**: lifetime practice, with the next marker from the research and roughly when
 *   you'd reach it at your current pace.
 *
 * Studies describe groups, not you, and dose findings are mixed: across 203 trials, longer
 * programmes weren't clearly better for distress (Strohmaier, 2020), and in one app trial whether
 * more minutes meant less distress depended on how it was measured (Goldberg et al., 2024). So
 * these are landmarks, not promises.
 */
object Progress {
    const val WINDOW_DAYS = 28
    const val WEEK = 7
    /** A full week without a sit ends a run. */
    const val RUN_BREAK_DAYS = 7
    const val TRIAL_WEEKS = 8
    const val CURVE_DAYS = 84

    val TRIAL = DoseMark(
        13.0,
        "13 min a day: after 8 weeks, beginners' attention, memory and mood had improved; at 4 weeks, not yet.",
        "Basso et al. · Behavioural Brain Research · 2019",
    )

    /** The daily amounts from the research, smallest first. */
    val DOSES = listOf(
        TRIAL,
        DoseMark(
            27.0,
            "27 min a day: what people on an 8-week MBSR course practised on average (22.6 hours in all); " +
                "their brain scans showed grey-matter changes.",
            "Hölzel et al. · Psychiatry Research: Neuroimaging · 2011",
        ),
        DoseMark(
            30.0,
            "About 30 min, 6 days a week: typical home practice on MBSR and MBCT courses, two-thirds of the " +
                "45 minutes asked; more practice went with slightly better results.",
            "Parsons et al. · Behaviour Research and Therapy · 2017",
        ),
        DoseMark(
            35.0,
            "35–65 min a day: what meditators with experience needed for clear gains in well-being over two " +
                "months (50–80 for mental health). Sitting often counted for more than sitting long.",
            "Bowles & Van Dam · Applied Psychology: Health and Well-Being · 2025",
        ),
    )

    /** Lifetime markers, smallest first. */
    val HOURS = listOf(
        HoursMark(22.6, "an 8-week MBSR course's practice", "Hölzel et al. · 2011"),
        HoursMark(160.0, "the lifetime practice that went with clearly lower distress and higher life satisfaction", "Bowles et al. · Mindfulness · 2022"),
        HoursMark(1_095.0, "the average lifetime practice of 1,668 meditators surveyed", "Bowles et al. · Mindfulness · 2022"),
        HoursMark(12_000.0, "where the long-term yogis studied by Richard Davidson's lab began", "Goleman & Davidson · Altered Traits · 2017"),
    )

    /** Null until the first sit. */
    fun report(records: List<SessionRecord>, zone: ZoneId, today: LocalDate): ProgressReport? {
        val byDay = HashMap<LocalDate, Double>()
        for (r in records) {
            val day = r.day(zone)
            if (!day.isAfter(today)) byDay.merge(day, r.actualSec / 60.0, Double::plus)
        }
        val first = byDay.keys.minOrNull() ?: return null
        val history = ChronoUnit.DAYS.between(first, today).toInt() + 1
        fun average(end: LocalDate, days: Int): Double =
            (0 until days).sumOf { byDay[end.minusDays(it.toLong())] ?: 0.0 } / days

        val window = minOf(WINDOW_DAYS, history)
        val perDay = average(today, window)
        val thisWeek = if (history > WEEK) average(today, WEEK) else null

        val sitDays = byDay.keys.sortedDescending()
        var runStart = sitDays.first()
        val runDays = if (ChronoUnit.DAYS.between(runStart, today) > RUN_BREAK_DAYS) {
            0
        } else {
            for (day in sitDays.drop(1)) {
                if (ChronoUnit.DAYS.between(day, runStart) > RUN_BREAK_DAYS) break
                runStart = day
            }
            ChronoUnit.DAYS.between(runStart, today).toInt() + 1
        }
        val runPerDay = if (runDays == 0) 0.0 else average(today, minOf(runDays, TRIAL_WEEKS * WEEK))

        val lifetime = byDay.values.sum().toLong()
        val next = HOURS.firstOrNull { it.hours * 60 > lifetime }
        val days = next?.takeIf { perDay >= 0.5 }?.let { ceil((it.hours * 60 - lifetime) / perDay).toInt() }
        val curve = (minOf(CURVE_DAYS, history) - 1 downTo 0).map { back ->
            average(today.minusDays(back.toLong()), minOf(WINDOW_DAYS, history - back))
        }
        return ProgressReport(perDay, window, thisWeek, thisWeek?.let { trend(it, perDay) }, runDays, runPerDay, lifetime, next, days, curve)
    }

    /** Clearly above or below your level counts as a change; within about 15% is steady. */
    fun trend(week: Double, level: Double): Trend {
        val margin = maxOf(1.0, 0.15 * level)
        return when {
            week > level + margin -> Trend.BUILDING
            week < level - margin -> Trend.EASING
            else -> Trend.STEADY
        }
    }

    /** "16 min a day". */
    fun headline(r: ProgressReport): String = "${minutes(r.perDay)} a day"

    /** What the headline averages over, and this week beside it. */
    fun windowLine(r: ProgressReport): String {
        val over = when {
            r.windowDays >= WINDOW_DAYS -> "your average over the last 4 weeks"
            r.windowDays == 1 -> "your first day"
            else -> "your average over the ${r.windowDays} days since you started"
        }
        return over + (r.thisWeekPerDay?.let { "  ·  this week ${minutes(it)} a day" } ?: "")
    }

    /** How long you've kept it up, against the 8 weeks of the trial. */
    fun runLine(r: ProgressReport): String {
        if (r.runDays == 0) return "Your last sit was over a week ago. A sit today starts a new run; every hour so far still counts."
        val weeks = r.runDays / WEEK
        val span = if (weeks == 0) dayCount(r.runDays) else if (weeks == 1) "1 week" else "$weeks weeks"
        val pace = "$span in, at ${minutes(r.runPerDay)} a day"
        val trial = "of a trial in which beginners' attention, memory and mood improved after 8 weeks, though not yet at 4"
        return when {
            r.runPerDay < TRIAL.minutesPerDay -> "$pace: below the 13 a day $trial."
            weeks >= TRIAL_WEEKS -> "$pace: past the 8 weeks of 13 a day $trial."
            else -> {
                val left = TRIAL_WEEKS - weeks
                "$pace: above the 13 a day $trial. ${if (left == 1) "1 week" else "$left weeks"} to go to match it."
            }
        }
    }

    /** Lifetime hours, and the next marker with roughly when you'd reach it. */
    fun hoursLine(r: ProgressReport): String {
        val all = if (r.lifetimeMinutes < 60) "${r.lifetimeMinutes} minutes in all" else "${hours(r.lifetimeMinutes / 60.0)} in all"
        val next = r.nextMark ?: return "$all: past every marker here, the 12,000 hours of the yogis included."
        val eta = r.daysToNext?.let { ", ${eta(it)} away at this pace" } ?: ""
        return "$all. Next: ${markHours(next.hours)}, ${next.what}$eta."
    }

    /** Where you stand on the habit timeline. */
    fun habitLine(r: ProgressReport): String =
        if (r.runDays == 0) {
            "A new daily habit took a median of 66 days to feel automatic (18 to 254), and one missed day didn't set it back."
        } else {
            "Day ${r.runDays} of your run. A new daily habit took a median of 66 days to feel automatic (18 to 254), " +
                "and one missed day didn't set it back."
        }

    fun minutes(perDay: Double): String {
        val m = perDay.roundToInt()
        return if (m == 0 && perDay > 0) "under 1 min" else "$m min"
    }

    /** Your hours: one decimal while they're few, whole hours after. */
    fun hours(h: Double): String = when {
        h < 10 -> String.format(Locale.ROOT, "%.1f hours", h)
        else -> String.format(Locale.US, "%,d hours", h.roundToInt())
    }

    /** A marker's hours as the study gave them: 22.6, 160, 1,095. */
    private fun markHours(h: Double): String =
        if (h % 1.0 == 0.0) String.format(Locale.US, "%,d hours", h.toLong()) else String.format(Locale.ROOT, "%.1f hours", h)

    /** Roughly how long [days] is: days, then weeks, months, years. */
    fun eta(days: Int): String = when {
        days < 14 -> if (days == 1) "1 day" else "$days days"
        days < 120 -> "about ${(days / 7.0).roundToInt()} weeks"
        days < 730 -> "about ${(days / 30.44).roundToInt()} months"
        days < 50 * 365 -> "about ${(days / 365.25).roundToInt()} years"
        else -> "over 50 years"
    }

    private fun dayCount(days: Int) = if (days == 1) "1 day" else "$days days"
}
