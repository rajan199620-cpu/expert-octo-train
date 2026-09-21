# SideNote test harness

Rebuilt from scratch on 2026-09-21; the original harness was lost with the
container it lived in. Chromium is at
`/opt/pw-browsers/chromium-1194/chrome-linux/chrome`; drive it with the
globally installed `playwright`. Do not run `playwright install`.

    node tests/run.js      # editor half: wrap an aside in a contenteditable
                           # inside a shadow root, across cloze placements
    node tests/suite.js    # card half: 3 renderer models x 4 field shapes

`suite.js` reads `sn_css.txt` / `sn_js.txt`, which are extracted first:

    python3 -c "import sys; sys.path.insert(0,'.'); import template as T; \
      open('sn_css.txt','w').write(T.CSS); open('sn_js.txt','w').write(T.JS)"

## What the renderer models are, and what they are not

Enhanced Cloze 2.1 v2's own source has never been read - AnkiWeb is blocked
from this environment (403 on CONNECT). `suite.js` therefore runs the card
script against THREE models of what a self-rendering cloze note type does to
the field after the template has run:

  none      no rewrite at all (the control)
  preserve  reassigns innerHTML; elements come back as new objects, so
            attributes survive and event listeners do not
  destroy   rebuilds any line holding a deletion from that line's text, so
            elements on that line are gone outright

`destroy` reproduces the reported symptom exactly: an aside written after a
deletion on the same line renders as plain visible text, while one on a line
of its own hides correctly. That is evidence the real renderer behaves like
this model, not proof. Anything here that says "works with Enhanced Cloze"
means "works against these models".
