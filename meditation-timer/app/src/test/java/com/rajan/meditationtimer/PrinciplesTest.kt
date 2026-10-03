package com.rajan.meditationtimer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class PrinciplesTest {
    private val today = LocalDate.of(2026, 10, 10)
    private fun days(vararg back: Long) = back.map { today.minusDays(it) }.toSet()

    @Test
    fun lessonsAdvanceOnlyOnDaysYouSat() {
        assertEquals(0, Principles.lessonIndex(emptySet(), today))
        // Sat on 3 earlier days (with gaps): lesson 4 today; gaps don't skip lessons.
        assertEquals(3, Principles.lessonIndex(days(1, 5, 9), today))
        // Sitting today doesn't change today's lesson; it counts from tomorrow.
        assertEquals(3, Principles.lessonIndex(days(0, 1, 5, 9), today))
        assertEquals(4, Principles.lessonIndex(days(0, 1, 5, 9), today.plusDays(1)))
    }

    @Test
    fun courseRepeatsAfterTheLastLesson() {
        val n = Principles.ALL.size
        assertEquals(Principles.ALL[0], Principles.forLesson(0))
        assertEquals(Principles.ALL[0], Principles.forLesson(n))
        assertEquals(n, (0 until n).map { Principles.forLesson(it) }.toSet().size)
        assertEquals(1, Principles.lessonNumber(0))
    }

    @Test
    fun archiveIsNewestFirstAndNeverRevealsLaterLessons() {
        assertEquals(listOf(4, 3, 2, 1, 0), Principles.archive(4).map { it.first })
        assertEquals(listOf(0), Principles.archive(0).map { it.first })
        val later = Principles.archive(500)
        assertEquals(Principles.ALL.size, later.size)
        assertEquals(Principles.ALL.toSet(), later.map { it.second }.toSet())
    }

    @Test
    fun everyPrincipleIsCompleteUniqueSourcedAndGraded() {
        assertTrue("course should last at least two months", Principles.ALL.size >= 60)
        assertEquals(Principles.ALL.size, Principles.ALL.map { it.title }.toSet().size)
        for (p in Principles.ALL) {
            listOf(p.theme, p.title, p.body, p.practice, p.finding, p.source).forEach {
                assertTrue("blank field in \"${p.title}\"", it.isNotBlank())
            }
            val year = p.source.substringAfterLast("· ").trim().toIntOrNull()
            assertTrue("bad source for \"${p.title}\": ${p.source}", year != null && year in 1980..2026)
            p.evidence // throws for an unclassified source
        }
        assertEquals(Evidence.THEORY, Principles.ALL.first { "Creswell" in it.source }.evidence)
        assertEquals(Evidence.TRIAL, Principles.ALL.first { it.source.startsWith("Lindsay et al.") }.evidence)
        assertEquals(Evidence.SMALL, Principles.ALL.first { it.source.startsWith("Zeidan") }.evidence)
    }
}
