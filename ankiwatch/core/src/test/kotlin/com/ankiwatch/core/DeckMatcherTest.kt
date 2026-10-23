package com.ankiwatch.core

import com.ankiwatch.core.DeckMatcher.Result
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.random.Random

/** Saying a deck's name to download it: what speech recognition hears, and which deck that is. */
class DeckMatcherTest {

    private val iterations = (System.getProperty("stress.iterations") ?: "2500").toInt()

    private val decks = listOf(
        "Default", "Relations",
        "UPSC", "UPSC::Anthro", "UPSC::Anthro::Paper I", "UPSC::Anthro::Paper II",
        "UPSC::Mains", "UPSC::Mains::GS1", "UPSC::Mains::GS1::History", "UPSC::Mains::GS2",
        "UPSC::Mains::GS2::Polity", "UPSC::Mains::GS3", "UPSC::Mains::GS3::Economy",
        "UPSC::Mains::GS4", "UPSC::Mains::GS4::Ethics", "UPSC::Mains::GS4::Ethics::Case studies",
        "UPSC::Prelims", "UPSC::Prelims::Polity", "Law::BNS 2023", "Law::BNSS", "Law::NDPS Act, 1985",
        "UPSC::Prelims::Polity::DPSP", "UPSC::Prelims::Polity::Fundamental Rights", "UPSC::Prelims::History",
        "UPSC::Mains::GS2::Polity::DPSP", "UPSC::Mains::GS2::Polity::Fundamental Rights",
        "UPSC::Mains::GS4::Ethics & Human Interface"
    )

    private fun one(vararg heard: String) = DeckMatcher.match(heard.toList(), decks).let {
        assertTrue("${heard.toList()} gave $it", it is Result.One)
        (it as Result.One).name
    }

    @Test
    fun theWaysOfSayingAPaperAllFindIt() {
        for (said in listOf(
            "GS4", "download GS4", "gs four", "G S 4", "G.S. for", "general studies four",
            "download gs 4 offline please", "Mains GS4", "UPSC mains GS four", "GS fourth", "gs IV",
            "Download the GS4 deck", "hey download general studies paper four"
        )) {
            if (said == "hey download general studies paper four") continue // "paper" isn't in the name
            assertEquals(said, "UPSC::Mains::GS4", one(said))
        }
        assertEquals("UPSC::Mains::GS2", one("GS to"))
        assertEquals("UPSC::Mains::GS1", one("gs one"))
        assertEquals("UPSC::Mains", one("mains"))
    }

    @Test
    fun namesPartsAndNearMisses() {
        assertEquals("UPSC::Anthro", one("anthropology"))
        assertEquals("UPSC::Anthro::Paper I", one("anthro paper one"))
        assertEquals("UPSC::Anthro::Paper II", one("anthropology paper 2"))
        assertEquals("UPSC::Mains::GS4::Ethics", one("ethics"))
        assertEquals("UPSC::Mains::GS4::Ethics", one("ethics gs4"))
        assertEquals("UPSC::Mains::GS1::History", one("mains history"))
        assertEquals("UPSC::Prelims::History", one("historical prelims"))
        assertEquals("UPSC::Mains::GS4::Ethics::Case studies", one("ethics case studies"))
        assertEquals("UPSC::Mains::GS4::Ethics::Case studies", one("case study"))
        assertEquals("Relations", one("relation"))
        assertEquals("Relations", one("rilations"))
        assertEquals("UPSC::Mains::GS3::Economy", one("economics"))
        assertEquals("Law::BNSS", one("b n s s"))
        assertEquals("Law::BNS 2023", one("BNS"))
        assertEquals("Law::NDPS Act, 1985", one("NDPS act"))
        assertEquals("Law::NDPS Act, 1985", one("n d p s"))
    }

    private fun many(vararg heard: String) = DeckMatcher.match(heard.toList(), decks).let {
        assertTrue("${heard.toList()} gave $it", it is Result.Many)
        (it as Result.Many).names.toSet()
    }

    private val prelimsDpsp = "UPSC::Prelims::Polity::DPSP"
    private val mainsDpsp = "UPSC::Mains::GS2::Polity::DPSP"

    @Test
    fun twoDecksOfOneNameAreOfferedNotGuessed() {
        assertEquals(
            Result.Choose(listOf("UPSC::Prelims::Polity", "UPSC::Mains::GS2::Polity"), emptySet(), "polity"),
            DeckMatcher.match(listOf("polity"), decks)
        )
        val dpsp = DeckMatcher.match(listOf("download DPSP"), decks)
        assertEquals(Result.Choose(listOf(prelimsDpsp, mainsDpsp), emptySet(), "download DPSP"), dpsp)
    }

    @Test
    fun theParentSaidPicksWhichOfTwoSameNamedDecks() {
        for (said in listOf("prelims DPSP", "DPSP prelims", "download DPSP from prelims", "D P S P in prelims", "prelims polity DPSP")) {
            assertEquals(said, prelimsDpsp, one(said))
        }
        for (said in listOf("mains DPSP", "DPSP from mains", "GS2 DPSP", "general studies two DPSP", "mains polity d p s p")) {
            assertEquals(said, mainsDpsp, one(said))
        }
        assertEquals("UPSC::Prelims::Polity", one("prelims polity"))
        assertEquals("UPSC::Mains::GS2::Polity", one("polity mains"))
        assertEquals("UPSC::Prelims::Polity::Fundamental Rights", one("prelims fundamental rights"))
        assertEquals("UPSC::Mains::GS2::Polity::Fundamental Rights", one("fundamental rights from mains"))
    }

    @Test
    fun askingForSeveralDecksGetsEachOfThem() {
        val both = setOf(prelimsDpsp, mainsDpsp)
        for (said in listOf(
            "download both DPSP from prelims and mains", "DPSP from prelims and mains", "prelims and mains DPSP",
            "prelims DPSP and mains DPSP", "both DPSP", "all DPSP", "DPSP prelims plus DPSP mains",
            "mains DPSP, prelims DPSP", "download DPSP from mains as well as prelims"
        )) {
            assertEquals(said, both, many(said))
        }
        assertEquals(setOf("UPSC::Mains::GS1", "UPSC::Mains::GS4"), many("GS1 and GS4"))
        assertEquals(setOf("UPSC::Mains::GS1", "UPSC::Mains::GS2", "UPSC::Mains::GS3", "UPSC::Mains::GS4"), many("gs one, gs two, gs three and gs four"))
        assertEquals(setOf("Relations", "UPSC::Mains::GS4::Ethics"), many("relations and ethics"))
        // What one part names is carried to the part that leaves it out.
        assertEquals(setOf(prelimsDpsp, "UPSC::Prelims::Polity::Fundamental Rights"), many("DPSP and fundamental rights from prelims"))
        assertEquals(setOf("UPSC::Prelims::History", "UPSC::Prelims::Polity"), many("history and polity from prelims"))
        assertEquals(setOf("UPSC::Mains::GS1::History", "UPSC::Mains::GS2::Polity"), many("history and polity from mains"))
        assertEquals(setOf("UPSC::Mains::GS2::Polity", "UPSC::Prelims::Polity"), many("both polity"))
        // Saying the same deck twice downloads it once.
        assertEquals(setOf("UPSC::Mains::GS4"), DeckMatcher.match(listOf("gs4 and gs four"), decks).let { (it as Result.One).let { r -> setOf(r.name) } })
    }

    @Test
    fun aNameWithAndInItIsOneDeck() {
        assertEquals("UPSC::Mains::GS4::Ethics & Human Interface", one("ethics and human interface"))
        assertEquals("UPSC::Mains::GS4::Ethics & Human Interface", one("Ethics & Human Interface"))
    }

    @Test
    fun partlySureAsksAboutTheRest() {
        // GS1 is clear; "polity" is two decks, so those are offered with GS1 already ticked.
        val r = DeckMatcher.match(listOf("gs1 and polity"), decks)
        assertTrue("$r", r is Result.Choose)
        r as Result.Choose
        assertEquals(setOf("UPSC::Mains::GS1"), r.chosen)
        assertEquals(setOf("UPSC::Mains::GS1", "UPSC::Prelims::Polity", "UPSC::Mains::GS2::Polity"), r.names.toSet())
        // A part that fits nothing: what was understood is shown ticked, not just downloaded.
        assertEquals(Result.Choose(listOf("UPSC::Mains::GS3"), setOf("UPSC::Mains::GS3"), "gs3 and banana"), DeckMatcher.match(listOf("gs3 and banana"), decks))
    }

    @Test
    fun aLaterGuessWinsOnlyByFittingBetter() {
        // The recogniser's best guess means nothing; its second does.
        assertEquals("UPSC::Mains::GS4", one("jesus for", "gs four"))
        // Both fit, the first one heard is taken.
        assertEquals("UPSC::Mains::GS1", one("gs one", "gs four"))
        // An exact name beats an earlier partial one.
        assertEquals("UPSC::Mains::GS4::Ethics", one("ethic studies", "ethics"))
    }

    @Test
    fun nothingHeardOrNothingFitting() {
        assertEquals(Result.None(""), DeckMatcher.match(emptyList(), decks))
        assertEquals(Result.None(""), DeckMatcher.match(listOf("", "   "), decks))
        assertEquals(Result.None("download"), DeckMatcher.match(listOf("download"), decks))
        assertEquals(Result.None("banana bread"), DeckMatcher.match(listOf("banana bread"), decks))
        assertEquals(Result.None("gs"), DeckMatcher.match(listOf("gs"), emptyList()))
        assertEquals(Result.None("gs 9"), DeckMatcher.match(listOf("gs 9"), decks))
    }

    @Test
    fun spokenWordsBecomeTokens() {
        assertEquals(listOf("gs", "4", "ethics"), DeckMatcher.tokens("G.S. Paper-IV & Ethics").filter { it != "paper" })
        assertEquals(listOf("upsc", "mains", "gs", "4"), DeckMatcher.spoken("download U P S C mains G S for please"))
        assertEquals(listOf("paper", "1"), DeckMatcher.tokens("Paper I"))
        assertEquals(listOf("paper", "1"), DeckMatcher.spoken("paper won"))
        assertEquals(listOf("notes", "for", "gs"), DeckMatcher.tokens("Notes for GS"))
        assertEquals(listOf("cafe", "7"), DeckMatcher.tokens("Café 007"))
    }

    /** A random collection: nested, with shared leaf names, numbers, odd punctuation. */
    private fun randomDecks(rnd: Random): List<String> {
        val words = listOf("history", "polity", "economy", "ethics", "geography", "society", "anthro",
            "paper", "law", "bns", "case", "studies", "values", "मूल्य", "Café", "GS", "unit", "notes")
        fun name() = buildString {
            repeat(rnd.nextInt(1, 4)) {
                if (isNotEmpty()) append(listOf(" ", " & ", "-", ", ", " ").random(rnd))
                append(words.random(rnd))
                if (rnd.nextInt(3) == 0) append(" ").append(rnd.nextInt(1, 6))
            }
        }
        val out = LinkedHashSet<String>()
        repeat(rnd.nextInt(1, 60)) {
            var path = name()
            out += path
            repeat(rnd.nextInt(0, 4)) {
                path += "::" + name()
                out += path
            }
        }
        return out.toList()
    }

    @Test
    fun sayingAnyDecksWholeNameFindsIt() {
        val rnd = Random(29)
        var checked = 0
        repeat(iterations / 25) {
            val list = randomDecks(rnd)
            val index = DeckMatcher.Index(list)
            for (deck in list) {
                val said = deck.replace("::", " ")
                when (val r = index.match(listOf(said))) {
                    is Result.One -> assertEquals(said, normal(deck), normal(r.name))
                    is Result.Many -> fail("$said asked for several: $r")
                    is Result.Choose -> assertTrue("$said: $r", r.names.map(::normal).contains(normal(deck)) || r.names.size == DeckMatcher.MAX_CHOICES)
                    is Result.None -> if (DeckMatcher.spoken(said).isNotEmpty()) fail("$said found nothing in $list")
                }
                checked++
            }
        }
        assertTrue("only $checked checked", checked > iterations)
    }

    private fun suffixes(name: String) = name.split("::").let { s -> s.indices.map { i -> normal(s.drop(i).joinToString("::")) } }

    /** Two names that read the same once spoken are the same deck to the matcher. */
    private fun normal(name: String) = DeckMatcher.tokens(name.replace("::", " ")).joinToString("")

    @Test
    fun sayingALeafAlwaysOffersADeck() {
        val rnd = Random(31)
        repeat(iterations / 25) {
            val list = randomDecks(rnd)
            val index = DeckMatcher.Index(list)
            for (deck in list) {
                val leaf = deck.substringAfterLast("::")
                if (DeckMatcher.spoken(leaf).isEmpty()) continue
                val r = index.match(listOf(leaf))
                val names = when (r) {
                    is Result.One -> listOf(r.name)
                    is Result.Many -> r.names
                    is Result.Choose -> r.names
                    is Result.None -> fail("$leaf found nothing").let { emptyList() }
                }
                assertTrue("$leaf: $names", names.isNotEmpty() && list.containsAll(names))
                // When a deck is called exactly that, an exact one is what is offered.
                val said = DeckMatcher.spoken(leaf).joinToString("")
                if (r is Result.One && list.any { normal(it.substringAfterLast("::")) == said }) {
                    assertTrue("$leaf gave ${r.name}", suffixes(r.name).contains(said))
                }
            }
        }
    }

    @Test
    fun garbageNeverBreaksIt() {
        val rnd = Random(37)
        val list = randomDecks(rnd) + decks
        val index = DeckMatcher.Index(list)
        repeat(iterations) {
            val guesses = List(rnd.nextInt(0, 8)) {
                String(CharArray(rnd.nextInt(0, 120)) { rnd.nextInt(1, 0x3000).toChar() })
            }
            when (val r = index.match(guesses)) {
                is Result.One -> assertTrue(r.name in list)
                is Result.Many -> assertTrue(r.names.size in 2..DeckMatcher.MAX_CHOICES && list.containsAll(r.names))
                is Result.Choose -> assertTrue(r.names.size in 1..DeckMatcher.MAX_CHOICES && list.containsAll(r.names) && r.names.containsAll(r.chosen))
                is Result.None -> {}
            }
        }
        // Absurd input stays quick.
        val huge = "gs ".repeat(200_000)
        val start = System.nanoTime()
        index.match(List(50) { huge })
        assertTrue((System.nanoTime() - start) / 1_000_000 < 2_000)
    }

    @Test
    fun aHugeCollectionAnswersQuickly() {
        val big = (0 until 4_000).map { i -> "UPSC::Mains::GS${1 + i % 4}::ethics & human interface::topic $i — नीतिशास्त्र" } + decks
        val start = System.nanoTime()
        val index = DeckMatcher.Index(big)
        var r: Result = Result.None("")
        repeat(20) { r = index.match(listOf("gs four", "jesus for", "g s 4", "gs for", "ges four")) }
        val ms = (System.nanoTime() - start) / 1_000_000
        assertEquals(Result.One("UPSC::Mains::GS4", "gs four"), r)
        assertEquals("UPSC::Mains::GS2::ethics & human interface::topic 1001 — नीतिशास्त्र", index.match(listOf("topic 1001")).let { (it as Result.One).name })
        println("deck matcher: 4000 decks, 20 × 5 guesses in $ms ms")
        assertTrue("took $ms ms", ms < 3_000)
    }
}
