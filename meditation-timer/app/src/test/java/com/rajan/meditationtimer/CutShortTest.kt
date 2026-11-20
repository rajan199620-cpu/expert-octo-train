package com.rajan.meditationtimer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A sit Android cut short: what gets logged for it, what the Sit screen says, and the stored form. */
class CutShortTest {
    private val start = 1_790_000_000_000L

    @Test
    fun `the minutes sat are logged like an early end, and only once`() {
        val progress = SitProgress(start, plannedSec = 1200, satMs = 840_500, before = 2, noticed = 5)
        val record = progress.record(emptyList())!!
        assertEquals(SessionRecord(start, 1200, 840, before = 2, noticed = 5), record)
        assertFalse(record.completed)
        // Already in the log (it finished; the app was closed before it could tidy up): nothing more.
        assertNull(progress.record(listOf(SessionRecord(start + 20_000, 1200, 1200))))
        // A different sit in another minute doesn't count as the same one.
        assertEquals(record, progress.record(listOf(SessionRecord(start - 3_600_000, 600, 600))))
    }

    @Test
    fun `under a minute logs nothing, and sitting time never runs past the plan`() {
        assertNull(SitProgress(start, 1200, satMs = 59_999).record(emptyList()))
        assertEquals(60, SitProgress(start, 1200, satMs = 60_000).record(emptyList())!!.actualSec)
        assertEquals(1200, SitProgress(start, 1200, satMs = 5_000_000).satSec)
        assertEquals(0, SitProgress(start, 1200, satMs = -5).satSec)
    }

    @Test
    fun `the note says what happened and what was logged`() {
        val logged = CutShort(840, 1200)
        assertTrue(logged.logged)
        assertEquals(
            "It stopped after 14 of its 20 minutes: Android closed the app, probably to save battery. " +
                "The 14 minutes are in your history.",
            logged.message(),
        )
        assertTrue(CutShort(60, 600).message().contains("The 1 minute is in your history."))
        val early = CutShort(40, 1200)
        assertFalse(early.logged)
        assertTrue(early.message().contains("first minute"))
        assertTrue(early.message().contains("nothing to log"))
        assertEquals(CutShort(840, 1200), CutShort.of(SitProgress(start, 1200, 840_900)))
    }

    @Test
    fun `stored forms round-trip and damaged ones are ignored`() {
        val progress = SitProgress(start, 1200, 840_500, 3, -1)
        assertEquals(progress, SitProgress.decode(progress.encode()))
        val cut = CutShort(840, 1200)
        assertEquals(cut, CutShort.decode(cut.encode()))
        for (bad in listOf(null, "", "1,2", "a,b,c,d,e", "$start,0,100,0,0", "$start,1200,-1,0,0")) {
            assertNull(bad, SitProgress.decode(bad))
        }
        for (bad in listOf(null, "", "x,1200", "840,0", "1,2,3")) assertNull(bad, CutShort.decode(bad))
    }
}
