package com.ankiwatch.core

/**
 * Card content shaped like the user's decks: the Enhanced Cloze law layout (header,
 * core-box, columns with coloured titles, hinted clozes, a `#` anchor) and the
 * anthropology answer-skeleton layout (nested ordered lists, no hints). The wording is
 * invented; only the structure matters.
 */
object Fixtures {

    const val LAW_SECTION = """<div class="header header-blue">Act §999 — Example offence</div>
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

    const val ANTHRO_SKELETON = """<b>Theories of the origin of the institution</b>
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

    /** Every answer token that must never be visible while its cloze is covered. */
    val LAW_SECRETS = mapOf(
        2 to listOf("persuading"),
        3 to listOf("agreeing", "pursuance"),
        4 to listOf("intentionally"),
        5 to listOf("seven years"),
        6 to listOf("good faith")
    )
}
