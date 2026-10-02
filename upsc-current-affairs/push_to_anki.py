#!/usr/bin/env python3
"""Push an Anki patch (.tsv from this folder) into Anki via AnkiConnect.

Dry run by default: prints what it would add/update and changes nothing.
Pass --apply to write. Run it on the computer where Anki + AnkiConnect run.

  python3 push_to_anki.py ethics/anki_ethics_bank.tsv --deck "UPSC::Ethics" --update-existing
  python3 push_to_anki.py 2026-10-02/anki_patch_TH_2026-10-02.tsv --deck "UPSC::Current Affairs" --apply
"""
import argparse, csv, json, sys, urllib.request

FIELDS = {"Basic": ("Front", "Back"), "Cloze": ("Text", "Back Extra")}


def anki(action, url, **params):
    req = json.dumps({"action": action, "version": 6, "params": params}).encode()
    with urllib.request.urlopen(urllib.request.Request(url, req), timeout=10) as r:
        out = json.load(r)
    if out.get("error"):
        sys.exit(f"AnkiConnect error on {action}: {out['error']}")
    return out["result"]


def read_patch(path):
    """Parse a patch written by this project: '#key:value' headers, then tab-separated rows."""
    headers, rows = {}, []
    with open(path, newline="") as fh:
        for row in csv.reader(fh, delimiter="\t"):
            if row and row[0].startswith("#"):
                k, _, v = row[0][1:].partition(":")
                headers[k] = v
            elif row:
                rows.append(row)
    notes = []
    nt_col = int(headers["notetype column"]) - 1 if "notetype column" in headers else None
    tag_col = int(headers["tags column"]) - 1
    for row in rows:
        notetype = row[nt_col] if nt_col is not None else headers["notetype"]
        values = [v for i, v in enumerate(row) if i not in (nt_col, tag_col)]
        notes.append({"modelName": notetype, "fields": dict(zip(FIELDS[notetype], values)),
                      "tags": row[tag_col].split()})
    return notes


def search_escape(text):
    # Quote-safe exact match on a field in Anki search syntax.
    for ch in '\\"*_':
        text = text.replace(ch, "\\" + ch)
    return text


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("patch")
    ap.add_argument("--deck", required=True)
    ap.add_argument("--update-existing", action="store_true",
                    help="if a note of the same type has the same first field, update it instead of adding")
    ap.add_argument("--apply", action="store_true", help="actually write to Anki (default: dry run)")
    ap.add_argument("--url", default="http://127.0.0.1:8765")
    a = ap.parse_args()

    print("AnkiConnect version:", anki("version", a.url))
    notes = read_patch(a.patch)
    plan = []  # (action, note, existing_note_id)
    for n in notes:
        first_field, first_value = next(iter(n["fields"].items()))
        found = anki("findNotes", a.url, query=f'"note:{n["modelName"]}" "{first_field}:{search_escape(first_value)}"')
        if found and a.update_existing:
            plan.append(("update", n, found[0]))
        elif found:
            plan.append(("skip-duplicate", n, found[0]))
        else:
            plan.append(("add", n, None))

    for action, n, _ in plan:
        print(f"{action:15} {next(iter(n['fields'].values()))[:90]}")
    counts = {k: sum(1 for p in plan if p[0] == k) for k in ("add", "update", "skip-duplicate")}
    print(counts, "| deck:", a.deck)
    if not a.apply:
        print("Dry run: nothing changed. Re-run with --apply to write.")
        return

    if a.deck not in anki("deckNames", a.url):
        anki("createDeck", a.url, deck=a.deck)
    for action, n, nid in plan:
        if action == "add":
            anki("addNote", a.url, note={**n, "deckName": a.deck, "options": {"allowDuplicate": False}})
        elif action == "update":
            anki("updateNoteFields", a.url, note={"id": nid, "fields": n["fields"]})
            anki("addTags", a.url, notes=[nid], tags=" ".join(n["tags"]))
    print("Done:", counts)


if __name__ == "__main__":
    main()
