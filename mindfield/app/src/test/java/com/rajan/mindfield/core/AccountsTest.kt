package com.rajan.mindfield.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

class LinkGuardTest {
    @Test
    fun `work started before an unlink is stale, work started after is current`() {
        val guard = LinkGuard()
        val before = guard.ticket()
        assertTrue(guard.isCurrent(before))
        guard.unlink()
        assertFalse(guard.isCurrent(before))
        val after = guard.ticket()
        assertTrue(guard.isCurrent(after))
        guard.unlink()
        guard.unlink()
        assertFalse(guard.isCurrent(after))
    }

    @Test
    fun `stress - no ticket survives an unlink, from any thread`() {
        val guard = LinkGuard()
        val pool = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        val survived = AtomicInteger()
        repeat(8) { t ->
            pool.execute {
                start.await()
                val rnd = Random(t)
                repeat(20_000) {
                    val ticket = guard.ticket()
                    if (rnd.nextInt(10) == 0) {
                        guard.unlink()
                        if (guard.isCurrent(ticket)) survived.incrementAndGet()
                    }
                }
            }
        }
        start.countDown()
        pool.shutdown()
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS))
        assertEquals(0, survived.get())
    }
}

class LinkMessagesTest {
    @Test
    fun `reconnecting the same account only mentions restored notes`() {
        assertNull(LinkMessages.afterConnect("a@x.com", "a@x.com", 0))
        assertNull(LinkMessages.afterConnect(null, "a@x.com", 0))
        assertEquals("Restored 1 field note from Google Drive", LinkMessages.afterConnect(null, "a@x.com", 1))
        assertEquals("Restored 3 field notes from Google Drive", LinkMessages.afterConnect("a@x.com", "a@x.com", 3))
    }

    @Test
    fun `switching accounts says where backups now go and that the old backup stays`() {
        val m = LinkMessages.afterConnect("a@x.com", "b@y.com", 0)!!
        assertTrue(m, "b@y.com" in m && "a@x.com" in m && "stays" in m)
        val withNotes = LinkMessages.afterConnect("a@x.com", "b@y.com", 2)!!
        assertTrue(withNotes, "2 field notes" in withNotes && "b@y.com" in withNotes)
    }

    @Test
    fun `an account whose address Google didn't give never claims a switch`() {
        assertNull(LinkMessages.afterConnect("a@x.com", LinkMessages.UNKNOWN_ACCOUNT, 0))
        assertNull(LinkMessages.afterConnect(LinkMessages.UNKNOWN_ACCOUNT, "a@x.com", 0))
    }
}

/**
 * A model of the Google backup on several phones and two Google accounts, run through thousands
 * of random interleavings of writing, editing, deleting, connecting, background uploads, unlinking
 * and switching accounts. It follows the steps GoogleSync takes (download, merge, upload, mark
 * linked), with the real merge, the real backup file format and the real [LinkGuard].
 */
class MultiAccountTest {
    private val day0: LocalDate = LocalDate.of(2026, 10, 6)
    private val accounts = listOf("rajan@gmail.com", "rajan.work@example.com")
    private val concepts = TestLibrary.library.all.map { it.id }

    private enum class Kind { CONNECT, BACKUP }

    private class Job(val account: String, val ticket: Long, val kind: Kind) {
        var step = 0
        var remote: String? = null
    }

    private class Phone {
        var state = AppState()
        /** The account the card shows as linked. */
        var linked: String? = null
        /** The account the user picked since the last unlink; the only one this phone may upload to. */
        var chosen: String? = null
        /** The account Google hands out silently because it still holds a grant for it. */
        var granted: String? = null
        /** The last account this phone used, kept across unlinks, to count switches. */
        var last: String? = null
        val guard = LinkGuard()
        val jobs = ArrayList<Job>()
        val everSeen = HashSet<String>()
    }

    private class World(seed: Int) {
        val rnd = Random(seed)
        val drive = HashMap<String, String>()
        var clock = 1L
        var uploads = 0
        var staleWorkStopped = 0
        var switches = 0
    }

    private fun write(w: World, p: Phone, tag: String) {
        val id = "$tag-${w.clock}"
        val day = day0.plusDays(w.rnd.nextLong(0, 40))
        val mode = Mode.entries.random(w.rnd)
        val e = Entry(id, concepts.random(w.rnd), day, mode, "note $id ✨ \"quoted\"", if (mode == Mode.USED) Outcome.WORKED else null, w.clock, w.clock)
        p.state = p.state.upsert(e)
        w.clock++
    }

    private fun editOrDelete(w: World, p: Phone) {
        val live = p.state.liveEntries
        if (live.isEmpty()) return
        val e = live.random(w.rnd)
        val changed = if (w.rnd.nextBoolean()) e.copy(note = e.note + " (edited)", updatedAt = w.clock) else e.copy(deleted = true, note = "", updatedAt = w.clock)
        p.state = p.state.upsert(changed)
        w.clock++
    }

    private fun connect(w: World, p: Phone, wanted: String) {
        // Google skips the chooser while it still holds a grant: that is why Unlink must revoke it.
        val account = p.granted ?: wanted
        if (p.last != null && p.last != account) w.switches++
        p.last = account
        p.granted = account
        p.chosen = account
        p.jobs += Job(account, p.guard.ticket(), Kind.CONNECT)
    }

    private fun unlink(w: World, p: Phone, revokeWorks: Boolean) {
        p.guard.unlink()
        p.linked = null
        p.chosen = null
        if (revokeWorks) p.granted = null
        // A scheduled upload that hasn't started is cancelled; work already running must stop itself.
        p.jobs.removeAll { it.kind == Kind.BACKUP && it.step == 0 && w.rnd.nextBoolean() }
    }

    /** One step of one job, exactly where GoogleSync checks its ticket. */
    private fun advance(w: World, p: Phone, job: Job) {
        when (job.step) {
            0 -> job.remote = w.drive[job.account]
            1 -> {
                if (!p.guard.isCurrent(job.ticket)) return stop(w, p, job)
                job.remote?.let { p.state = Sync.merge(p.state, Codec.decode(it)) }
            }
            2 -> {
                if (!p.guard.isCurrent(job.ticket)) return stop(w, p, job)
                assertEquals("uploaded to an account that isn't the one chosen since the last unlink", p.chosen, job.account)
                // GoogleSync uploads the store as it is now, which may include notes written mid-sync.
                w.drive[job.account] = Codec.encode(p.state, w.clock)
                w.uploads++
            }
            3 -> {
                if (!p.guard.isCurrent(job.ticket)) return stop(w, p, job)
                if (job.kind == Kind.CONNECT) p.linked = job.account
                p.jobs.remove(job)
                return
            }
        }
        job.step++
    }

    private fun stop(w: World, p: Phone, job: Job) {
        p.jobs.remove(job)
        w.staleWorkStopped++
    }

    private fun checkPhone(p: Phone) {
        val ids = p.state.entries.mapTo(HashSet()) { it.id }
        assertTrue("a note vanished from a phone", ids.containsAll(p.everSeen))
        p.everSeen += ids
        p.linked?.let { assertEquals("linked to an account that wasn't chosen", p.chosen, it) }
    }

    private fun data(s: AppState) = s.copy(settings = Settings())

    @Test
    fun `stress - phones and accounts never lose a note or upload to an unlinked account`() {
        var totalUploads = 0
        var totalStopped = 0
        var totalSwitches = 0
        repeat(300) { run ->
            val w = World(run)
            val phones = List(3) { Phone() }
            repeat(250) {
                val p = phones.random(w.rnd)
                when (w.rnd.nextInt(100)) {
                    in 0..24 -> write(w, p, "p${phones.indexOf(p)}")
                    in 25..34 -> editOrDelete(w, p)
                    in 35..44 -> connect(w, p, accounts.random(w.rnd))
                    in 45..54 -> if (p.linked != null) p.jobs += Job(p.linked!!, p.guard.ticket(), Kind.BACKUP)
                    in 55..61 -> unlink(w, p, revokeWorks = w.rnd.nextInt(5) != 0)
                    else -> p.jobs.randomOrNull(w.rnd)?.let { advance(w, p, it) }
                }
                phones.forEach { checkPhone(it) }
            }
            // Let everything finish, then have every phone link to one account and sync twice.
            phones.forEach { p -> while (p.jobs.isNotEmpty()) advance(w, p, p.jobs.first()) }
            val settle = accounts.random(w.rnd)
            repeat(2) {
                phones.forEach { p ->
                    unlink(w, p, revokeWorks = true)
                    connect(w, p, settle)
                    while (p.jobs.isNotEmpty()) advance(w, p, p.jobs.first())
                    checkPhone(p)
                }
            }
            val backup = data(Codec.decode(w.drive.getValue(settle)))
            phones.forEachIndexed { i, p ->
                assertEquals("run $run: phone $i disagrees with the backup", backup.entries.sortedBy { it.id }, p.state.entries.sortedBy { it.id })
                assertEquals(backup.assignments, p.state.assignments)
            }
            totalUploads += w.uploads
            totalStopped += w.staleWorkStopped
            totalSwitches += w.switches
        }
        // The scenarios really exercised the risky paths.
        assertTrue("uploads $totalUploads", totalUploads > 1000)
        assertTrue("stale work stopped $totalStopped", totalStopped > 100)
        assertTrue("account switches $totalSwitches", totalSwitches > 100)
    }

    @Test
    fun `unlinking mid-sync and connecting another account never sends the journal to the first one`() {
        val w = World(1)
        val p = Phone()
        write(w, p, "mine")
        connect(w, p, accounts[0])
        val first = p.jobs.single()
        advance(w, p, first) // downloading from the first account when you unlink
        unlink(w, p, revokeWorks = true)
        connect(w, p, accounts[1])
        while (p.jobs.isNotEmpty()) advance(w, p, p.jobs.first())
        assertNull("nothing reached the account you unlinked", w.drive[accounts[0]])
        assertEquals(accounts[1], p.linked)
        assertEquals(1, Codec.decode(w.drive.getValue(accounts[1])).liveEntries.size)
    }

    @Test
    fun `switching accounts keeps every note and deletion, and leaves the old backup as it was`() {
        val w = World(2)
        val p = Phone()
        repeat(20) { write(w, p, "a") }
        connect(w, p, accounts[0])
        while (p.jobs.isNotEmpty()) advance(w, p, p.jobs.first())
        val oldBackup = w.drive.getValue(accounts[0])
        editOrDelete(w, p)
        editOrDelete(w, p)
        // The second account already holds an older backup from another phone.
        val other = Phone()
        repeat(5) { write(w, other, "b") }
        connect(w, other, accounts[1])
        while (other.jobs.isNotEmpty()) advance(w, other, other.jobs.first())

        unlink(w, p, revokeWorks = true)
        connect(w, p, accounts[1])
        while (p.jobs.isNotEmpty()) advance(w, p, p.jobs.first())

        assertEquals(oldBackup, w.drive.getValue(accounts[0]))
        val merged = Codec.decode(w.drive.getValue(accounts[1]))
        assertEquals(25, merged.entries.size)
        assertEquals(p.state.entries.filter { it.deleted }.map { it.id }.toSet(), merged.entries.filter { it.deleted }.map { it.id }.toSet())
        assertEquals(accounts[1], p.linked)
    }

    @Test
    fun `if Google can't be told about an unlink, connecting again reuses the same account and says nothing about a switch`() {
        val w = World(3)
        val p = Phone()
        connect(w, p, accounts[0])
        while (p.jobs.isNotEmpty()) advance(w, p, p.jobs.first())
        unlink(w, p, revokeWorks = false)
        connect(w, p, accounts[1])
        assertEquals(accounts[0], p.jobs.single().account)
        assertNull(LinkMessages.afterConnect(accounts[0], accounts[0], 0))
    }
}

/** Adding concepts to the library must never disturb people already using the app. */
class LibraryUpgradeTest {
    private val now = TestLibrary.library

    /** Concepts added in October 2026 from five books (see README); everything else was already there. */
    private val fromFiveBooks = setOf(
        "focal-is-causal", "implicit-egotism", "moral-licensing", "law-of-small-numbers", "affect-heuristic",
        "outcome-bias", "trusting-intuition", "consider-the-opposite", "behavioural-priming",
        "underestimating-compliance", "forced-teaming", "worries-rarely-happen", "money-and-happiness",
        "halo-effect", "lie-detection-myth", "charm-that-fades", "romeo-and-juliet-effect",
        "formulas-beat-judgement", "denominator-neglect", "certainty-effect", "narrow-framing",
        "less-is-better", "opportunity-cost-neglect", "premortem", "round-number-goals", "panic-myth",
        "hidden-profiles", "broken-windows", "reflected-glory",
    )

    /** Concepts added after that, from Quiet and The Man Who Mistook His Wife for a Hat (see README). */
    private val fromTwoMoreBooks = setOf(
        "multitasking-myth", "left-right-brain-myth", "face-blindness", "weak-nose-myth", "memory-recording-myth",
        "acting-extraverted", "personality-type-myth", "single-gene-myth", "venting-myth", "grief-stages-myth",
        "feelings-outlast-memory", "ten-thousand-hours", "babble-effect", "social-facilitation", "open-plan-offices",
    )

    private val added = fromFiveBooks + fromTwoMoreBooks

    /** The library as people first had it (151 concepts), and as it was after the five books (180). */
    private val original = Library(Library.interleave(now.all.filter { it.id !in added }))
    private val afterFiveBooks = Library(Library.interleave(now.all.filter { it.id !in fromTwoMoreBooks }))

    /** Each earlier library, with the concepts that arrived after it. */
    private val upgrades = listOf(original to added, afterFiveBooks to fromTwoMoreBooks)
    private val start: LocalDate = LocalDate.of(2026, 10, 2)

    /** [days] of use on [lib], with notes, guesses and reviews, as a phone would hold it. */
    private fun userAfter(days: Int, seed: Int, lib: Library): AppState {
        val rnd = Random(seed)
        var s = AppState()
        for (d in 0 until days) {
            val day = start.plusDays(d.toLong())
            s = Curriculum.assign(day, lib, s, d.toLong())
            val id = s.assignments.getValue(day).conceptId
            if (rnd.nextInt(3) > 0) s = s.upsert(Entry("e$d", id, day, Mode.entries.random(rnd), "n", null, d.toLong(), d.toLong()))
            s = s.copy(guesses = s.guesses + (id to Guess(rnd.nextInt(3), d.toLong())))
            for (card in Spacing.due(s, lib, day)) {
                val c = s.cards[card.conceptId] ?: card
                s = s.copy(cards = s.cards + (card.conceptId to Spacing.grade(c, rnd.nextInt(10) < 7, day)))
            }
        }
        return s
    }

    @Test
    fun `the library grew by exactly the new concepts`() {
        assertEquals(151, original.size)
        assertEquals(180, afterFiveBooks.size)
        assertEquals(180 + fromTwoMoreBooks.size, now.size)
        assertTrue(added.all { now.contains(it) })
        assertTrue(fromFiveBooks.intersect(fromTwoMoreBooks).isEmpty())
    }

    @Test
    fun `stress - an upgrade never changes a day already had, today, or the review schedule`() {
        for ((before, _) in upgrades) {
            for ((i, days) in listOf(1, 2, 9, 30, 100, 135, 136, 144, 145, 150, 151, 152, 180, 181, 195, 196, 400).withIndex()) {
                val s = userAfter(days, i, before)
                val today = start.plusDays(days - 1L)
                for ((day, a) in s.assignments) {
                    assertEquals(a.conceptId, Curriculum.pick(day, now, s))
                    assertTrue(Curriculum.assign(day, now, s, 0) === s)
                }
                assertEquals(Spacing.cards(s, before), Spacing.cards(s, now))
                for (card in Spacing.due(s, now, today.plusDays(1))) {
                    val q = Quiz.question(card, now, s.unlocked.keys, seed = days.toLong())
                    assertTrue(q.answer in q.options.indices)
                }
                assertEquals(s, Codec.decode(Codec.encode(s, 0)).copy(settings = s.settings))
            }
        }
    }

    @Test
    fun `people part-way through see every concept, old and new, before any repeat`() {
        for ((before, _) in upgrades) {
            // Part-way means before the old library ran out; past that, people were already revisiting.
            for ((i, days) in listOf(1, 50, 135, 140, 144, 170, 179).filter { it < before.size }.withIndex()) {
                var s = userAfter(days, 100 + i, before)
                val shown = ArrayList(s.assignments.toSortedMap().values.map { it.conceptId })
                for (d in days until now.size) {
                    val day = start.plusDays(d.toLong())
                    s = Curriculum.assign(day, now, s, d.toLong())
                    shown += s.assignments.getValue(day).conceptId
                }
                assertEquals("after $days days on the ${before.size}-concept library", now.size, shown.toSet().size)
                assertEquals(now.size, shown.size)
            }
        }
    }

    @Test
    fun `people who had seen everything meet the new concepts before any revisit`() {
        for ((before, newer) in upgrades) {
            if (newer.isEmpty()) continue
            val first = before.size + 49 // past the old library, so already revisiting
            var s = userAfter(first, 7, before)
            val next = (first until first + newer.size).map { d ->
                val day = start.plusDays(d.toLong())
                s = Curriculum.assign(day, now, s, d.toLong())
                s.assignments.getValue(day).conceptId
            }
            assertEquals(newer, next.toSet())
            assertEquals(now.all.filter { it.id in newer }.map { it.id }, next)
        }
    }

    @Test
    fun `specimen numbers already dealt in the first 135 days don't move`() {
        for (c in original.all.filter { it.number <= 135 }) assertEquals(c.id, c.number, now[c.id]!!.number)
    }

    @Test
    fun `the second addition moves nothing before its first new day`() {
        if (fromTwoMoreBooks.isEmpty()) return
        val firstNew = now.all.filter { it.id in fromTwoMoreBooks }.minOf { it.number }
        assertTrue("first new day $firstNew", firstNew > 136)
        for (c in afterFiveBooks.all.filter { it.number < firstNew }) assertEquals(c.id, c.number, now[c.id]!!.number)
    }
}
