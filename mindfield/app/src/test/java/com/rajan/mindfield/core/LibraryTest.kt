package com.rajan.mindfield.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Checks every concept in the real library, so a typo in the content fails the build, not the app. */
class LibraryTest {
    private val library = TestLibrary.library
    private val all = library.all

    @Test
    fun `the library parses cleanly and is big enough for months of days`() {
        val parsed = ConceptParser.parse(TestLibrary.text)
        assertEquals(emptyList<String>(), parsed.errors)
        assertTrue("only ${all.size} concepts", all.size >= 140)
        assertEquals(all.size, all.map { it.id }.toSet().size)
    }

    @Test
    fun `day one is the frequency illusion and numbers run 1 to N`() {
        assertEquals(Curriculum.FIRST, all.first().id)
        assertEquals((1..all.size).toList(), all.map { it.number })
    }

    @Test
    fun `categories are dealt round robin so neighbours differ`() {
        for (c in Category.entries) assertTrue("$c has too few", all.count { it.category == c } >= 12)
        // While every category still has concepts left, no two consecutive days share a category.
        val fullRounds = Category.entries.minOf { c -> all.count { it.category == c } } * Category.entries.size
        for (i in 1 until fullRounds) assertTrue("days $i and ${i + 1}", all[i].category != all[i - 1].category)
    }

    @Test
    fun `myths are well represented and every evidence level is used`() {
        for (e in Evidence.entries) assertTrue("no $e", all.any { it.evidence == e })
        assertTrue(all.count { it.isMyth } >= 10)
    }

    @Test
    fun `titles are unique and short enough for a card`() {
        assertEquals(all.size, all.map { it.title.lowercase() }.toSet().size)
        for (c in all) assertTrue("${c.id} title too long: ${c.title.length}", c.title.length <= 42)
    }

    @Test
    fun `hooks fit a notification and missions have a clean first sentence`() {
        for (c in all) {
            assertTrue("${c.id} hook is ${c.hook.length} chars", c.hook.length in 30..160)
            val m = c.missionLine
            assertTrue("${c.id} mission line '$m'", m.length in 25..220 && m.last() in ".!?”’")
        }
    }

    @Test
    fun `every predict question has distinct options and exactly one answer`() {
        for (c in all) {
            val p = c.predict
            assertEquals("${c.id} options", 3, p.options.size)
            assertEquals("${c.id} duplicate option", p.options.size, p.options.toSet().size)
            assertTrue(p.answer in p.options.indices)
            assertTrue("${c.id} question", p.question.endsWith("?") || p.question.endsWith("...") || p.question.endsWith("…"))
        }
        // As shown, the right answer isn't always in the same place, so position can't give it away.
        val positions = all.groupingBy { it.predict.order(it.id).indexOf(it.predict.answer) }.eachCount()
        for (i in 0..2) assertTrue("answer position $i used ${positions[i]} times", (positions[i] ?: 0) >= all.size / 6)
    }

    @Test
    fun `scenarios never name their own concept`() {
        for (c in all) {
            assertFalse("${c.id} scenario names it", c.scenario.contains(c.title, ignoreCase = true))
            c.aka?.let { assertFalse("${c.id} scenario names its aka", c.scenario.contains(it, ignoreCase = true)) }
        }
    }

    @Test
    fun `text is typeset and complete`() {
        for (c in all) {
            val texts = listOf(c.title, c.hook, c.what, c.study, c.proof, c.spot, c.use, c.guard, c.scenario, c.source) +
                c.predict.options + c.predict.question
            for (t in texts) {
                assertFalse("${c.id} has a straight quote: $t", t.contains('"') || t.contains('\''))
                assertFalse("${c.id} has a double space: $t", t.contains("  "))
                assertEquals("${c.id} has untrimmed text", t.trim(), t)
            }
            for (t in listOf(c.what, c.study, c.proof, c.spot, c.use, c.guard, c.scenario)) {
                assertTrue("${c.id} text should end a sentence: …${t.takeLast(30)}", t.last() in ".!?”)")
            }
        }
    }

    @Test
    fun `every concept has a substantial real-world case with a takeaway`() {
        for (c in all) {
            assertTrue("${c.id} case is ${c.case.length} chars", c.case.length in 500..1300)
            assertTrue("${c.id} case repeats the study", c.case != c.study && !c.case.startsWith(c.study.take(60)))
            assertTrue("${c.id} case story too short", c.caseStory.length >= 300)
        }
        for (c in all) assertTrue("${c.id} case has no \"The nuance:\" takeaway", c.caseNuance != null)
    }

    @Test
    fun `related links point both ways often enough to be useful`() {
        val linked = all.count { it.related.isNotEmpty() }
        assertTrue(linked >= all.size * 0.9)
    }
}

class ParserTest {
    private val good = """
        ## sample-one
        title: Sample
        category: thinking
        evidence: solid
        hook: A "hook" that's long enough to pass the length check here.
        what: What.
        study: Study.
        case: A real place, with numbers. The nuance: it depends.
        proof: Proof.
        spot: Spot.
        use: Do it today. Then more.
        guard: Guard.
        predict: Which?
        - no
        * yes
          continued
        - no again
        scenario: Story.
        source: Someone (2020).
    """.trimIndent()

    @Test
    fun `parses fields, continuation lines and smart quotes`() {
        val r = ConceptParser.parse(good)
        assertEquals(emptyList<String>(), r.errors)
        val c = r.concepts.single()
        assertEquals("A “hook” that’s long enough to pass the length check here.", c.hook)
        assertEquals(listOf("no", "yes continued", "no again"), c.predict.options)
        assertEquals(1, c.predict.answer)
        assertEquals("Do it today.", c.missionLine)
        assertEquals("A real place, with numbers.", c.caseStory)
        assertEquals("It depends.", c.caseNuance)
    }

    @Test
    fun `reports every kind of mistake with its id`() {
        val bad = good
            .replace("evidence: solid", "evidence: maybe")
            .replace("- no again", "* also yes") +
            "\n\n## sample-one\ntitle: Dup\n" +
            "\n## Bad Id\n" +
            "\n## orphan\nrelated: nowhere\nfoo: bar\n"
        val errors = ConceptParser.parse(bad).errors.joinToString("\n")
        for (expected in listOf("unknown evidence", "exactly one right option", "bad id", "unknown field 'foo'", "missing 'title'")) {
            assertTrue("expected '$expected' in:\n$errors", errors.contains(expected, ignoreCase = true))
        }
    }

    @Test
    fun `unknown related ids are caught`() {
        val errors = ConceptParser.parse(good + "\nrelated: ghost").errors
        assertTrue(errors.any { "unknown id 'ghost'" in it })
    }

    @Test
    fun `typography handles quotes, apostrophes and dashes`() {
        assertEquals("“Hi,” she said. It’s ‘fine’ — really…", Typography.smarten("\"Hi,\" she said. It's 'fine' -- really..."))
        assertEquals("(“quoted”)", Typography.smarten("(\"quoted\")"))
    }
}
