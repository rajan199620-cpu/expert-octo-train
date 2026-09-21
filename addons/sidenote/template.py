# -*- coding: utf-8 -*-
"""The card-side half of SideNote.

This is the part that has to live in the NOTE TYPE, not in the add-on. Desktop
add-ons do not run on AnkiDroid or AnkiMobile; card templates do. Putting the
hide-and-reveal here is what makes an aside behave the same on the phone you
revise on as on the desktop you author on.

Everything is written between markers so it can be replaced or removed cleanly,
and it is keyed off a data attribute the script itself sets - so if the script
does not run for any reason, the aside is simply ordinary visible text rather
than text you cannot get back.
"""

START = "/*== SideNote start ==*/"
END = "/*== SideNote end ==*/"
HTML_START = "<!--== SideNote start ==-->"
HTML_END = "<!--== SideNote end ==-->"

CSS = """
/* An aside is hidden only once the script has marked it. Without that, a
   template that lost its script would hide text with no way to bring it back. */
span.sn[data-sn-ready]:not(.sn-open) { display: none; }
/* Belongs to a different cloze than the one this card is asking. Gone
   entirely - no chip, no trace. */
span.sn[data-sn-off] { display: none !important; }
span.sn.sn-open {
  display: inline;
  margin-left: .25em;
  border-bottom: 1px dotted currentColor;
  opacity: .88;
}
.sn-chip::before { content: attr(data-sn-chip); }
.sn-chip {
  display: inline-block;
  margin: 0 .15em;
  padding: 0 .45em;
  border: 1px solid currentColor;
  border-radius: 999px;
  font-size: .72em;
  line-height: 1.5;
  vertical-align: baseline;
  opacity: .55;
  cursor: pointer;
  user-select: none;
  -webkit-user-select: none;
}
.sn-chip:hover { opacity: .9; }
.sn-chip.sn-chip-open { opacity: .3; }
"""

JS = """
<script>
(function () {
  /* Which cloze is this card asking?

     Anki tags the active deletion <span class="cloze" data-ordinal="N"> and
     the rest "cloze-inactive", on both the question and the answer side, so
     the DOM says it outright. Note types that render clozes themselves -
     Enhanced Cloze is a regular note type with one template per deletion -
     get the number written into the template instead, as __snCard.

     Unknown means show everything. An aside you cannot reach is worse than an
     aside on the wrong card. */
  function ordinal() {
    if (window.__snCard) return String(window.__snCard);
    var el = document.querySelector(".cloze[data-ordinal]");
    return el ? el.getAttribute("data-ordinal") : null;
  }

  function root() { return document.body || document.documentElement; }

  /* Every aside this card started with.

     A note type that renders its own clozes rewrites the field after this
     script has run. MEASURED, against both shapes that rewrite can take:

       - it reassigns innerHTML and our span comes back as a NEW element.
         Attributes survive the round trip, event listeners do not, so the
         chip is still on screen and clicking it does nothing.
       - it rebuilds a line that holds a deletion from that line's TEXT, and
         our span on that line is gone outright. The explanation is then
         ordinary visible text, which is what a missing install looks like.

     The second is why an aside written after a deletion on the same line did
     not hide while one on a line of its own did: only the lines carrying a
     deletion get rebuilt. Neither is recoverable by re-scanning for spans
     that no longer exist, so remember them while they are still here. */
  var REG = [];
  var nextId = 1;

  function record(el) {
    if (el.getAttribute("data-sn-id")) return;
    var id = "s" + (nextId++);
    el.setAttribute("data-sn-id", id);
    REG.push({
      id: id,
      label: el.getAttribute("data-sn") || "why",
      c: el.getAttribute("data-sn-c") || "",
      text: el.textContent
    });
  }

  function insideSn(node) {
    var el = node && node.nodeType === 1 ? node : (node && node.parentNode);
    while (el && el.nodeType === 1) {
      if (el.classList &&
          (el.classList.contains("sn") || el.classList.contains("sn-chip"))) {
        return true;
      }
      el = el.parentNode;
    }
    return false;
  }

  /* Put an aside back around its own text.

     Only when that text occurs exactly ONCE outside the asides already on the
     card. Two candidates means no way to tell which was the explanation, and
     hiding the wrong half of a sentence is worse than leaving this one
     visible - which is only what it already does today. */
  function candidates(entry) {
    var out = [];
    var walk = document.createTreeWalker(root(), NodeFilter.SHOW_TEXT, null, false);
    var node;
    while ((node = walk.nextNode())) {
      if (insideSn(node)) continue;
      var from = 0, at;
      while ((at = node.nodeValue.indexOf(entry.text, from)) >= 0) {
        out.push({ node: node, at: at });
        from = at + entry.text.length;
        if (out.length > 1) return out;     // ambiguous; no need to count on
      }
    }
    return out;
  }

  function reWrap(entry) {
    if (!entry.text) return false;
    var found = candidates(entry);
    if (found.length !== 1) return false;
    var node = found[0].node;
    var mine = node.splitText(found[0].at);
    if (mine.nodeValue.length > entry.text.length) {
      mine.splitText(entry.text.length);
    }
    var span = document.createElement("span");
    span.className = "sn";
    span.setAttribute("data-sn", entry.label);
    if (entry.c) span.setAttribute("data-sn-c", entry.c);
    span.setAttribute("data-sn-id", entry.id);
    mine.parentNode.insertBefore(span, mine);
    span.appendChild(mine);
    return true;
  }

  function restoreMissing() {
    for (var i = 0; i < REG.length; i++) {
      var entry = REG[i];
      if (root().querySelector('span.sn[data-sn-id="' + entry.id + '"]')) continue;
      reWrap(entry);
    }
  }

  /* Chip and handlers.

     `data-sn-ready` is an ATTRIBUTE, so it survives a renderer that reassigns
     innerHTML - which is what we want for the CSS, because the aside stays
     hidden across the rewrite instead of flashing into view. Whether the
     handlers are still attached is a different question and an attribute
     cannot answer it: listeners die with the old element object. A plain JS
     property does die with it, so that is what decides whether to re-bind. */
  function build(el) {
    var chip = el.previousSibling;
    if (!(chip && chip.nodeType === 1 && chip.classList &&
          chip.classList.contains("sn-chip"))) {
      chip = document.createElement("span");
      chip.className = "sn-chip";
      chip.setAttribute("role", "button");
      chip.setAttribute("tabindex", "0");
      el.parentNode.insertBefore(chip, el);
    }
    var label = el.getAttribute("data-sn") || "why";
    // An attribute, drawn by .sn-chip::before. Text here would be a childList
    // mutation that wakes the observer and scans again forever, and a
    // renderer rebuilding a line from its text would swallow the label into
    // the sentence.
    if (chip.getAttribute("data-sn-chip") !== label) {
      chip.setAttribute("data-sn-chip", label);
    }
    el.setAttribute("data-sn-ready", "1");
    var open = el.classList.contains("sn-open");
    chip.classList.toggle("sn-chip-open", open);
    if (el.__snBound && chip.__snBound) return;
    el.__snBound = true;
    chip.__snBound = true;
    function toggle(e) {
      if (e) { e.preventDefault(); e.stopPropagation(); }
      var now = el.classList.toggle("sn-open");
      chip.classList.toggle("sn-chip-open", now);
    }
    chip.addEventListener("click", toggle);
    chip.addEventListener("keydown", function (e) {
      if (e.key === "Enter" || e.key === " ") toggle(e);
    });
  }

  /* Every deletion this note has, so an aside pointing at one that no longer
     exists is not hidden on all of them. Delete a cloze and its aside becomes
     an orphan; orphans show rather than disappear. */
  function known() {
    var out = {};
    var spans = document.querySelectorAll("[data-ordinal]");
    for (var i = 0; i < spans.length; i++) {
      out[spans[i].getAttribute("data-ordinal")] = true;
    }
    return out;
  }

  function scan() {
    restoreMissing();
    var now = ordinal();
    var here = known();
    var checkable = window.__snCard ? null : here;
    var all = document.querySelectorAll("span.sn");
    for (var i = 0; i < all.length; i++) {
      var el = all[i];
      record(el);
      var want = el.getAttribute("data-sn-c");
      var orphan = checkable && want && !checkable[want];
      if (want && now && want !== now && !orphan) {
        el.setAttribute("data-sn-off", "1");
        continue;
      }
      el.removeAttribute("data-sn-off");
      build(el);
    }
  }
  /* Scan once is not enough: the renderer runs after this script does, and
     again every time you reveal a deletion. Watch the card and re-apply. */
  var scanning = false;
  var queued = false;

  function rescan() {
    if (scanning || queued) return;
    queued = true;
    var run = function () { queued = false; guarded(); };
    if (typeof requestAnimationFrame === "function") requestAnimationFrame(run);
    else setTimeout(run, 16);
  }

  function guarded() {
    scanning = true;
    try { scan(); } finally { scanning = false; }
  }

  guarded();
  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", guarded);
  }
  // A few early passes cover a renderer that finishes before the observer is
  // attached; the observer covers everything after that.
  setTimeout(guarded, 50);
  setTimeout(guarded, 300);

  if (typeof MutationObserver === "function") {
    // Anki reuses the reviewer webview and this script runs again on every
    // card, so drop the previous card's observer instead of stacking one per
    // card for the whole session.
    if (window.__snObserver) {
      try { window.__snObserver.disconnect(); } catch (e) {}
    }
    window.__snObserver = new MutationObserver(rescan);
    // childList only: scan() sets attributes, and observing those would have
    // it retrigger itself.
    window.__snObserver.observe(root(), { childList: true, subtree: true });
  }
})();
</script>
"""


def css_block() -> str:
    return "\n%s%s%s\n" % (START, CSS, END)


def js_block(card_ordinal=None) -> str:
    """`card_ordinal` is written for note types that render their own clozes,
    where one template means one deletion. It is always written, null included:
    Anki reuses the reviewer webview, so a value left over from the previous
    card would otherwise decide what this one shows."""
    declare = "<script>window.__snCard = %s;</script>" % (
        int(card_ordinal) if card_ordinal else "null"
    )
    return "\n%s%s%s%s\n" % (HTML_START, declare, JS, HTML_END)


def _replace(text: str, start: str, end: str, block: str) -> str:
    """Idempotent: replace an existing block, otherwise append one."""
    text = text or ""
    i = text.find(start)
    if i == -1:
        return text.rstrip() + "\n" + block
    j = text.find(end, i)
    if j == -1:
        return text[:i].rstrip() + "\n" + block
    return text[:i].rstrip() + "\n" + block + text[j + len(end):].lstrip("\n")


def _strip(text: str, start: str, end: str) -> str:
    text = text or ""
    i = text.find(start)
    if i == -1:
        return text
    j = text.find(end, i)
    if j == -1:
        return text[:i].rstrip() + "\n"
    return (text[:i].rstrip() + "\n" + text[j + len(end):].lstrip("\n")).strip() + "\n"


def _ordinals_for(notetype: dict):
    """Per-template cloze numbers, or None to let the card say at render time.

    A real Cloze note type has ONE template shared by every deletion, so the
    number can only come from the rendered card. A note type that fakes clozes
    with one template per deletion - Enhanced Cloze, "Card 3" showing c3 - can
    have it written in."""
    if notetype.get("type") == 1 or len(notetype["tmpls"]) < 2:
        return [None] * len(notetype["tmpls"])
    return [i + 1 for i in range(len(notetype["tmpls"]))]


def install(notetype: dict) -> bool:
    """Add (or refresh) the block in a note type. True if anything changed."""
    before = (notetype.get("css", ""),
              [(t.get("qfmt", ""), t.get("afmt", "")) for t in notetype["tmpls"]])
    notetype["css"] = _replace(notetype.get("css", ""), START, END, css_block())
    ordinals = _ordinals_for(notetype)
    for index, tmpl in enumerate(notetype["tmpls"]):
        block = js_block(ordinals[index])
        for side in ("qfmt", "afmt"):
            tmpl[side] = _replace(tmpl.get(side, ""), HTML_START, HTML_END, block)
    after = (notetype.get("css", ""),
             [(t.get("qfmt", ""), t.get("afmt", "")) for t in notetype["tmpls"]])
    return before != after


def remove(notetype: dict) -> bool:
    before = (notetype.get("css", ""),
              [(t.get("qfmt", ""), t.get("afmt", "")) for t in notetype["tmpls"]])
    notetype["css"] = _strip(notetype.get("css", ""), START, END)
    for tmpl in notetype["tmpls"]:
        for side in ("qfmt", "afmt"):
            tmpl[side] = _strip(tmpl.get(side, ""), HTML_START, HTML_END)
    after = (notetype.get("css", ""),
             [(t.get("qfmt", ""), t.get("afmt", "")) for t in notetype["tmpls"]])
    return before != after


def installed(notetype: dict) -> bool:
    if START not in (notetype.get("css") or ""):
        return False
    for tmpl in notetype["tmpls"]:
        if HTML_START not in (tmpl.get("qfmt") or ""):
            return False
        if HTML_START not in (tmpl.get("afmt") or ""):
            return False
    return True
