# SideNote

Keep an explanation inside a cloze note without putting it on the card.
Select it, press **Ctrl+Shift+D**, and it comes off the card behind a small
chip you tap when you want it.

> A confession must be recorded by {{c1::a magistrate}} under
> {{c2::s.183 BNSS}} `why▸`

Tap `why` and the rest appears: *because a confession to police is barred by
s.23 BSA*.

## You do not need this add-on for the behaviour

An aside is ordinary HTML in the field:

```html
<span class="sn" data-sn="why">because a confession to police is barred</span>
```

and the hiding is done by the **note type**, not by the add-on. That is
deliberate: desktop add-ons do not run on AnkiDroid or AnkiMobile, card
templates do. Your asides behave the same on your phone as on your desktop,
and they sync like any other note content.

What the add-on gives you is the authoring half — the shortcut, the button,
the editor styling, and a one-click install of the note-type half.

## Setup, once

**Tools → SideNote → Add to note types…**, tick your cloze note type, confirm.
It writes one block into the Styling and one into each card template, between
marked comments, and nothing else is touched. Ctrl+Z undoes the whole thing,
and **Remove from note types…** takes it back out cleanly.

Cloze note types are listed first. Enhanced Cloze is detected by looking at
your actual notes, not at what the note type calls itself — it is a *regular*
note type whose `{{cN::}}` markers live in the notes, so anything that checks
the note type's own flag misses it.

## Use

| Action | Key |
|---|---|
| Turn the selection into an aside | `Ctrl+Shift+D`, or the **…why** button |
| Turn an aside back into plain text | `Ctrl+Shift+D` with the cursor inside it |
| Move it to the next cloze | `Ctrl+Shift+Alt+D` with the cursor inside it |
| Read one while reviewing | tap or click the chip |

## One aside, one card

A cloze note makes one card per deletion, and an explanation usually belongs to
exactly one of them. So an aside is attached to **the deletion it follows** and
appears on that card only:

> A confession is recorded by {{c1::a magistrate}} `why▸`, under
> {{c2::s.183 BNSS}} `why▸`, and s.23 BSA bars {{c3::police confessions}} `why▸`

Reviewing the c2 card shows the c2 reason and neither of the others.

The guess is never silent. The editor writes the target on the aside itself —
**why · c2** — the tooltip says where it landed, and `Ctrl+Shift+Alt+D` moves
it: c1 → c2 → c3 → every card → c1.

Text that sits before any deletion, or a note with no deletions yet, gets no
target and rides every card. Set `default_target` to `all` if you would rather
that were the default everywhere.

Delete the cloze an aside points at and the aside becomes an orphan — it shows
on every card rather than vanishing from all of them.

The label on the chip is `why` by default; change `label` in the config, or
just edit the `data-sn` attribute of an individual aside in the HTML editor.

Anki uses `Ctrl+Shift+D` for *Set Due Date* — but only in the reviewer, which
is a different window, so the two never meet. Change `shortcut` in the config
if you would rather they did not share a key at all.

## What it refuses to do

**A selection containing a cloze deletion.** Hiding `{{c1::a magistrate}}`
behind a chip makes a card whose answer is the thing you cannot see. You get a
tooltip instead.

## Why a `<span>` and not `<details>`

`<details>` looks like the obvious answer and is not. Measured in a
contenteditable field:

- `execCommand("insertHTML")` **deletes** a `<details>` — the body text
  vanished and only the `<summary>` survived.
- A `<details>` built by hand does survive, but typing inside a collapsed one
  puts the characters in the wrong place (`whyXYZbecause`).

A span survives insertion exactly, keeps Anki's native undo, and stays
editable like any other text.

## If the script does not run

The aside is plain visible text. It is never hidden with no way back — the
CSS only hides an aside the script has marked, so a template that lost its
script shows the explanation rather than swallowing it.

## What 1.4 fixed

Two faults in the card-side script, both of them only visible on a note type
that renders its own cloze deletions and therefore rewrites the field after
the template has run. Both were measured against two models of that rewrite -
one that reassigns innerHTML, one that rebuilds a cloze-carrying line from its
text - because the real renderer's source was not available to read.

1. **An aside written after a deletion on the same line never hid.** A rewrite
   that rebuilds a line from its text destroys the span outright, and the
   script had no way back from that: it re-scanned for spans that no longer
   existed. An aside on a line of its own survived because lines without a
   deletion are not rebuilt - which is exactly the difference that showed up
   in use. The script now remembers each aside while it is still on the card
   and puts it back around its own text afterwards. Where that text occurs
   more than once it refuses to guess and leaves the explanation visible,
   because hiding the wrong half of a sentence is worse than hiding nothing.

2. **The reveal chip went dead after any re-render.** Attributes survive a
   round trip through innerHTML; event listeners do not. The script used an
   attribute to decide whether it had already wired an aside up, so after the
   first rewrite the chip was still on screen and clicking it did nothing -
   the explanation was hidden and unreachable. It now decides from a plain JS
   property, which dies with the element it was set on, and re-binds. This one
   affected every aside on such a note type, including the ones that looked
   like they worked.

The chip's label also moved from text into an attribute drawn by CSS, so a
renderer that rebuilds a line from its text cannot swallow the word "why" into
the middle of the sentence.

**The template changed, so Tools -> SideNote -> Add to note types... must be
run again.** It is not a toggle and is safe to run twice.

## What 1.3 fixed

**The shortcut did nothing.** The button was registered with Anki's
`addButton(keys=…)`, which creates a *Qt* shortcut on the editor widget. That
grabs the key before the web page ever sees it, and then runs the handler
through `call_after_note_saved` — a full save round trip — so by the time the
toggle fired, the selection it needed was gone. The key is now bound in the
page, where the selection still exists.

The **button** goes through that save whatever we do, so the engine now
remembers the last real selection and puts it back. A remembered position
whose nodes were replaced by a field reload is refused rather than acted on,
so the button can never wrap the wrong text.

## What 1.2 fixed

**Asides showed as plain text on Enhanced Cloze.** Note types that render
their own clozes rewrite the field *after* this script has run — and again
every time you reveal a deletion. That wiped the chip and put the explanation
back on screen, which looks exactly like a missing install. The card is now
watched and the asides re-applied, with the previous card's observer dropped
so they do not stack up over a review session.

Reproduced and fixed against a mock that re-renders at 60 ms, at 500 ms, and
five times in a row.

## What 1.1 added

Per-card asides, and one bug worth naming: resolving "which field am I in" was
returning the nearest editable *element*, which inside a contenteditable is the
`<span>` or the `<b>` under the cursor rather than the field. Everything inside
a contenteditable reports `isContentEditable`, so the first match is almost
never the right one. The cloze inference was reading a few words of context
instead of the note, and the cycle key reported that a note full of deletions
had none.

## Licence

GNU AGPL v3, matching Anki. Written from scratch.
