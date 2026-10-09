package com.rajan.meditationtimer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WelcomeGuideTest {
    @Test
    fun `welcome shows only to someone who has never used the app`() {
        for (done in listOf(false, true)) for (saved in listOf(false, true)) for (history in listOf(false, true)) {
            assertEquals("done=$done saved=$saved history=$history", !done && !saved && !history, Welcome.needed(done, saved, history))
        }
    }

    @Test
    fun `more experience starts longer, with a goal to match, and beginners start small`() {
        val all = Experience.entries
        assertEquals(5, Experience.NEW.minutes)
        assertTrue(all.zipWithNext().all { (a, b) -> a.minutes < b.minutes && a.weeklyGoal <= b.weeklyGoal })
        for (e in all) {
            assertTrue(e.weeklyGoal in 1..7)
            // What the answer does is said on the answer itself.
            assertTrue(e.detail, e.detail.contains("${e.minutes} minutes") && e.detail.contains("${e.weeklyGoal} days a week"))
        }
    }

    @Test
    fun `choosing a time sets a reminder tied to that habit, and later changes nothing`() {
        val before = Reminder(enabled = false, minuteOfDay = 7 * 60, cue = "")
        assertEquals(Reminder(true, 21 * 60 + 30, "Before bed"), Welcome.reminderFor(SitTime.BED, before))
        assertEquals(before, Welcome.reminderFor(SitTime.LATER, before))
        val existing = Reminder(true, 6 * 60, "After tea")
        assertEquals(existing, Welcome.reminderFor(SitTime.LATER, existing))
        for (t in SitTime.entries.filter { it.minuteOfDay != null }) {
            val r = Welcome.reminderFor(t, before)
            assertTrue(r.minuteOfDay in 0 until 24 * 60)
            assertTrue(t.detail, t.detail.endsWith(r.timeLabel))
            // The notification reads as a plan: "After waking up" / "That's your cue: sit for 5 minutes."
            val (title, text) = r.message(5)
            assertEquals(t.cue, title)
            assertEquals("That's your cue: sit for 5 minutes.", text)
        }
        assertEquals("No reminder for now", SitTime.LATER.detail)
    }

    @Test
    fun `guide topics are short, distinct and say what's inside`() {
        val topics = Guide.topics("Downloads/x.csv")
        assertEquals(topics.size, topics.map { it.title }.toSet().size)
        for (t in topics) {
            assertTrue(t.title, t.points.isNotEmpty() && t.points.size <= 6)
            assertTrue(t.title, t.summary.length <= 50)
            // A point is a glance, not an essay: phone-screen sized.
            for (p in t.points) assertTrue("${t.title}: $p", p.length <= 330)
        }
        // The first thing a beginner needs comes first.
        assertEquals("How to sit", topics.first().title)
    }

    @Test
    fun `guide stays true to the app`() {
        val text = Guide.topics("Downloads/Meditation Timer/meditation-history.csv").flatMap { it.points + it.summary }.joinToString("\n")
        for (pattern in BreathPattern.ALL) assertTrue("Breathe topic names ${pattern.name}", text.contains(pattern.name))
        assertTrue(text.contains("${SessionClock.END_CONFIRM_MS / 1000} seconds to change your mind"))
        assertEquals(60, MIN_LOGGED_SEC) // "Sits under a minute aren't saved"
        assertTrue(text.contains("under a minute"))
        assertEquals(1, REST_DAYS_PER_WEEK) // "forgives one missed day each week"
        assertTrue(text.contains("one missed day each week"))
        assertTrue(text.contains("Downloads/Meditation Timer/meditation-history.csv"))
        // On phones without the automatic copy, the guide doesn't promise one.
        val older = Guide.topics(null).flatMap { it.points }.joinToString("\n")
        assertFalse(older.contains("saved automatically"))
        assertNull(Guide.topics(null).flatMap { it.points }.firstOrNull { it.contains("null") })
        // How to sit is the same in the welcome and the guide.
        for ((title, _) in Welcome.howToSit) assertTrue(text.contains(title))
    }
}
