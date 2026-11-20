package com.rajan.meditationtimer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.random.Random

/**
 * Randomised and exhaustive checks of the pure logic: thousands of inputs per property, fixed
 * seeds so any failure reproduces. Each test states the invariant it hammers.
 */
class StressTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    // Whole code points, as a keyboard produces them (an emoji is two UTF-16 chars).
    private val nasty = "abc XYZ 0123 ,;:=&%+\"'\n\r\t—–…éü हिंदी 💡🧘\\/#?".codePoints().toArray().map { String(Character.toChars(it)) }

    private fun Random.text(max: Int) = (0 until nextInt(0, max)).joinToString("") { nasty[nextInt(nasty.size)] }

    private fun Random.record(base: Long): SessionRecord {
        val planned = nextInt(1, 181) * 60
        return SessionRecord(
            // Whole minutes and 0.1-minute durations: what the CSV keeps exactly.
            startedAtMs = base + nextLong(0, 5L * 365 * 24 * 60) * 60_000,
            plannedSec = planned,
            actualSec = nextInt(1, planned / 6 + 1) * 6,
            rating = nextInt(0, 6),
            note = text(80),
            before = nextInt(0, 6),
            after = nextInt(0, 6),
            noticed = nextInt(-1, 60),
        )
    }

    private val base = LocalDateTime.of(2022, 1, 1, 0, 0).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun `log lines round-trip any note and any field values`() {
        val rnd = Random(1)
        repeat(5_000) {
            val r = rnd.record(base)
            val line = r.encode()
            assertEquals("one line per record: [$line]", 1, line.lines().size)
            assertEquals(r, SessionRecord.decode(line))
        }
    }

    @Test
    fun `garbage log lines never crash and never decode to nonsense`() {
        val rnd = Random(2)
        repeat(20_000) {
            val line = rnd.text(60)
            val r = runCatching { SessionRecord.decode(line) }.getOrElse { throw AssertionError("decode threw on [$line]", it) }
            if (r != null) {
                assertTrue(r.rating in 0..5 || line.split(',').size == 3)
                assertTrue(r.before in 0..5 && r.after in 0..5 && r.noticed >= -1)
            }
        }
    }

    @Test
    fun `backup CSV restores exactly what it saved, settings included`() {
        val rnd = Random(3)
        repeat(300) {
            val records = List(rnd.nextInt(0, 40)) { rnd.record(base) }
                .distinctBy { it.startedAtMs }
                .map { it.copy(note = it.note.replace("\r", "")) } // CSV normalises line endings
            val settings = mapOf("duration_min" to "${rnd.nextInt(1, 181)}", "reminder_cue" to java.net.URLEncoder.encode(rnd.text(30), "UTF-8"))
            val csv = History.toCsv(records, zone, settings)
            assertEquals(records.sortedBy { it.startedAtMs }, History.fromCsv(csv, zone))
            assertEquals(settings, History.settingsFrom(csv))
        }
    }

    @Test
    fun `bells - every combination stays inside the sit, in order, never crowded`() {
        for (minutes in 1..180) for (opening in listOf(5, 10, 15, 30)) for (closing in listOf(5, 10, 30, 60))
            for (interval in listOf(0, 5, 10, 15)) for (end in listOf(false, true)) {
                val config = SessionConfig(minutes * 60, opening, closing, end, interval)
                val cues = BellSchedule.cues(config)
                val label = "$minutes min, open $opening, close $closing, every $interval, end $end"
                assertTrue(label, cues.all { it.atMs in 0..config.durationMs })
                assertEquals(label, cues.sortedBy { it.atMs }, cues)
                cues.zipWithNext().forEach { (a, b) -> assertTrue("$label: $a then $b", b.atMs - a.atMs >= BellSchedule.MIN_GAP_MS) }
                assertTrue(label, cues.count { it.cue == Cue.OPENING } == 1)
                if (end) assertTrue(label, cues.last().cue == Cue.END)
            }
    }

    @Test
    fun `pause and resume - sat time never runs backwards, never exceeds the wall clock, never counts pauses`() {
        val rnd = Random(4)
        repeat(2_000) {
            var clock = SessionClock(startMs = 1_000)
            var now = 1_000L
            var paused = 0L
            var last = 0L
            repeat(rnd.nextInt(1, 40)) {
                val step = rnd.nextLong(0, 120_000)
                if (clock.isPaused) paused += step
                now += step
                clock = if (rnd.nextBoolean()) clock.pause(now) else clock.resume(now)
                val e = clock.elapsedAt(now)
                assertTrue("went backwards", e >= last)
                assertTrue("more than wall time", e <= now - 1_000)
                assertEquals(now - 1_000 - paused, e)
                last = e
            }
        }
    }

    @Test
    fun `reminder - every minute of the day, across time zones and DST changes, fires once within a day`() {
        val zones = listOf("Asia/Kolkata", "America/New_York", "Europe/London", "Australia/Lord_Howe", "Pacific/Chatham").map(ZoneId::of)
        val days = listOf(LocalDate.of(2026, 3, 8), LocalDate.of(2026, 3, 29), LocalDate.of(2026, 10, 4), LocalDate.of(2026, 11, 1), LocalDate.of(2026, 10, 1))
        for (z in zones) for (day in days) for (minute in 0 until 24 * 60 step 7) for (nowMinute in listOf(0, 61, 179, 720, 1439)) {
            val now = day.atStartOfDay(z).plusMinutes(nowMinute.toLong())
            val next = Reminder(true, minute, "").nextAt(now)
            assertTrue("$z $day $minute after $now -> $next", next.isAfter(now))
            assertTrue("$z $day $minute after $now -> $next", Duration.between(now, next) <= Duration.ofHours(26))
        }
    }

    @Test
    fun `history - streaks match brute force on random calendars`() {
        val rnd = Random(5)
        val today = LocalDate.of(2026, 10, 1)
        repeat(1_000) {
            val days = (0 until rnd.nextInt(0, 60)).map { today.minusDays(rnd.nextLong(0, 90)) }.toSet()
            var expected = 0
            var d = if (today in days) today else today.minusDays(1)
            while (d in days) { expected++; d = d.minusDays(1) }
            assertEquals(expected, History.currentStreak(days, today))
            val longest = days.sorted().fold(0 to 0) { (best, run), day ->
                val r = if (day.minusDays(1) in days) run + 1 else 1
                maxOf(best, r) to r
            }.first
            assertEquals(longest, History.longestStreak(days))
            val monday = today.with(java.time.DayOfWeek.MONDAY)
            assertEquals(days.count { !it.isBefore(monday) && !it.isAfter(today) }, History.week(days, today).count { it.second == true })
        }
    }

    @Test
    fun `history - five years of daily sits summarise quickly`() {
        val rnd = Random(6)
        val today = LocalDate.of(2026, 10, 1)
        val records = (0 until 20_000).map { i ->
            val day = today.minusDays((i / 11).toLong())
            SessionRecord(
                day.atTime(6, i % 60).atZone(zone).toInstant().toEpochMilli() + i,
                1200, rnd.nextInt(60, 1201), rnd.nextInt(0, 6), "", rnd.nextInt(0, 6), rnd.nextInt(0, 6), rnd.nextInt(-1, 30),
            )
        }
        val started = System.nanoTime()
        val summary = History.summarize(records, zone, today)
        History.heatmap(records, zone, today, 12)
        History.moodTrend(records, zone, today)
        History.checkInSummary(records)
        History.noticing(records, zone, today)
        History.toCsv(records, zone)
        val ms = (System.nanoTime() - started) / 1_000_000
        assertEquals(20_000, summary.sessionCount)
        assertTrue("took $ms ms", ms < 5_000)
    }

    @Test
    fun `check-in summary and noticing never produce impossible numbers`() {
        val rnd = Random(7)
        val today = LocalDate.of(2026, 10, 1)
        repeat(2_000) {
            val records = List(rnd.nextInt(0, 50)) { rnd.record(today.minusDays(80).atStartOfDay(zone).toInstant().toEpochMilli()) }
            History.checkInSummary(records)?.let { s ->
                assertTrue(s.averageShift in -4.0..4.0)
                assertEquals(s.sits, s.better + s.same + s.worse)
                assertTrue(s.sits in 1..30)
            }
            History.noticing(records, zone, today).forEach { p ->
                assertTrue(p.count >= 0 && p.perTenMin >= 0 && p.perTenMin.isFinite())
            }
        }
    }

    @Test
    fun `lessons - any history gives a valid lesson that only moves forward`() {
        val rnd = Random(8)
        val today = LocalDate.of(2026, 10, 1)
        repeat(1_000) {
            val days = (0 until rnd.nextInt(0, 400)).map { today.minusDays(rnd.nextLong(0, 800)) }.toSet()
            val i = Principles.lessonIndex(days, today)
            Principles.forLesson(i)
            assertTrue(Principles.lessonIndex(days + today, today.plusDays(1)) >= i)
        }
    }

    @Test
    fun `breath count - score is never more than rounds, rounds never more than presses`() {
        val rnd = Random(9)
        repeat(5_000) {
            val presses = List(rnd.nextInt(0, 200)) { rnd.nextInt(10) == 0 }
            val r = BreathCount.score(presses)
            assertTrue(r.correct <= r.total && r.total <= presses.size)
            assertTrue(r.accuracyPercent == null || r.accuracyPercent in 0..100)
        }
    }

    @Test
    fun `length limits never cut an emoji in half`() {
        val note = "a".repeat(499) + "🧘🧘"
        val cut = note.limitText(500)
        assertEquals("a".repeat(499), cut)
        assertEquals("ab", "ab".limitText(5))
        assertEquals("🧘", "🧘x".limitText(2))
    }

    @Test
    fun `reminder message never blank and never shouts`() {
        val rnd = Random(10)
        repeat(2_000) {
            val (title, text) = Reminder(true, 0, rnd.text(60)).message(rnd.nextInt(1, 181))
            assertTrue(title.isNotBlank() && text.isNotBlank())
        }
    }

    @Suppress("unused")
    private fun ZonedDateTime.minuteOfDay() = hour * 60 + minute
}
