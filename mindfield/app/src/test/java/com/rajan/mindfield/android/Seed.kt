package com.rajan.mindfield

import android.Manifest
import android.app.Application
import com.rajan.mindfield.core.Mode
import com.rajan.mindfield.core.Outcome
import com.rajan.mindfield.core.Quiz
import com.rajan.mindfield.core.Spacing
import com.rajan.mindfield.core.SystemZoneClock
import org.robolectric.Shadows.shadowOf
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import kotlin.random.Random

/** Shared set-up for the Android tests: a clean store, and weeks of realistic use. */
object Seed {
    val zone: ZoneId = ZoneId.systemDefault()

    fun clean(app: Application) {
        Store.resetForTests(app)
        GoogleSync.resetForTests(app)
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    fun at(day: LocalDate, hour: Int = 9, minute: Int = 0) {
        Store.clock = Clock.fixed(day.atTime(hour, minute).atZone(zone).toInstant(), zone)
    }

    fun backToNow() {
        Store.clock = SystemZoneClock
    }

    private val notes = listOf(
        "Saw it in a sale banner: ‘was ₹4,999, now ₹1,999’. I still wanted it.",
        "Caught myself doing this in the team meeting, then said so out loud.",
        "Tried it with my landlord. Mixed results, but he did agree to fix the tap.",
        "My cousin did exactly this at dinner. Everyone nodded along.",
        "Noticed it three times on the commute alone.",
        "",
        "Used it when asking for a deadline extension. Worked!",
        "Didn't come up, but I thought about it while scrolling.",
    )

    /** [days] of use ending yesterday: concepts, guesses, plans, notes and reviews. */
    fun weeks(days: Int = 35, seed: Int = 7, onboarded: Boolean = true) {
        val rnd = Random(seed)
        val today = LocalDate.now(zone)
        Store.settings { it.copy(onboarded = onboarded) }
        for (d in days downTo 1) {
            val day = today.minusDays(d.toLong())
            if (d % 9 == 4) continue // a missed day now and then
            at(day, 8)
            val c = Store.todayConcept()
            val right = rnd.nextInt(10) < 4
            Store.guess(c.id, if (right) c.predict.answer else (c.predict.answer + 1) % c.predict.options.size)
            if (rnd.nextInt(3) == 0) Store.plan(day, "At the team stand-up, I'll watch for it.")
            at(day, 21)
            val mode = when (rnd.nextInt(10)) {
                in 0..3 -> Mode.SPOTTED
                in 4..5 -> Mode.MYSELF
                in 6..8 -> Mode.USED
                else -> Mode.NOT_TODAY
            }
            Store.log(c.id, mode, notes[rnd.nextInt(notes.size)], if (mode == Mode.USED) Outcome.entries.random(rnd) else null, day)
            if (rnd.nextInt(4) == 0) Store.log(c.id, Mode.SPOTTED, notes[rnd.nextInt(notes.size)], day = day)
            // Do the day's reviews, mostly right.
            Spacing.due(Store.state.value, Store.library, day).forEach { card ->
                Store.grade(card.conceptId, rnd.nextInt(10) < 7)
            }
        }
        backToNow()
        Store.todayConcept()
        Store.flush()
    }

    /** The right option text for the first review question today, as the Review screen will ask it. */
    fun firstReviewAnswer(): String {
        val today = Store.today()
        val card = Spacing.due(Store.state.value, Store.library, today).first()
        val q = Quiz.question(card, Store.library, Store.state.value.unlocked.keys, seed = today.toEpochDay() * 31)
        return q.options[q.answer]
    }
}
