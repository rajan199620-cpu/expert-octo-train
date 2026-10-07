package com.rajan.mindfield.core

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import kotlin.math.roundToInt

/** A streak that forgives one missed day a week, and when it last did. */
data class Streak(val days: Int, val restsUsed: Int, val restThisWeek: Boolean)

/** This week against the weekly goal, and how many weeks running it has been met. */
data class WeekGoal(val goal: Int, val daysThisWeek: Int, val met: Boolean, val weeksRunning: Int) {
    val daysLeft: Int get() = (goal - daysThisWeek).coerceAtLeast(0)
}

/** A month in the field, for the month in review. Every number comes from the journal. */
data class MonthReview(
    val month: YearMonth,
    val inProgress: Boolean,
    val daysLogged: Int,
    val daysSoFar: Int,
    val reports: Int,
    val byMode: Map<Mode, Int>,
    val discovered: Int,
    val mythsMet: Int,
    val topArea: Category?,
    val topConcept: String?,
    val predictions: Int,
    val predictionsRight: Int,
    val previousReports: Int?,
) {
    val sightings: Int get() = byMode.filterKeys { it.isSighting }.values.sum()
}

/** Your predictions against chance, and how long you've kept going. */
data class Standing(val predicted: Int, val right: Int, val chancePercent: Int, val dayNumber: Int, val activeThisWeek: Boolean) {
    val percent: Int get() = if (predicted == 0) 0 else (right * 100.0 / predicted).roundToInt()
}

/** The day's steps, ticked off as they're done. */
enum class Step(val label: String) { PREDICT("Predict"), PLAN("Plan"), REPORT("Report"), REVIEW("Review") }

data class PathStep(val step: Step, val done: Boolean)

/**
 * Progress features taken from the most-used learning apps, each for a reason with research
 * behind it:
 *
 * - **A forgiving streak** (like Duolingo's streak freeze): one missed day a week doesn't break
 *   it. A highlighted broken streak makes people less likely to carry on (Silverman & Barasch,
 *   Journal of Consumer Research 2023), and one missed day doesn't harm a forming habit (Lally et
 *   al., European Journal of Social Psychology 2010).
 * - **A weekly goal** in days: room for a busy day, where a daily target is all-or-nothing.
 * - **A month in review** (like Duolingo's and Spotify's yearly recaps), shown first in a new
 *   month: the start of a month is a "fresh start" when people are most ready to recommit
 *   (Dai, Milkman & Riis, Management Science 2014).
 * - **Today's path**: the day's steps ticked off as you go. People speed up as a goal comes
 *   into view, and showing progress already made helps them finish (the goal-gradient effect;
 *   Kivetz, Urminsky & Zheng, Journal of Marketing Research 2006).
 */
object Progress {
    const val REST_DAYS_PER_WEEK = 1
    /** Baumel et al., JMIR 2019: median share of installers still using a mental-health app 30 days on. */
    const val APP_30_DAY_RETENTION = 3.3
    /** Hoogeveen, Sarafoglou & Wagenmakers, 2020: laypeople's accuracy at predicting replications (chance 50%). */
    const val LAYPEOPLE_REPLICATION_ACCURACY = 59

    fun weekOf(day: LocalDate): LocalDate = day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

    /**
     * Days with a report in a run ending today (or yesterday, if today has none yet), where up to
     * [restsPerWeek] missed day in any Monday–Sunday week is forgiven. A missed day only counts
     * as a rest once an earlier report shows the run really did continue past it.
     */
    fun streak(days: Set<LocalDate>, today: LocalDate, restsPerWeek: Int = REST_DAYS_PER_WEEK): Streak {
        if (days.isEmpty()) return Streak(0, 0, false)
        val first = days.min()
        var day = if (today in days) today else today.minusDays(1)
        var count = 0
        var committed = 0
        val restsByWeek = HashMap<LocalDate, Int>()
        val pending = ArrayList<LocalDate>()
        while (!day.isBefore(first)) {
            if (day in days) {
                count++
                for (r in pending) restsByWeek.merge(weekOf(r), 1, Int::plus)
                committed += pending.size
                pending.clear()
            } else {
                val week = weekOf(day)
                val used = (restsByWeek[week] ?: 0) + pending.count { weekOf(it) == week }
                if (used >= restsPerWeek) break
                pending += day
            }
            day = day.minusDays(1)
        }
        return Streak(count, committed, (restsByWeek[weekOf(today)] ?: 0) > 0)
    }

    /** The longest forgiving streak: only the last day of each unbroken run can end the longest one. */
    fun longestStreak(days: Set<LocalDate>, restsPerWeek: Int = REST_DAYS_PER_WEEK): Int =
        days.filter { it.plusDays(1) !in days }.maxOfOrNull { streak(days, it, restsPerWeek).days } ?: 0

    /** This week against [goal] days, and weeks in a row it has been met (this week counts once met). */
    fun weekGoal(days: Set<LocalDate>, today: LocalDate, goal: Int): WeekGoal {
        val monday = weekOf(today)
        fun daysIn(start: LocalDate) = (0L..6L).count { val d = start.plusDays(it); d in days && !d.isAfter(today) }
        val now = daysIn(monday)
        val met = goal > 0 && now >= goal
        var running = if (met) 1 else 0
        if (goal > 0) {
            val first = days.minOrNull()
            var week = monday.minusWeeks(1)
            while (first != null && !week.plusDays(6).isBefore(first) && daysIn(week) >= goal) {
                running++
                week = week.minusWeeks(1)
            }
        }
        return WeekGoal(goal, now, met, running)
    }

    /**
     * Months with any reports or newly discovered concepts, newest first, never past today: the
     * same rule as [month], so every month listed has a review to show.
     */
    fun months(state: AppState, today: LocalDate): List<YearMonth> =
        (state.liveEntries.map { it.day } + state.unlocked.values)
            .filter { !it.isAfter(today) }.map { YearMonth.from(it) }.distinct().sortedDescending()

    /** Days of the week still open for a report: the days after today, and today itself if it has none yet. */
    fun openDays(days: Set<LocalDate>, today: LocalDate): Int {
        val sunday = weekOf(today).plusDays(6)
        return ChronoUnit.DAYS.between(today, sunday).toInt() + if (today in days) 0 else 1
    }

    /**
     * "3 of 5 days this week · 2 to go", "Goal met ✓ · 3 weeks running", or a plain count without a
     * goal. When the days left can't reach the goal it doesn't count down to the impossible: it says
     * every report still counts (a goal seen as out of reach tends to make people give up on the rest).
     */
    fun goalLine(g: WeekGoal, openDays: Int): String = when {
        g.goal <= 0 -> if (g.daysThisWeek == 1) "1 day this week" else "${g.daysThisWeek} days this week"
        g.met && g.weeksRunning >= 2 -> "Goal met ✓ · ${g.weeksRunning} weeks running"
        g.met -> "Goal met ✓ · ${g.daysThisWeek} of ${g.goal} days this week"
        g.daysThisWeek + openDays < g.goal -> "${g.daysThisWeek} of ${g.goal} days this week · every report still counts for your streak"
        else -> "${g.daysThisWeek} of ${g.goal} days this week · ${g.daysLeft} to go"
    }

    /** In the first week of a month, last month's review; otherwise this month so far. */
    fun defaultMonth(months: List<YearMonth>, today: LocalDate): YearMonth? {
        val now = YearMonth.from(today)
        val last = now.minusMonths(1)
        return when {
            today.dayOfMonth <= 7 && last in months -> last
            now in months -> now
            else -> months.firstOrNull()
        }
    }

    fun month(state: AppState, library: Library, month: YearMonth, today: LocalDate, zone: ZoneId): MonthReview? {
        if (month > YearMonth.from(today)) return null
        val inMonth = state.liveEntries.filter { YearMonth.from(it.day) == month && !it.day.isAfter(today) }
        val discovered = state.unlocked.filter { (_, d) -> YearMonth.from(d) == month && !d.isAfter(today) }.keys
        if (inMonth.isEmpty() && discovered.isEmpty()) return null
        val inProgress = month == YearMonth.from(today)
        val sightings = inMonth.filter { it.mode.isSighting }
        val byArea = sightings.mapNotNull { library[it.conceptId]?.category }.groupingBy { it }.eachCount()
        val topArea = byArea.entries.sortedWith(compareBy({ -it.value }, { it.key.ordinal })).firstOrNull()?.key
        val topConcept = sightings.groupingBy { it.conceptId }.eachCount()
            .entries.sortedWith(compareBy({ -it.value }, { it.key })).firstOrNull()?.takeIf { it.value >= 2 }?.key
        val guesses = state.guesses.filter { (id, g) ->
            library.contains(id) && YearMonth.from(Instant.ofEpochMilli(g.at).atZone(zone).toLocalDate()) == month
        }
        val right = guesses.count { (id, g) -> library[id]?.predict?.answer == g.choice }
        val previous = state.liveEntries.count { YearMonth.from(it.day) == month.minusMonths(1) }
        val daysLogged = inMonth.map { it.day }.toSet().size
        // In the month you started, the days before you started don't count against you.
        val started = listOfNotNull(state.startDay, state.liveEntries.minOfOrNull { it.day }).minOrNull()
        val from = listOfNotNull(month.atDay(1), started).max()
        val to = if (inProgress) today else month.atEndOfMonth()
        return MonthReview(
            month = month,
            inProgress = inProgress,
            daysLogged = daysLogged,
            daysSoFar = maxOf(ChronoUnit.DAYS.between(from, to).toInt() + 1, daysLogged, 1),
            reports = inMonth.size,
            byMode = Mode.entries.associateWith { m -> inMonth.count { it.mode == m } },
            discovered = discovered.size,
            mythsMet = discovered.count { library[it]?.isMyth == true },
            topArea = topArea,
            topConcept = topConcept,
            predictions = guesses.size,
            predictionsRight = right,
            previousReports = previous.takeIf { it > 0 },
        )
    }

    fun standing(state: AppState, library: Library, today: LocalDate): Standing {
        val guessed = state.guesses.filterKeys { library.contains(it) }
        val right = guessed.count { (id, g) -> library[id]!!.predict.answer == g.choice }
        val chance = if (guessed.isEmpty()) 0.0 else guessed.keys.map { 1.0 / library[it]!!.predict.options.size }.average()
        val active = state.liveEntries.any { !it.day.isAfter(today) && ChronoUnit.DAYS.between(it.day, today) < 7 }
        return Standing(guessed.size, right, (chance * 100).roundToInt(), Stats.dayNumber(state, today), active)
    }

    /** Within this many points of blind guessing counts as "about the same". */
    const val CHANCE_MARGIN = 10

    /** Your predictions against blind guessing; needs a handful before it says anything. */
    fun predictionLine(s: Standing): String = when {
        s.predicted < 5 -> "Predict ${5 - s.predicted} more ${if (5 - s.predicted == 1) "study" else "studies"} to see how your intuition compares with chance."
        s.percent >= s.chancePercent + CHANCE_MARGIN ->
            "You've called ${s.percent}% of study results before reading them, where blind guessing would get about ${s.chancePercent}%."
        s.percent <= s.chancePercent - CHANCE_MARGIN ->
            "You've called ${s.percent}% of study results, below what blind guessing gets (${s.chancePercent}%): these findings run against intuition. Surprises are the point: they're what you remember."
        else ->
            "You've called ${s.percent}% of study results, about what blind guessing gets (${s.chancePercent}%). Surprises are the point: they're what you remember."
    }

    /** How rare keeping going is, against what usually happens with self-help apps. */
    fun consistencyLine(s: Standing): String {
        val study = "a median of ${APP_30_DAY_RETENTION}% of people who install a mental-health app are still using it a month later"
        return if (s.dayNumber >= 30 && s.activeThisWeek) {
            "Day ${s.dayNumber} and still going. Across popular apps, $study."
        } else {
            "Across popular apps, $study. You're on day ${s.dayNumber}."
        }
    }

    /** The day's steps: predict, plan, report, and review when something is due or was done today. */
    fun todayPath(state: AppState, library: Library, today: LocalDate, conceptId: String): List<PathStep> {
        val due = Spacing.due(state, library, today).size
        val reviewedToday = state.cards.values.any { it.lastReviewed == today }
        return buildList {
            add(PathStep(Step.PREDICT, conceptId in state.guesses))
            add(PathStep(Step.PLAN, state.plans[today]?.text?.isNotBlank() == true))
            add(PathStep(Step.REPORT, state.entriesOn(today).any { it.conceptId == conceptId }))
            if (due > 0 || reviewedToday) add(PathStep(Step.REVIEW, due == 0))
        }
    }
}
