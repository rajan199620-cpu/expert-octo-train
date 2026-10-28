package com.rajan.mindfield.core

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.roundToInt

/** How often you've seen bias in others against in yourself, from the journal. */
data class BlindSpot(
    val others: Int,
    val self: Int,
    /** Your share of sightings that were in yourself over the last [Weekly.RECENT_DAYS] days, and before; null with too few to say. */
    val recentSelfPercent: Int?,
    val earlierSelfPercent: Int?,
    /** The concept you've spotted most in others and least in yourself, if one stands out. */
    val conceptId: String?,
    val conceptOthers: Int,
    val conceptSelf: Int,
)

/**
 * The weekly review: five minutes on Sunday looking back on the week's own reports, ending in one
 * if-then plan for the week after. Built on what the research supports, and avoiding what it doesn't:
 *
 * - **Reflection on your own experience**, not free journaling. Fifteen minutes spent writing down
 *   what was learned beat fifteen more minutes of practice, but only once there was experience to
 *   reflect on (Di Stefano, Gino, Pisano & Staats, "Learning by Thinking", HBS 14-093): so a week
 *   needs [MIN_REPORTS] reports before it gets a review. Open-ended expressive writing has a tiny
 *   average effect (Frattaroli, Psychological Bulletin 2006) and none on depression in brief
 *   self-help form (Reinhold, Bürkner & Holling, Clinical Psychology: Science and Practice 2018).
 * - **Concrete "what happened, what will you do" questions, never "why"**: abstract "why" thinking
 *   feeds rumination, concrete thinking doesn't (Watkins, Psychological Bulletin 2008).
 * - **The bias blind spot**: people see bias in others far more readily than in themselves
 *   (Pronin, Lin & Ross 2002; Scopelliti et al., Management Science 2015). The journal already
 *   says how often you spotted a concept in others against caught it in yourself.
 * - **Ending in an if-then plan**, the form people follow through on (Gollwitzer & Sheeran,
 *   Advances in Experimental Social Psychology 2006), checked at the next review.
 * - **Looking back at an older note**: judging a past self from a distance, where a fresh write-up
 *   would be rewritten by hindsight.
 */
object Weekly {
    /** Field reports a week needs before it has enough to look back on. */
    const val MIN_REPORTS = 2
    /** Sightings (in others or yourself) before the blind spot is shown: fewer is noise. */
    const val MIN_SIGHTINGS = 10
    /** Sightings in each period before its share is compared. */
    const val MIN_PERIOD_SIGHTINGS = 5
    /** Times a concept must be spotted in others before it's named as a blind spot. */
    const val MIN_CONCEPT_SPOTTED = 3
    const val RECENT_DAYS = 28L
    /** An old note is brought back from about a month ago, and never from the last two weeks. */
    const val LOOK_BACK_DAYS = 28L
    const val LOOK_BACK_MIN_DAYS = 14L
    const val PLAN_MAX = 200

    /** The week a review today looks back on: this week on Sunday, otherwise last week. */
    fun weekFor(today: LocalDate): LocalDate =
        Progress.weekOf(today).let { if (today.dayOfWeek == DayOfWeek.SUNDAY) it else it.minusWeeks(1) }

    /** The field reports from the week starting [week] (its Monday). */
    fun reportsIn(state: AppState, week: LocalDate): List<Entry> {
        val end = week.plusDays(6)
        return state.liveEntries.filter { !it.day.isBefore(week) && !it.day.isAfter(end) }
    }

    /** The week whose review is waiting today, if any: one with enough reports, not yet reviewed. */
    fun due(state: AppState, today: LocalDate): LocalDate? {
        val week = weekFor(today)
        return week.takeIf { it !in state.reflections && reportsIn(state, it).size >= MIN_REPORTS }
    }

    /**
     * The if-then plan for the week [today] is in: from the review of the week before, or from this
     * week's own review once it's done on Sunday (it's for the week starting the next day).
     */
    fun activePlan(state: AppState, today: LocalDate): Pair<LocalDate, Reflection>? {
        val week = Progress.weekOf(today)
        return listOf(week, week.minusWeeks(1)).firstNotNullOfOrNull { w ->
            state.reflections[w]?.takeIf { it.plan.isNotBlank() }?.let { w to it }
        }
    }

    /** The plan a review of [week] checks on: the one made at the review before it. */
    fun previousPlan(state: AppState, week: LocalDate): String? =
        state.reflections[week.minusWeeks(1)]?.plan?.takeIf { it.isNotBlank() }

    fun blindSpot(state: AppState, today: LocalDate): BlindSpot? {
        val sightings = state.liveEntries.filter { (it.mode == Mode.SPOTTED || it.mode == Mode.MYSELF) && !it.day.isAfter(today) }
        val others = sightings.count { it.mode == Mode.SPOTTED }
        val self = sightings.size - others
        if (sightings.size < MIN_SIGHTINGS) return null
        val since = today.minusDays(RECENT_DAYS - 1)
        fun percent(list: List<Entry>): Int? =
            if (list.size < MIN_PERIOD_SIGHTINGS) null else (list.count { it.mode == Mode.MYSELF } * 100.0 / list.size).roundToInt()
        val (recent, earlier) = sightings.partition { !it.day.isBefore(since) }
        val byConcept = sightings.groupBy { it.conceptId }.mapValues { (_, l) -> l.count { it.mode == Mode.SPOTTED } to l.count { it.mode == Mode.MYSELF } }
        // Never caught in yourself first, then the widest gap, then the most spotted.
        val top = byConcept.entries
            .filter { (_, c) -> c.first >= MIN_CONCEPT_SPOTTED && c.first > c.second * 2 }
            .sortedWith(compareBy({ it.value.second != 0 }, { -(it.value.first - it.value.second) }, { -it.value.first }, { it.key }))
            .firstOrNull()
        return BlindSpot(
            others, self, percent(recent), percent(earlier),
            top?.key, top?.value?.first ?: 0, top?.value?.second ?: 0,
        )
    }

    /**
     * The moment from [week] to learn from: something you tried that backfired, then one that went
     * mixed, then a time you caught yourself, then anything else you tried. Newest first within each.
     * Sightings in others aren't yours to change, so they aren't picked.
     */
    fun moment(state: AppState, week: LocalDate): Entry? {
        fun rank(e: Entry): Int? = when {
            e.mode == Mode.USED && e.outcome == Outcome.BACKFIRED -> 0
            e.mode == Mode.USED && e.outcome == Outcome.MIXED -> 1
            e.mode == Mode.MYSELF -> 2
            e.mode == Mode.USED && e.outcome == null -> 3
            e.mode == Mode.USED -> 4
            else -> null
        }
        return reportsIn(state, week)
            .mapNotNull { e -> rank(e)?.let { it to e } }
            .sortedWith(compareBy({ it.first }, { -it.second.day.toEpochDay() }, { -it.second.createdAt }, { it.second.id }))
            .firstOrNull()?.second
    }

    /**
     * An older note to judge again: the one written closest to [LOOK_BACK_DAYS] ago (at least
     * [LOOK_BACK_MIN_DAYS] ago), the longer one on a tie, not one an earlier review brought back.
     */
    fun lookBack(state: AppState, today: LocalDate): Entry? {
        val seen = state.reflections.values.mapNotNullTo(HashSet()) { it.noteId }
        return state.liveEntries
            .filter { it.note.isNotBlank() && it.id !in seen && ChronoUnit.DAYS.between(it.day, today) >= LOOK_BACK_MIN_DAYS }
            .minWithOrNull(compareBy({ abs(ChronoUnit.DAYS.between(it.day, today) - LOOK_BACK_DAYS) }, { -it.note.length }, { it.id }))
    }
}
