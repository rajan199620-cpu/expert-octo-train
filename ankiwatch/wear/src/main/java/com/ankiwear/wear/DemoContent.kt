package com.ankiwear.wear

import com.ankiwear.wear.model.CardData
import com.ankiwear.wear.model.ClozeCard
import com.ankiwear.wear.model.DeckInfo

/**
 * Sample decks for demo mode: cards shaped like real Enhanced Cloze notes (a statute layout
 * with hints and a `#` anchor, and an answer skeleton of nested lists), plus a plain card.
 * The wording is invented.
 */
object DemoContent {

    private const val LAW_NOTE = """<div class="header header-blue">Act §999 — Example offence</div>
<div class="content">
  <div class="core-box"><p>{{c1::#A person commits the example offence::which act?}}
     when, in any of the following ways:</p></div>
  <ol>
    <li><b>First limb:</b> {{c2::by persuading another person to do the act::first mode}}</li>
    <li><b>Second limb:</b> {{c3::by agreeing with others and acting on it::second mode}};
        {{c3::an act in pursuance must follow::condition}}</li>
    <li><b>Third limb:</b> {{c4::by intentionally helping the act::third mode}}</li>
  </ol>
  <div class="horizontal-wrap">
    <div class="column"><div class="col-title punishment">Punishment</div>
      <p>{{c5::imprisonment up to seven years, and fine::punishment}}</p></div>
    <div class="column"><div class="col-title proviso">Proviso</div>
      <p>Not applicable where {{c6::the act is done in good faith::exception}}.</p></div>
  </div>
</div>"""

    private const val ANTHRO_NOTE = """<b>Theories of the origin of the institution</b>
<ol>
  <li>Racial theory
    <ol>
      <li>{{c1::Proponent A}} — stated in {{c1::Work One (1901)}}</li>
      <li>Evidence: {{c2::measurements of a population sample}}</li>
    </ol>
  </li>
  <li>Occupational theory
    <ol>
      <li>{{c3::Proponent B}} argued {{c3::occupation as the basis}}</li>
      <li>Critique: {{c4::ignores ritual status}}</li>
    </ol>
  </li>
</ol>"""

    private val INTERVALS = listOf("<1m", "6m", "1d", "4d")

    val decks = listOf(
        DeckInfo(1, "law::example act", 2, 1, 3),
        DeckInfo(2, "UPSC::ANTHRO+::P1::origins", 1, 0, 1),
        DeckInfo(3, "Basics", 0, 0, 1)
    )

    fun cards(deckId: Long): List<CardData> = when (deckId) {
        1L -> listOf(
            lawCard(ord = 2),
            lawCard(ord = 4),
            lawCard(ord = 0)
        )
        2L -> listOf(
            CardData(
                noteId = 201, cardOrd = 3, question = "", answer = "", buttonCount = 4,
                nextReviewTimes = INTERVALS,
                cloze = ClozeCard(ANTHRO_NOTE, clozeNumber = 4, modelName = "Enhanced Cloze 2.1 v2")
            )
        )
        else -> listOf(
            CardData(
                noteId = 301, cardOrd = 0,
                question = "<div>What does <b>FSRS</b> schedule?</div>",
                answer = "<div>What does <b>FSRS</b> schedule?</div><hr id=answer>" +
                    "<div>When each card is due next, from its memory state.</div>",
                buttonCount = 4,
                nextReviewTimes = INTERVALS
            )
        )
    }

    private fun lawCard(ord: Int) = CardData(
        noteId = 101,
        cardOrd = ord,
        question = "",
        answer = "",
        buttonCount = 4,
        nextReviewTimes = INTERVALS,
        cloze = ClozeCard(
            content = LAW_NOTE,
            clozeNumber = ord + 1,
            extras = listOf(
                "Note" to "Definitional section: it says when the offence is made out; punishment is in the column.",
                "Extra" to "Memory: P-A-H — <b>P</b>ersuade, <b>A</b>gree + act, <b>H</b>elp."
            ),
            modelName = "Enhanced Cloze (Content/Note/Extra)"
        )
    )
}
