# -*- coding: utf-8 -*-
"""SideNote - keep explanations in a cloze note without putting them on the card.

The behaviour you want does NOT need an add-on. An aside is ordinary HTML in
the field:

    <span class="sn" data-sn="why">because police confessions are barred</span>

and the hiding is done by the note type, so it works identically on AnkiDroid
and AnkiMobile, where desktop add-ons do not run. What the add-on gives you is
the authoring side: a shortcut that wraps the selection, a button, the editor
styling that keeps an aside visible while you edit it, and a one-click install
of the note-type half.

Licensed under the GNU AGPL v3, the same licence as Anki.
"""

import json
import os

from aqt import gui_hooks, mw
from aqt.qt import (
    QAction,
    QDialog,
    QDialogButtonBox,
    QLabel,
    QListWidget,
    QListWidgetItem,
    Qt,
    QVBoxLayout,
)
from aqt.utils import askUser, showInfo, tooltip

from . import template

ADDON_PACKAGE = mw.addonManager.addonFromModule(__name__)
mw.addonManager.setWebExports(__name__, r"web/.*\.(js|css)")

DEFAULTS = {
    "label": "why",
    "shortcut": "Ctrl+Shift+D",
    "cycle_shortcut": "Ctrl+Shift+Alt+D",
    "default_target": "nearest",
    "button": True,
    "debug": False,
}


def config() -> dict:
    cfg = dict(DEFAULTS)
    cfg.update(mw.addonManager.getConfig(__name__) or {})
    if not isinstance(cfg.get("label"), str) or not cfg["label"].strip():
        cfg["label"] = DEFAULTS["label"]
    for key in ("shortcut", "cycle_shortcut"):
        if not isinstance(cfg.get(key), str) or not cfg[key].strip():
            cfg[key] = DEFAULTS[key]
    if cfg.get("default_target") not in ("nearest", "all"):
        cfg["default_target"] = DEFAULTS["default_target"]
    return cfg


# --------------------------------------------------------------- injection
#
# Anki's editor is loaded with load_sveltekit_page(), which calls load_url()
# directly and so never fires gui_hooks.webview_will_set_content. The engine is
# therefore evaluated straight into the editor webview. eval() is queued until
# the DOM is ready, so calling it early is safe.

JS_PATH = os.path.join(os.path.dirname(__file__), "web", "sidenote.js")
_ENGINE = None


def engine_source() -> str:
    """Read once. inject() runs on every note load and the engine reconfigures
    itself, so re-reading the file each time buys nothing."""
    global _ENGINE
    if _ENGINE is None:
        with open(JS_PATH, encoding="utf-8") as handle:
            _ENGINE = handle.read()
    return _ENGINE


def build_script() -> str:
    cfg = config()
    payload = json.dumps(
        {
            "label": cfg["label"],
            "shortcut": cfg["shortcut"],
            "cycle_shortcut": cfg["cycle_shortcut"],
            "default_target": cfg["default_target"],
            "debug": cfg["debug"],
        },
        ensure_ascii=False,
    )
    return "globalThis.SIDENOTE_CONFIG = %s;\n%s" % (payload, engine_source())


def inject(editor) -> None:
    web = getattr(editor, "web", None)
    if web is None:
        return
    web.eval(build_script())


def on_editor_did_init(editor) -> None:
    inject(editor)


def on_editor_did_load_note(editor) -> None:
    inject(editor)


# ------------------------------------------------------------------ button

_REASONS = {
    "no-selection": "Select the text you want to tuck away first.",
    "contains-cloze": "That selection contains a cloze deletion. Hiding it "
                      "would make a card you cannot answer.",
    "already": "That is already an aside — the same key removes it.",
    "not-editable": "Click into a field first.",
    "refused": "The editor refused the edit.",
    "not-in-aside": "The cursor is not inside an aside.",
    "no-clozes": "This note has no cloze deletions to attach the aside to.",
}


def _target_text(value) -> str:
    return "every card" if value in (None, "all") else "card c%s" % value


def _on_result(value) -> None:
    if not isinstance(value, dict):
        return
    if value.get("ok"):
        # Which card it landed on is a guess, so it is never a silent one.
        if value.get("action") in ("wrapped", "retargeted"):
            tooltip("Aside shows on %s." % _target_text(value.get("target")),
                    period=2500)
        return
    message = _REASONS.get(value.get("reason"))
    if message:
        tooltip(message, period=3000)


def _call(editor, method: str) -> None:
    web = getattr(editor, "web", None)
    if web is None:
        return
    web.evalWithCallback(
        "window.SideNote ? SideNote.%s() : {ok:false,reason:'refused'}" % method,
        _on_result,
    )


def toggle_aside(editor) -> None:
    _call(editor, "toggle")


def cycle_aside(editor) -> None:
    _call(editor, "cycleTarget")


def on_editor_did_init_buttons(buttons, editor) -> None:
    cfg = config()
    if not cfg.get("button", True):
        return
    buttons.append(
        editor.addButton(
            icon=None,
            cmd="sidenote",
            func=toggle_aside,
            tip="Aside: keep this text in the note, off the card (%s). "
                "%s moves it to the next cloze."
                % (cfg["shortcut"], cfg["cycle_shortcut"]),
            label="…why",
            # Deliberately NO keys= here.
            #
            # addButton(keys=...) makes a Qt QShortcut on the editor widget.
            # It grabs the key before the webview ever sees it, and then runs
            # the handler through call_after_note_saved - a full save round
            # trip - so the toggle finally fires with the selection already
            # gone and nothing happens. The engine binds the key itself, in
            # the page, where the selection still exists.
        )
    )


# ------------------------------------------------------- note type install

class NoteTypePicker(QDialog):
    """Which note types get the card-side half."""

    def __init__(self, parent, notetypes, action):
        super().__init__(parent)
        self.setWindowTitle("SideNote — %s note types" % action)
        self.resize(460, 460)
        layout = QVBoxLayout(self)
        layout.addWidget(QLabel(
            "Tick the note types to %s.<br>"
            "<small>✓ = already installed there. "
            "Cloze note types are listed first.</small>"
            % action
        ))
        self.list = QListWidget()
        for name, nid, done, cloze in notetypes:
            mark = "✓  " if done else "     "
            item = QListWidgetItem(mark + name + ("" if cloze else "   (not cloze)"))
            item.setData(Qt.ItemDataRole.UserRole, nid)
            item.setFlags(item.flags() | Qt.ItemFlag.ItemIsUserCheckable)
            item.setCheckState(Qt.CheckState.Unchecked)
            self.list.addItem(item)
        layout.addWidget(self.list, 1)
        buttons = QDialogButtonBox(
            QDialogButtonBox.StandardButton.Ok | QDialogButtonBox.StandardButton.Cancel
        )
        buttons.accepted.connect(self.accept)
        buttons.rejected.connect(self.reject)
        layout.addWidget(buttons)

    def chosen(self):
        out = []
        for i in range(self.list.count()):
            item = self.list.item(i)
            if item.checkState() == Qt.CheckState.Checked:
                out.append(item.data(Qt.ItemDataRole.UserRole))
        return out


def _looks_cloze(nt) -> bool:
    """Does this note type hold cloze deletions?

    Not "does it say it is one". Enhanced Cloze - the note type these decks are
    actually built on - is a REGULAR note type (type 0) whose templates say
    {{Text}}; the {{cN::}} markers live in the NOTES, not the templates. A
    template scan misses it entirely, so ask the notes."""
    if nt.get("type") == 1:
        return True
    if "cloze" in (nt.get("name") or "").lower():
        return True
    blob = " ".join((t.get("qfmt") or "") + (t.get("afmt") or "") for t in nt["tmpls"])
    if "cloze:" in blob:
        return True
    try:
        rows = mw.col.db.list("select flds from notes where mid = ? limit 20", nt["id"])
    except Exception:
        return False
    return any("{{c" in (r or "") for r in rows)


def _notetypes():
    """Every note type, cloze-looking ones first.

    Deliberately not filtered down to what the add-on guesses is a cloze type.
    A wrong guess would leave you unable to install into the note type you
    actually use, and you know which those are."""
    out = []
    for row in mw.col.models.all_names_and_ids():
        nt = mw.col.models.get(row.id)
        if nt is None:
            continue
        out.append((row.name, row.id, template.installed(nt), _looks_cloze(nt)))
    out.sort(key=lambda r: (not r[3], r[0].lower()))
    return out


def _apply(action: str) -> None:
    if mw.col is None:
        tooltip("Open a profile first.")
        return
    notetypes = _notetypes()
    if not notetypes:
        showInfo("No note types found in this collection.", title="SideNote")
        return
    dialog = NoteTypePicker(mw, notetypes, action)
    if not dialog.exec():
        return
    ids = dialog.chosen()
    if not ids:
        return
    verb = "Add" if action == "install" else "Remove"
    if not askUser(
        "%s the SideNote block in %d note type(s)?\n\n"
        "This edits the Styling and both card templates, between clearly "
        "marked comments. Nothing else in them is touched, and Ctrl+Z undoes "
        "the whole thing." % (verb, len(ids))
    ):
        return

    # One undo step for the lot. Anki keeps 30, and this writes at most a few.
    try:
        entry = mw.col.add_custom_undo_entry("SideNote: %s note types" % action)
    except Exception:
        entry = None

    changed = 0
    for nid in ids:
        nt = mw.col.models.get(nid)
        if nt is None:
            continue
        touched = template.install(nt) if action == "install" else template.remove(nt)
        if touched:
            mw.col.models.update_dict(nt)
            changed += 1

    if entry is not None:
        try:
            mw.col.merge_undo_entries(entry)
        except Exception:
            pass

    mw.reset()
    tooltip("%s %d note type(s). Ctrl+Z undoes it." % (
        "Updated" if action == "install" else "Cleaned", changed))


def install_into_notetypes() -> None:
    _apply("install")


def remove_from_notetypes() -> None:
    _apply("remove")


def show_help() -> None:
    cfg = config()
    showInfo(
        "<b>SideNote</b><br><br>"
        "Select an explanation inside a field and press <b>%s</b> (or the "
        "<b>…why</b> button). It stays in the note and comes off the "
        "card, behind a small chip you tap to read it.<br><br>"
        "The same key with the cursor inside an aside removes it again.<br><br>"
        "An aside is attached to the cloze deletion it <i>follows</i>, so it "
        "appears on that one card only. <b>%s</b> with the cursor inside it "
        "moves it to the next deletion, and past the last one to every card. "
        "The editor shows the target on the aside itself — "
        "<i>why · c2</i>.<br><br>"
        "Asides are hidden by the <b>note type</b>, not by this add-on, so they "
        "behave the same on AnkiDroid and AnkiMobile. Run "
        "<i>Tools → SideNote → Add to note types…</i> once per "
        "note type.<br><br>"
        "A selection containing a cloze deletion is refused: hiding the thing "
        "the card asks for would make a card you cannot answer."
        % (cfg["shortcut"], cfg["cycle_shortcut"]),
        title="SideNote",
    )


def setup_menu() -> None:
    from aqt.qt import QMenu

    menu = QMenu("SideNote", mw)
    for label, handler in (
        ("Add to note types…", install_into_notetypes),
        ("Remove from note types…", remove_from_notetypes),
        (None, None),
        ("How it works", show_help),
    ):
        if label is None:
            menu.addSeparator()
            continue
        action = QAction(label, mw)
        action.triggered.connect(handler)
        menu.addAction(action)
    mw.form.menuTools.addMenu(menu)


gui_hooks.editor_did_init.append(on_editor_did_init)
gui_hooks.editor_did_load_note.append(on_editor_did_load_note)
gui_hooks.editor_did_init_buttons.append(on_editor_did_init_buttons)
gui_hooks.main_window_did_init.append(setup_menu)
