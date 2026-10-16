package com.rajan.meditationtimer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

class ReminderTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private fun at(h: Int, m: Int) = ZonedDateTime.of(2026, 10, 1, h, m, 0, 0, zone)

    @Test
    fun firesLaterTodayOrTomorrow() {
        val r = Reminder(true, 7 * 60 + 30, "")
        assertEquals(at(7, 30), r.nextAt(at(6, 0)))
        assertEquals(at(7, 30).plusDays(1), r.nextAt(at(7, 30)))
        assertEquals(at(7, 30).plusDays(1), r.nextAt(at(21, 0)))
    }

    @Test
    fun timeLabelIsTwelveHour() {
        assertEquals("7:05 am", Reminder(true, 7 * 60 + 5, "").timeLabel)
        assertEquals("12:00 pm", Reminder(true, 12 * 60, "").timeLabel)
        assertEquals("12:15 am", Reminder(true, 15, "").timeLabel)
        assertEquals("9:30 pm", Reminder(true, 21 * 60 + 30, "").timeLabel)
    }

    @Test
    fun messageUsesTheHabitAsTheCue() {
        assertEquals("After morning tea" to "That's your cue: sit for 10 minutes.", Reminder(true, 0, " after morning tea. ").message(10))
        assertEquals("Time to sit" to "Your usual 20 minutes, whenever you're ready.", Reminder(true, 0, "  ").message(20))
    }

    @Test
    fun skipsDaysYouHaveAlreadySat() {
        val today = LocalDate.of(2026, 10, 1)
        assertFalse(Reminder.shouldNotify(setOf(today), at(7, 0)))
        assertTrue(Reminder.shouldNotify(setOf(today.minusDays(1)), at(7, 0)))
    }
}
