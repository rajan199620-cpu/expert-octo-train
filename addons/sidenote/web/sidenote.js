/* SideNote - mark a span of a field as an aside: kept in the note, hidden on
 * the card until you ask for it.
 *
 * This runs in the EDITOR only. The aside is stored as ordinary HTML in the
 * field:
 *
 *   <span class="sn" data-sn="why">because police confessions are excluded</span>
 *
 * A span, not <details>. execCommand("insertHTML") silently DELETES a
 * <details> in a contenteditable - measured: the body text vanished and only
 * the <summary> survived - and a <details> built by hand puts the caret in
 * the wrong place when you type inside it. A span survives insertHTML exactly,
 * keeps native undo, and stays editable like any other text.
 *
 * Hiding is the card template's job, so it works on AnkiDroid and AnkiMobile
 * too, where desktop add-ons do not run.
 *
 * AGPLv3.
 */
(function () {
  "use strict";

  var G = typeof globalThis !== "undefined" ? globalThis : window;

  function currentConfig() {
    return G.SIDENOTE_CONFIG || {};
  }

  if (G.SideNote) {
    G.SideNote.reconfigure(currentConfig());
    return;
  }

  var LABEL = "why";
  var SHORTCUT = "Ctrl+Shift+D";
  var CYCLE_SHORTCUT = "Ctrl+Shift+Alt+D";
  var TARGET = "nearest";
  var DEBUG = false;

  function reconfigure(cfg) {
    cfg = cfg || {};
    LABEL = (typeof cfg.label === "string" && cfg.label.trim()) || "why";
    SHORTCUT = cfg.shortcut || "Ctrl+Shift+D";
    CYCLE_SHORTCUT = cfg.cycle_shortcut || "Ctrl+Shift+Alt+D";
    TARGET = cfg.default_target === "all" ? "all" : "nearest";
    DEBUG = !!cfg.debug;
    if (DEBUG) console.log("[SideNote] configured", LABEL, SHORTCUT, TARGET);
  }

  reconfigure(currentConfig());

  /* Anki's editor fields live in shadow roots, so an event caught on document
     is retargeted to the host and document.getSelection() hands back an
     element rather than the text node. Read the real node from composedPath
     and ask that node's own root for the selection. */

  function eventNode(e) {
    if (e && typeof e.composedPath === "function") {
      var path = e.composedPath();
      if (path && path.length) return path[0];
    }
    return e ? e.target : null;
  }

  /* The last field that had focus. The toolbar button steals focus from the
     field before its handler runs, so by then document.activeElement is the
     button and there is nothing useful to walk down from. */
  var lastRoot = null;

  function rootSelection(root) {
    if (!root || typeof root.getSelection !== "function") return null;
    var sel = root.getSelection();
    return sel && sel.rangeCount ? sel : null;
  }

  /* Descend through shadow roots from the active element.
     document.getSelection() is NOT a usable fallback on its own: measured
     inside a shadow root, it reports rangeCount 1 with focusNode BODY, so a
     naive check finds "a selection" that points nowhere near the caret and
     every lookup for the enclosing aside fails. */
  function activeSelection() {
    var el = document.activeElement;
    for (var i = 0; i < 10 && el; i++) {
      if (!el.shadowRoot) break;
      var sel = rootSelection(el.shadowRoot);
      if (sel) {
        var fn = sel.focusNode;
        if (fn && fn.nodeType === 1 && fn.shadowRoot) { el = fn; continue; }
        return sel;
      }
      if (el.shadowRoot.activeElement) { el = el.shadowRoot.activeElement; continue; }
      break;
    }
    return null;
  }

  function usable(sel) {
    return sel && sel.rangeCount && fieldOf(sel.getRangeAt(0).startContainer);
  }

  function selectionFor(node) {
    var tries = [];
    if (node && node.getRootNode) tries.push(rootSelection(node.getRootNode()));
    tries.push(activeSelection());
    if (lastRoot) tries.push(rootSelection(lastRoot));
    tries.push(rootSelection(document));
    for (var i = 0; i < tries.length; i++) {
      if (usable(tries[i])) return tries[i];
    }
    return null;
  }

  /* The last selection seen inside a field, kept as two separate memories.

     The toolbar button cannot use the live selection: Anki routes a button
     press through call_after_note_saved, so a whole save round trip happens
     first and the selection is gone by the time the handler runs.

     Two memories, not one, because the save COLLAPSES the selection before it
     disappears - and a single memory is overwritten by that collapse, which
     is exactly the text the button needed. `text` only ever holds a real
     selection; `caret` holds wherever the cursor last was. */
  var lastText = null;
  var lastCaret = null;

  function remember() {
    var sel = activeSelection();
    if (!sel && lastRoot) sel = rootSelection(lastRoot);
    if (!sel || !sel.rangeCount) return;
    var range = sel.getRangeAt(0);
    if (!fieldOf(range.startContainer)) return;
    try {
      var saved = { sel: sel, range: range.cloneRange() };
      lastCaret = saved;
      if (!range.collapsed) lastText = saved;
    } catch (err) {}
  }

  /* Put a remembered selection back. A range whose nodes were replaced by a
     field reload cannot be restored; report nothing rather than acting on a
     position that no longer means anything. */
  function restore(saved) {
    if (!saved) return null;
    try {
      var r = saved.range;
      if (!r.startContainer || !r.startContainer.isConnected) return null;
      if (!fieldOf(r.startContainer)) return null;
      saved.sel.removeAllRanges();
      saved.sel.addRange(r);
      return saved.sel;
    } catch (err) {
      return null;
    }
  }

  /* Is the cursor inside an aside? Answered WITHOUT putting a remembered
     range back, so asking the question cannot change the selection. */
  function enclosingAside(node) {
    var sel = selectionFor(node);
    if (sel && sel.rangeCount) return asideAt(sel.getRangeAt(0).startContainer);
    var saved = lastCaret && lastCaret.range;
    if (saved && saved.startContainer && saved.startContainer.isConnected) {
      return asideAt(saved.startContainer);
    }
    return null;
  }

  function liveOrRemembered(node, needText) {
    var sel = selectionFor(node);
    if (sel && sel.rangeCount && (!needText || !sel.getRangeAt(0).collapsed)) {
      return sel;
    }
    return restore(needText ? lastText : lastCaret);
  }

  function fieldOf(node) {
    var el = node && node.nodeType === 1 ? node : (node && node.parentElement);
    while (el) {
      if (el.isContentEditable) {
        var parent = el.parentElement;
        if (!parent || !parent.isContentEditable) return el;
      }
      el = el.parentElement;
    }
    return null;
  }

  function asideAt(node) {
    var el = node && node.nodeType === 1 ? node : (node && node.parentElement);
    while (el) {
      if (el.classList && el.classList.contains("sn")) return el;
      if (el.isContentEditable && el.parentElement &&
          !el.parentElement.isContentEditable) return null;   // field root
      el = el.parentElement;
    }
    return null;
  }

  function selectionHtml(range) {
    var holder = document.createElement("div");
    holder.appendChild(range.cloneContents());
    return holder.innerHTML;
  }

  function esc(text) {
    return String(text).replace(/&/g, "&amp;").replace(/</g, "&lt;")
      .replace(/>/g, "&gt;").replace(/"/g, "&quot;");
  }

  /* A cloze deletion inside an aside would be a card you cannot answer: the
     thing being asked for is hidden behind the reveal. Refuse rather than
     silently make an unanswerable card. */
  var CLOZE_RE = /\{\{c\d+::/;
  var CLOZE_G = /\{\{c(\d+)::/g;

  /* Which deletion does this aside belong to?

     The cloze it sits after. That is how the sentences are written - the
     deletion, then the reason for it - and it is the only guess that does not
     need a dialog in the middle of authoring. It is never silent: the number
     goes on the chip in the editor and into the tooltip, and the cycle key
     changes it. */
  function textOffset(field, range) {
    try {
      var probe = document.createRange();
      probe.selectNodeContents(field);
      probe.setEnd(range.startContainer, range.startOffset);
      return probe.toString().length;
    } catch (err) {
      return -1;
    }
  }

  function clozeNumbers(field) {
    var text = (field && field.textContent) || "";
    var seen = {}, out = [], m;
    CLOZE_G.lastIndex = 0;
    while ((m = CLOZE_G.exec(text))) {
      var n = String(parseInt(m[1], 10));
      if (!seen[n]) { seen[n] = true; out.push(n); }
    }
    out.sort(function (a, b) { return a - b; });
    return out;
  }

  function nearestCloze(field, range) {
    var offset = textOffset(field, range);
    if (offset < 0) return null;
    var before = ((field && field.textContent) || "").slice(0, offset);
    var found = null, m;
    CLOZE_G.lastIndex = 0;
    while ((m = CLOZE_G.exec(before))) found = String(parseInt(m[1], 10));
    return found;
  }

  function wrap(node) {
    // Asked first, so a cursor parked inside an aside reports the useful
    // reason rather than the generic "nothing selected".
    if (enclosingAside(node)) return { ok: false, reason: "already" };
    var sel = liveOrRemembered(node, true);
    if (!sel || !sel.rangeCount) return { ok: false, reason: "no-selection" };
    var range = sel.getRangeAt(0);
    if (range.collapsed) return { ok: false, reason: "no-selection" };
    var field = fieldOf(range.startContainer);
    if (!field) return { ok: false, reason: "not-editable" };

    var html = selectionHtml(range);
    if (CLOZE_RE.test(html)) return { ok: false, reason: "contains-cloze" };
    if (/class="[^"]*\bsn\b/.test(html)) return { ok: false, reason: "already" };

    var target = TARGET === "all" ? null : nearestCloze(field, range);
    var attr = target ? ' data-sn-c="' + esc(target) + '"' : "";
    var out = '<span class="sn" data-sn="' + esc(LABEL) + '"' + attr +
      ' data-sn-new="1">' + html + "</span>";
    var done = false;
    try {
      done = document.execCommand("insertHTML", false, out);
    } catch (err) {
      done = false;
    }
    if (!done) return { ok: false, reason: "refused" };
    tidy(field);
    lastText = null;            // consumed; never act on it twice
    return { ok: true, action: "wrapped", target: target || "all" };
  }

  /* Move the aside under the cursor to the next deletion in the note, and
     round to "every card" after the last one. */
  function cycleTarget(node) {
    var sel = liveOrRemembered(node, false);
    if (!sel || !sel.rangeCount) return { ok: false, reason: "no-selection" };
    var aside = asideAt(sel.getRangeAt(0).startContainer);
    if (!aside) return { ok: false, reason: "not-in-aside" };
    var field = fieldOf(aside);
    var numbers = clozeNumbers(field);
    if (!numbers.length) return { ok: false, reason: "no-clozes" };

    var current = aside.getAttribute("data-sn-c");
    var next;
    if (!current) {
      next = numbers[0];
    } else {
      var i = numbers.indexOf(String(parseInt(current, 10)));
      next = (i === -1 || i === numbers.length - 1) ? null : numbers[i + 1];
    }
    if (next) aside.setAttribute("data-sn-c", next);
    else aside.removeAttribute("data-sn-c");
    notifyInput(field || aside.parentNode);
    return { ok: true, action: "retargeted", target: next || "all" };
  }

  /* Chromium's insertHTML pads the join with a non-breaking space when the
     insertion point follows or precedes one. It looks identical on screen and
     it is not: Anki's search is literal, so "magistrate because" stops
     matching a note whose text now reads "magistrate\u00a0because". Swap back
     the padding we caused, and only that. */
  function tidy(field) {
    var span = field && field.querySelector ?
      field.querySelector('span.sn[data-sn-new]') : null;
    if (!span) return;
    span.removeAttribute("data-sn-new");
    var before = span.previousSibling;
    if (before && before.nodeType === 3 && /\u00a0$/.test(before.nodeValue)) {
      before.nodeValue = before.nodeValue.replace(/\u00a0$/, " ");
    }
    var after = span.nextSibling;
    if (after && after.nodeType === 3 && /^\u00a0/.test(after.nodeValue)) {
      after.nodeValue = after.nodeValue.replace(/^\u00a0/, " ");
    }
  }

  /* Editing the DOM directly fires no input event, so Anki's editor never
     learns the field changed and may not save it. Say so ourselves. */
  function notifyInput(el) {
    var target = el && el.nodeType === 1 ? el : (el && el.parentElement);
    if (!target || typeof target.dispatchEvent !== "function") return;
    var ev;
    try {
      ev = new InputEvent("input", { bubbles: true, composed: true,
                                     inputType: "deleteByDrag" });
    } catch (err) {
      try { ev = new Event("input", { bubbles: true, composed: true }); }
      catch (err2) { return; }
    }
    try { target.dispatchEvent(ev); } catch (err3) {}
  }

  function unwrap(node) {
    var sel = liveOrRemembered(node, false);
    if (!sel || !sel.rangeCount) return { ok: false, reason: "no-selection" };
    var aside = asideAt(sel.getRangeAt(0).startContainer);
    if (!aside) return { ok: false, reason: "not-in-aside" };
    var field = fieldOf(aside);

    /* Plain DOM, not execCommand. Selecting the span with selectNode() and
       calling insertHTML on it RETURNS TRUE AND CHANGES NOTHING - measured -
       so the aside looked un-removable. Moving the children out by hand is
       the only reliable way; the cost is one step of native undo, which is
       why wrapping (the common direction) still goes through execCommand. */
    var parent = aside.parentNode;
    if (!parent) return { ok: false, reason: "detached" };
    var first = aside.firstChild;
    var last = aside.lastChild;
    while (aside.firstChild) parent.insertBefore(aside.firstChild, aside);
    parent.removeChild(aside);
    if (parent.normalize) parent.normalize();

    if (first) {
      var range = document.createRange();
      try {
        range.setStartBefore(first);
        range.setEndAfter(last || first);
        sel.removeAllRanges();
        sel.addRange(range);
      } catch (err) {}
    }
    notifyInput(field || parent);
    lastText = null;
    return { ok: true, action: "unwrapped" };
  }

  /* One shortcut, both directions: inside an aside it removes it, otherwise it
     makes one out of the selection. */
  function toggle(node) {
    if (enclosingAside(node)) return unwrap(node);
    return wrap(node);
  }

  function matches(e, spec) {
    var parts = String(spec || "").split("+");
    var key = parts.pop();
    var want = { ctrl: false, shift: false, alt: false, meta: false };
    parts.forEach(function (m) {
      m = m.toLowerCase();
      if (m === "ctrl" || m === "control") want.ctrl = true;
      else if (m === "shift") want.shift = true;
      else if (m === "alt") want.alt = true;
      else if (m === "meta" || m === "cmd") want.meta = true;
    });
    if (e.ctrlKey !== want.ctrl || e.shiftKey !== want.shift ||
        e.altKey !== want.alt || e.metaKey !== want.meta) return false;
    return String(e.key).toLowerCase() === String(key).toLowerCase();
  }

  function onKeyDown(e) {
    // The cycle key is the toggle key plus Alt, so it must be tested first or
    // a loose match would swallow it.
    var cycling = matches(e, CYCLE_SHORTCUT);
    if (!cycling && !matches(e, SHORTCUT)) return;
    var node = eventNode(e);
    if (!fieldOf(node)) return;
    e.preventDefault();
    e.stopPropagation();
    // Nothing else gets a turn at this key, in this page or in Anki's.
    if (e.stopImmediatePropagation) e.stopImmediatePropagation();
    var result = cycling ? cycleTarget(node) : toggle(node);
    if (DEBUG) console.log("[SideNote]", result);
    G.SideNote._last = result;
  }

  /* Editor styling. Fields live in shadow roots, so a stylesheet in the editor
     document never reaches them - it has to be injected into each root. An
     aside is shown expanded and marked here: you cannot edit what you cannot
     see, and the point of the collapse is the card, not the editor. */
  var EDITOR_CSS =
    'span.sn{background:rgba(128,128,128,.16);border-bottom:1px dotted currentColor;' +
    'border-radius:3px;padding:0 .15em;}' +
    'span.sn::before{content:attr(data-sn);font-size:.7em;opacity:.55;' +
    'letter-spacing:.06em;text-transform:uppercase;margin-right:.35em;}' +
    // Which card it will appear on, visible while you write rather than
    // guessable only from the HTML editor.
    'span.sn[data-sn-c]::before{content:attr(data-sn) " \u00b7 c" attr(data-sn-c);}';

  function styleRoot(root) {
    if (!root || root.nodeType !== 11) return;
    try {
      if (root.querySelector && root.querySelector("style[data-sn-css]")) return;
      var st = document.createElement("style");
      st.setAttribute("data-sn-css", "1");
      st.textContent = EDITOR_CSS;
      root.appendChild(st);
    } catch (err) {}
  }

  function sweep(node, depth) {
    if (!node || (depth || 0) > 8) return;
    var els = node.querySelectorAll ? node.querySelectorAll("*") : [];
    for (var i = 0; i < els.length; i++) {
      if (els[i].shadowRoot) {
        styleRoot(els[i].shadowRoot);
        sweep(els[i].shadowRoot, (depth || 0) + 1);
      }
    }
  }

  function restyle() { sweep(document, 0); }

  document.addEventListener("keydown", onKeyDown, true);
  document.addEventListener("selectionchange", remember, true);
  document.addEventListener("mouseup", remember, true);
  document.addEventListener("keyup", remember, true);

  var hooked = typeof WeakSet === "function" ? new WeakSet() : null;
  document.addEventListener("focusin", function (e) {
    var node = eventNode(e);
    if (fieldOf(node) && node && node.getRootNode) {
      lastRoot = node.getRootNode();
      styleRoot(lastRoot);
    }
    if (!hooked) return;
    var root = node && node.getRootNode ? node.getRootNode() : null;
    if (!root || root === document || root.nodeType !== 11) return;
    if (hooked.has(root)) return;
    hooked.add(root);
    root.addEventListener("keydown", onKeyDown, true);
  }, true);

  restyle();
  if (typeof MutationObserver === "function") {
    // Fields are created and replaced as notes load; keep the styling with them.
    new MutationObserver(function () { restyle(); })
      .observe(document.documentElement, { childList: true, subtree: true });
  }

  G.SideNote = {
    reconfigure: reconfigure,
    restyle: restyle,
    // No argument: these are what the toolbar button calls, and by then the
    // field has already lost focus, so they resolve the caret themselves.
    toggle: function () { return toggle(null); },
    wrap: function () { return wrap(null); },
    unwrap: function () { return unwrap(null); },
    cycleTarget: function () { return cycleTarget(null); },
    label: function () { return LABEL; },
    _last: null
  };
})();
