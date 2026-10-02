package com.ankiwatch.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.random.Random

/**
 * Randomised stress tests. Every failure message carries the seed and the generated input,
 * so a failing case can be replayed. Scale with -Dstress.iterations=N.
 */
class StressTest {

    private val iterations = (System.getProperty("stress.iterations") ?: "2500").toInt()

    // ── Document generator with an answer-visibility oracle ──────────────────────────────

    private class GenCloze(
        val id: Int,
        val ord: Int,
        val anchor: Boolean,
        val parent: GenCloze?,
        /** Inside a display:none element: may legitimately be invisible even when open. */
        val inHiddenElement: Boolean
    ) {
        val secret = "Q${id}Z"
        val children = ArrayList<GenCloze>()
    }

    private class GenDoc(val html: String, val clozes: List<GenCloze>)

    private val words = listOf(
        "alpha", "beta", "gamma", "person", "offence", "section", "act", "court", "fine",
        "years", "theory", "caste", "tribe", "kinship", "§45", "(a)", "—", "x²", "दंड", "قانون"
    )

    private fun generate(rnd: Random, maxOrd: Int = 6): GenDoc {
        val sb = StringBuilder()
        val clozes = ArrayList<GenCloze>()
        var nextId = 0

        fun words(n: Int) {
            repeat(n) {
                if (sb.isNotEmpty() && rnd.nextInt(4) != 0) sb.append(' ')
                when (rnd.nextInt(14)) {
                    0 -> sb.append("&amp;")
                    1 -> sb.append("&nbsp;")
                    2 -> sb.append("&#150;")
                    3 -> sb.append("&lt;tag&gt;")
                    else -> sb.append(words[rnd.nextInt(words.size)])
                }
            }
        }

        fun inline(depth: Int, parent: GenCloze?, hidden: Boolean) {
            val items = 1 + rnd.nextInt(5)
            repeat(items) {
                when (rnd.nextInt(12)) {
                    0, 1 -> if (depth < 4) {
                        val ord = 1 + rnd.nextInt(maxOrd)
                        val anchor = rnd.nextInt(8) == 0
                        val c = GenCloze(nextId++, ord, anchor, parent, hidden)
                        parent?.children?.add(c)
                        clozes.add(c)
                        sb.append("{{c").append(ord).append("::")
                        if (anchor) sb.append('#')
                        sb.append(c.secret)
                        if (rnd.nextBoolean()) {
                            sb.append(' ')
                            inline(depth + 1, c, hidden)
                        }
                        if (rnd.nextBoolean()) {
                            sb.append("::")
                            words(1 + rnd.nextInt(2))
                        }
                        sb.append("}}")
                    }
                    2 -> {
                        val tag = listOf("b", "i", "u", "em", "strong", "sup", "sub", "code", "span")[rnd.nextInt(9)]
                        sb.append('<').append(tag).append('>')
                        inline(depth, parent, hidden)
                        sb.append("</").append(tag).append('>')
                    }
                    3 -> sb.append(if (rnd.nextBoolean()) "<br>" else "<br/>")
                    4 -> sb.append("<img src=\"data:image/png;base64,AAAA\" alt=\"").append(words[rnd.nextInt(words.size)]).append("\">")
                    5 -> {
                        sb.append("<span style=\"display:none\">")
                        inline(depth, parent, true)
                        sb.append("</span>")
                    }
                    else -> words(1 + rnd.nextInt(4))
                }
            }
        }

        fun block(depth: Int) {
            when (rnd.nextInt(9)) {
                0 -> {
                    val list = if (rnd.nextBoolean()) "ol" else "ul"
                    sb.append('<').append(list).append('>')
                    repeat(1 + rnd.nextInt(4)) {
                        sb.append("<li>")
                        inline(0, null, false)
                        if (depth < 3 && rnd.nextInt(3) == 0) block(depth + 1)
                        if (rnd.nextBoolean()) sb.append("</li>")
                    }
                    sb.append("</").append(list).append('>')
                }
                1 -> {
                    sb.append("<div class=\"header header-").append(listOf("red", "blue", "green", "yellow")[rnd.nextInt(4)]).append("\">")
                    inline(0, null, false)
                    sb.append("</div>")
                }
                2 -> {
                    sb.append("<div class=\"column\"><div class=\"col-title punishment\">")
                    words(2)
                    sb.append("</div><p>")
                    inline(0, null, false)
                    sb.append("</p></div>")
                }
                3 -> {
                    sb.append("<table><tr><td>")
                    inline(0, null, false)
                    sb.append("</td><td>")
                    inline(0, null, false)
                    sb.append("</td></tr></table>")
                }
                4 -> sb.append("<hr>")
                5 -> {
                    sb.append("<div class=\"core-box\"><p>")
                    inline(0, null, false)
                    sb.append("</p></div>")
                }
                else -> {
                    sb.append(if (rnd.nextBoolean()) "<p>" else "<div>")
                    inline(0, null, false)
                    sb.append(if (rnd.nextBoolean()) "</p>" else "</div>")
                }
            }
        }

        repeat(1 + rnd.nextInt(6)) {
            if (rnd.nextInt(3) == 0) inline(0, null, false) else block(0)
        }
        return GenDoc(sb.toString(), clozes)
    }

    /** Whether the oracle says cloze [c] is covered for this card state. */
    private fun covered(c: GenCloze, active: Int, answer: Boolean, toggled: Set<Int>, doc: GenDoc): Boolean {
        val tapped = c.id in toggled
        if (c.ord == active) return answer == tapped
        if (c.anchor) return false
        val containsActive = doc.clozes.any { d -> d.ord == active && isAncestor(c, d) }
        if (containsActive) return false
        return !tapped
    }

    private fun isAncestor(a: GenCloze, d: GenCloze): Boolean {
        var p = d.parent
        while (p != null) {
            if (p === a) return true
            p = p.parent
        }
        return false
    }

    private fun hiddenByCloze(c: GenCloze, active: Int, answer: Boolean, toggled: Set<Int>, doc: GenDoc): Boolean {
        var x: GenCloze? = c
        while (x != null) {
            if (covered(x, active, answer, toggled, doc)) return true
            x = x.parent
        }
        return false
    }

    @Test
    fun coveredAnswersNeverLeakAndOpenAnswersAreShown() {
        // Coverage counters: the test fails if the generator stops producing the cases it
        // is meant to check, so it cannot pass vacuously.
        var coveredChecks = 0
        var openChecks = 0
        var nestedChecks = 0
        var anchorChecks = 0
        var toggledChecks = 0
        repeat(iterations) { iteration ->
            val seed = 1000L + iteration
            val rnd = Random(seed)
            val doc = generate(rnd)
            val active = 1 + rnd.nextInt(6)
            for (answer in listOf(false, true)) {
                val toggled = doc.clozes.filter { rnd.nextInt(4) == 0 }.map { it.id }.toSet()
                val rendered = try {
                    CardRenderer.render(doc.html, RenderOptions(activeOrd = active, showAnswer = answer, toggled = toggled))
                } catch (t: Throwable) {
                    throw AssertionError("seed=$seed crashed on:\n${doc.html}", t)
                }
                val text = rendered.plainText()
                for (c in doc.clozes) {
                    val hidden = hiddenByCloze(c, active, answer, toggled, doc)
                    if (hidden) coveredChecks++ else if (!c.inHiddenElement) openChecks++
                    if (c.parent != null) nestedChecks++
                    if (c.anchor) anchorChecks++
                    if (c.id in toggled) toggledChecks++
                    if (hidden && text.contains(c.secret)) {
                        fail("seed=$seed active=c$active answer=$answer toggled=$toggled: covered ${c.secret} (c${c.ord}) leaked\nHTML: ${doc.html}\nTEXT: $text")
                    }
                    if (!hidden && !c.inHiddenElement && !text.contains(c.secret)) {
                        fail("seed=$seed active=c$active answer=$answer toggled=$toggled: open ${c.secret} (c${c.ord}) missing\nHTML: ${doc.html}\nTEXT: $text")
                    }
                }
                // The tested cloze, when present and visible, is what focus mode lands on.
                val visibleGenuine = doc.clozes.any { it.ord == active && !it.inHiddenElement &&
                    (it.parent == null || !hiddenByCloze(it.parent, active, answer, toggled, doc)) }
                if (visibleGenuine) {
                    assertTrue("seed=$seed no genuine block\n${doc.html}", rendered.hasGenuine)
                    assertTrue("seed=$seed", rendered.focusIndices()!!.isNotEmpty())
                }
            }
        }
        val minimum = iterations / 5
        assertTrue("only $coveredChecks covered checks", coveredChecks > minimum)
        assertTrue("only $openChecks open checks", openChecks > minimum)
        assertTrue("only $nestedChecks nested checks", nestedChecks > minimum)
        assertTrue("only $anchorChecks anchor checks", anchorChecks > minimum / 4)
        assertTrue("only $toggledChecks toggled checks", toggledChecks > minimum / 2)
        println("leak oracle: covered=$coveredChecks open=$openChecks nested=$nestedChecks anchor=$anchorChecks toggled=$toggledChecks")
    }

    // ── Mutation fuzzing: arbitrary garbage must never crash or break invariants ─────────

    private val nasty = "<>{}:/#&;\"'= \n\tabc1c2{{c1::}}</li><ol><li><br><hr id=answer><!--<script>"

    private fun mutate(rnd: Random, s: String): String {
        val sb = StringBuilder(s)
        repeat(1 + rnd.nextInt(12)) {
            if (sb.isEmpty()) {
                sb.append(nasty[rnd.nextInt(nasty.length)])
                return@repeat
            }
            val at = rnd.nextInt(sb.length + 1)
            when (rnd.nextInt(5)) {
                0 -> if (at < sb.length) sb.deleteCharAt(at)
                1 -> sb.insert(at, nasty[rnd.nextInt(nasty.length)])
                2 -> {
                    val from = rnd.nextInt(nasty.length)
                    sb.insert(at, nasty.substring(from, minOf(nasty.length, from + 1 + rnd.nextInt(10))))
                }
                3 -> {
                    val end = minOf(sb.length, at + rnd.nextInt(30))
                    if (at < end) sb.insert(at, sb.substring(at, end))
                }
                else -> sb.setLength(minOf(sb.length, at))
            }
        }
        return sb.toString()
    }

    private fun checkInvariants(seed: Long, html: String, r: RenderedField) {
        for ((k, b) in r.blocks.withIndex()) {
            assertEquals("seed=$seed index", k, b.index)
            for (c in b.context) assertTrue("seed=$seed context $c of $k\n$html", c in 0 until k)
            if (b.kind != BlockKind.RULE) {
                assertTrue("seed=$seed empty block $k\n$html", b.runs.isNotEmpty())
                assertTrue("seed=$seed blank block $k: '${b.text}'\n$html", b.text.isNotBlank())
            }
            for (run in b.runs) assertTrue("seed=$seed empty run\n$html", run.text.isNotEmpty())
        }
        val focus = r.focusIndices()
        if (focus != null) {
            assertEquals("seed=$seed focus sorted", focus.sorted().distinct(), focus)
            assertTrue("seed=$seed focus range", focus.all { it in r.blocks.indices })
            assertTrue("seed=$seed focus has genuine", focus.any { r.blocks[it].containsGenuine })
        }
    }

    @Test
    fun mutatedInputNeverCrashesAndKeepsInvariants() {
        val seeds = listOf(Fixtures.LAW_SECTION, Fixtures.ANTHRO_SKELETON)
        repeat(iterations) { iteration ->
            val seed = 50_000L + iteration
            val rnd = Random(seed)
            val base = if (rnd.nextInt(3) == 0) seeds[rnd.nextInt(seeds.size)] else generate(rnd).html
            val html = mutate(rnd, base)
            val options = RenderOptions(
                activeOrd = rnd.nextInt(7),
                showAnswer = rnd.nextBoolean(),
                toggled = (0 until 20).filter { rnd.nextInt(3) == 0 }.toSet(),
                plainClozes = rnd.nextInt(6) == 0
            )
            val rendered = try {
                CardRenderer.render(html, options)
            } catch (t: Throwable) {
                throw AssertionError("seed=$seed crashed on:\n$html", t)
            }
            checkInvariants(seed, html, rendered)
            // Rendering is deterministic.
            assertEquals("seed=$seed", rendered.blocks, CardRenderer.render(html, options).blocks)
        }
    }

    @Test
    fun purelyRandomCharactersNeverCrash() {
        val alphabet = "<>{}:/#&;\"'= \nabcli1234567890!-?[]"
        repeat(iterations) { iteration ->
            val seed = 90_000L + iteration
            val rnd = Random(seed)
            val html = String(CharArray(rnd.nextInt(400)) { alphabet[rnd.nextInt(alphabet.length)] })
            val r = try {
                CardRenderer.render(html, RenderOptions(activeOrd = 1 + rnd.nextInt(3), showAnswer = rnd.nextBoolean()))
            } catch (t: Throwable) {
                throw AssertionError("seed=$seed crashed on:\n$html", t)
            }
            checkInvariants(seed, html, r)
        }
    }

    // ── Sanitizer must not change what the watch shows ───────────────────────────────────

    private fun withNoise(rnd: Random, html: String): String {
        // Sprinkle in things the sanitizer removes or rewrites.
        val noise = listOf(
            "<script>var s = '{{c1::x}}'; if (a < b) {}</script>",
            "<style>.c > b { color: red }</style>",
            "<!-- comment {{c2::y}} -->",
            "<img src=\"data:image/png;base64,${"A".repeat(500)}\" alt='a \"quoted\" alt'>",
            "<img src=x.png>",
            "<!DOCTYPE html>",
            "<SCRIPT type=\"text/javascript\">x</SCRIPT>"
        )
        val sb = StringBuilder(html)
        repeat(1 + rnd.nextInt(4)) {
            // Insert only in front of a tag, like real template output (a space could be
            // inside an attribute value such as class="header header-red").
            val candidates = sb.indices.filter { sb[it] == '<' }
            val at = if (candidates.isEmpty()) sb.length else candidates[rnd.nextInt(candidates.size)]
            sb.insert(at, noise[rnd.nextInt(noise.size)])
        }
        return sb.toString()
    }

    @Test
    fun sanitizerPreservesRendering() {
        repeat(iterations) { iteration ->
            val seed = 120_000L + iteration
            val rnd = Random(seed)
            val html = withNoise(rnd, generate(rnd).html)
            val clean = HtmlSanitizer.forWatch(html)
            assertTrue("seed=$seed sanitizer grew input", clean.length <= html.length + 7 * 8)
            assertFalse("seed=$seed script survived", clean.contains("<script", ignoreCase = true))
            assertFalse("seed=$seed base64 survived", clean.contains("base64"))
            for (active in 0..3) {
                val options = RenderOptions(activeOrd = active, showAnswer = rnd.nextBoolean())
                val before = CardRenderer.render(html, options)
                val after = CardRenderer.render(clean, options)
                if (before.blocks != after.blocks) {
                    fail("seed=$seed sanitizer changed rendering\nIN:  $html\nOUT: $clean\nBEFORE: ${before.plainText()}\nAFTER:  ${after.plainText()}")
                }
            }
        }
    }

    @Test
    fun sanitizerIsIdempotent() {
        repeat(iterations / 5) { iteration ->
            val rnd = Random(130_000L + iteration)
            val once = HtmlSanitizer.forWatch(withNoise(rnd, generate(rnd).html))
            val twice = HtmlSanitizer.forWatch(once)
            assertEquals(CardRenderer.render(once, RenderOptions(activeOrd = 1)).blocks, CardRenderer.render(twice, RenderOptions(activeOrd = 1)).blocks)
        }
    }

    // ── Truncation must fit the budget and never uncover an answer ───────────────────────

    @Test
    fun truncationFitsBudgetAndNeverLeaks() {
        repeat(iterations) { iteration ->
            val seed = 150_000L + iteration
            val rnd = Random(seed)
            val doc = generate(rnd)
            val full = PayloadBudget.utf8Length(doc.html)
            val budget = rnd.nextInt(full + 50)
            val cut = SafeTruncate.truncate(doc.html, budget)
            assertTrue("seed=$seed over budget: ${PayloadBudget.utf8Length(cut)} > $budget", PayloadBudget.utf8Length(cut) <= maxOf(budget, 0) || cut.isEmpty())
            if (cut != doc.html && cut.isNotEmpty()) {
                val prefix = cut.removeSuffix(PayloadBudget.TRUNCATION_NOTE)
                assertTrue("seed=$seed not a prefix", doc.html.startsWith(prefix))
            }
            val active = 1 + rnd.nextInt(6)
            val fullText = CardRenderer.render(doc.html, RenderOptions(activeOrd = active)).plainText()
            val cutText = CardRenderer.render(cut, RenderOptions(activeOrd = active)).plainText()
            for (c in doc.clozes) {
                if (cutText.contains(c.secret) && !fullText.contains(c.secret)) {
                    fail("seed=$seed truncation uncovered ${c.secret}\nCUT: $cut\nTEXT: $cutText")
                }
            }
        }
    }

    @Test
    fun shrinkAlwaysFitsBudget() {
        repeat(iterations / 5) { iteration ->
            val seed = 170_000L + iteration
            val rnd = Random(seed)
            fun big() = buildString { repeat(rnd.nextInt(4)) { append(generate(rnd).html.repeat(1 + rnd.nextInt(80))) } }
            val card = PayloadBudget.CardText(
                question = big(),
                answer = big(),
                content = big(),
                extras = List(rnd.nextInt(4)) { "Extra$it" to big() }
            )
            val budget = 2_000 + rnd.nextInt(90_000)
            val shrunk = PayloadBudget.shrink(card, budget)
            assertTrue("seed=$seed ${shrunk.byteSize()} > $budget", shrunk.byteSize() <= budget)
        }
    }

    // ── Scale ────────────────────────────────────────────────────────────────────────────

    @Test(timeout = 20_000)
    fun oneMegabyteFieldRendersQuickly() {
        val html = buildString { while (length < 1_000_000) append(Fixtures.LAW_SECTION) }
        val start = System.nanoTime()
        val r = CardRenderer.render(html, RenderOptions(activeOrd = 5))
        val ms = (System.nanoTime() - start) / 1_000_000
        assertTrue("took ${ms}ms", ms < 5_000)
        assertTrue(r.blocks.size > 1000)
        assertFalse(r.plainText().contains("seven years"))
    }

    @Test(timeout = 20_000)
    fun deepNestingDoesNotOverflowTheStack() {
        val depth = 50_000
        val divs = "<div>".repeat(depth) + "x {{c1::secret}}" + "</div>".repeat(depth)
        assertEquals("x […]", CardRenderer.render(divs, RenderOptions(activeOrd = 1)).plainText())
        val clozes = "{{c2::".repeat(depth) + "core" + "}}".repeat(depth)
        val r = CardRenderer.render(clozes, RenderOptions(activeOrd = 2))
        assertEquals("[…]", r.plainText())
        val lists = "<ol><li>".repeat(5_000) + "deep {{c1::x}}"
        assertTrue(CardRenderer.render(lists, RenderOptions(activeOrd = 1)).plainText().endsWith("deep […]"))
    }

    @Test(timeout = 20_000)
    fun manyTinyTokensStayLinear() {
        val html = "<b>a</b> ".repeat(100_000)
        val start = System.nanoTime()
        CardRenderer.render(html, RenderOptions())
        assertTrue((System.nanoTime() - start) / 1_000_000 < 5_000)
        val unclosedScripts = "<script>".repeat(20_000)
        CardRenderer.render(unclosedScripts, RenderOptions())
        val manyTags = "<span>".repeat(100_000)
        CardRenderer.render(manyTags + "text", RenderOptions())
    }
}
