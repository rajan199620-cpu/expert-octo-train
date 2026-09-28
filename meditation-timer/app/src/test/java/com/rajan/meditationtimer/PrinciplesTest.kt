package com.rajan.meditationtimer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class PrinciplesTest {
    private val start = Principles.START

    @Test
    fun courseStartsAtLessonOneAndAdvancesDaily() {
        assertEquals(Principles.ALL[0], Principles.forDate(start))
        assertEquals(Principles.ALL[1], Principles.forDate(start.plusDays(1)))
        assertEquals(1L, Principles.dayNumber(start))
        assertEquals(31L, Principles.dayNumber(start.plusDays(30)))
    }

    @Test
    fun noRepeatWithinOneFullCourse() {
        val n = Principles.ALL.size
        val window = (0 until n).map { Principles.forDate(start.plusDays(it.toLong() + 17)) }
        assertEquals(n, window.toSet().size)
        // The course then begins again.
        assertEquals(Principles.forDate(start), Principles.forDate(start.plusDays(n.toLong())))
    }

    @Test
    fun archiveIsNewestFirstAndNeverRevealsTheFuture() {
        val today = start.plusDays(4)
        val archive = Principles.archive(today)
        assertEquals((0L..4L).map { today.minusDays(it) }, archive.map { it.first })
        assertEquals(Principles.forDate(today), archive.first().second)
        // Long after the start it holds exactly one full course, each principle once.
        val later = Principles.archive(start.plusDays(500))
        assertEquals(Principles.ALL.size, later.size)
        assertEquals(Principles.ALL.toSet(), later.map { it.second }.toSet())
    }

    @Test
    fun clockBeforeStartStillWorks() {
        val before = start.minusDays(3)
        assertEquals(1, Principles.archive(before).size)
        assertEquals(1L, Principles.dayNumber(before))
        Principles.forDate(before) // must not throw
    }

    @Test
    fun everyPrincipleIsCompleteUniqueAndSourced() {
        assertTrue("course should last at least two months", Principles.ALL.size >= 60)
        assertEquals(Principles.ALL.size, Principles.ALL.map { it.title }.toSet().size)
        for (p in Principles.ALL) {
            listOf(p.theme, p.title, p.body, p.practice, p.finding, p.source).forEach {
                assertTrue("blank field in \"${p.title}\"", it.isNotBlank())
            }
            // Source format: Authors · Journal · Year
            val year = p.source.substringAfterLast("· ").trim().toIntOrNull()
            assertTrue("bad source for \"${p.title}\": ${p.source}", year != null && year in 1980..2026)
        }
    }
}
