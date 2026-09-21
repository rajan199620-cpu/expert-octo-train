## SideNote

An aside is `<span class="sn" data-sn="LABEL" data-sn-c="2">text</span>` in
the field. The note type hides it; this add-on writes it. `data-sn-c` is the
cloze number it belongs to — leave it off and the aside shows on every card of
the note.

| Key | Meaning |
|---|---|
| `label` | Default chip label for new asides. `why` by default. Existing asides keep the label they were made with. |
| `shortcut` | Wrap the selection, or unwrap the aside under the cursor. Default `Ctrl+Shift+D`. Anki's editor already uses Ctrl+Shift+C/H/P/T/V/X, so avoid those. |
| `cycle_shortcut` | Move the aside under the cursor to the next cloze, and past the last one to every card. Default `Ctrl+Shift+Alt+D`. |
| `default_target` | `nearest` (default) attaches a new aside to the cloze deletion it follows, so it shows on that card only. `all` attaches nothing, and the aside rides every card of the note. |
| `button` | Show the **…why** button in the editor toolbar. |
| `debug` | Log to the editor webview console. |

Run **Tools → SideNote → Add to note types…** once per note type. Without it
asides are simply visible text — the add-on alone does not hide anything, and
that is what makes them work on AnkiDroid and AnkiMobile.
