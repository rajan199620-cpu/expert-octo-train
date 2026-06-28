# Anki integration — control Anki from Claude

Drive your Anki collection from Claude (or the terminal) through the
[AnkiConnect](https://foosoft.net/projects/anki-connect/) add-on. Generate
flashcards, add/edit/find/suspend/delete them, tag, get stats, open the browser,
and sync — all programmatically.

Two ways to use it, sharing one client (`client.mjs`):

| Piece | What it is | Use it when |
| --- | --- | --- |
| `mcp-server.mjs` | An **MCP server** exposing Anki as tools | You want Claude to control Anki directly during a chat |
| `anki.mjs` | A **zero-dependency CLI** | You want to run commands yourself / in scripts |

> **Important — where this runs.** AnkiConnect listens on `http://127.0.0.1:8765`
> on the machine where Anki is open. So both the MCP server and the CLI must run
> on **your own computer** (not a cloud session), with the Anki desktop app
> running. A remote/cloud Claude session cannot reach your local Anki.

## 1. Prerequisites (one time)

1. **Install Anki desktop** (https://apps.ankiweb.net) and open it.
2. **Install the AnkiConnect add-on**: Anki → *Tools → Add-ons → Get Add-ons…*
   → paste code **`2055492159`** → restart Anki.
3. Keep Anki **running** whenever you want Claude/the CLI to reach it.
4. (Optional) Set an API key in AnkiConnect's add-on config; then export
   `ANKI_CONNECT_KEY` so the client sends it.

Verify with the CLI:

```bash
node anki/anki.mjs ping
# → AnkiConnect OK — API version 6
```

## 2. Use from the terminal (CLI)

No install needed (Node 18+ for global `fetch`).

```bash
node anki/anki.mjs decks
node anki/anki.mjs create-deck "Spanish::Verbs"
node anki/anki.mjs add --deck "Spanish::Verbs" --front "hablar" --back "to speak" --tags spanish,verbs
node anki/anki.mjs add-batch anki/examples/notes.example.json
node anki/anki.mjs find "deck:Spanish::Verbs tag:verbs"
node anki/anki.mjs suspend "deck:Spanish::Verbs tag:hard"
node anki/anki.mjs browse "deck:Spanish::Verbs"
node anki/anki.mjs stats
node anki/anki.mjs sync
# Full API escape hatch:
node anki/anki.mjs invoke deckNamesAndIds
```

Run `node anki/anki.mjs help` for the complete command list.

### Batch import format

`add-batch` takes a JSON array (or `{ "notes": [...] }`). Each note accepts
`deck`, optional `model` (default `Basic`), either `front`/`back` or an explicit
`fields` map, and optional `tags`. See
[`examples/notes.example.json`](examples/notes.example.json).

This is the format Claude generates when you ask it to "make me cards on X" — it
writes the JSON, then `add-batch` (or the MCP `anki_add_notes` tool) pushes them
into Anki.

## 3. Use from Claude (MCP server)

This lets Claude call Anki tools directly (`anki_add_notes`, `anki_find_notes`,
`anki_suspend`, …, plus `anki_invoke` for the entire API).

```bash
cd anki
npm install        # @modelcontextprotocol/sdk + zod
```

### Register with Claude Code (CLI)

```bash
claude mcp add anki -- node /absolute/path/to/anki/mcp-server.mjs
```

### Register with Claude Desktop

Add to your `claude_desktop_config.json`
(*Settings → Developer → Edit Config*):

```json
{
  "mcpServers": {
    "anki": {
      "command": "node",
      "args": ["/absolute/path/to/anki/mcp-server.mjs"],
      "env": { "ANKI_CONNECT_KEY": "" }
    }
  }
}
```

Restart Claude, make sure Anki is open, then ask things like:

> "Add 10 cards about the Krebs cycle to my Biology deck."
> "Find my suspended Spanish cards and unsuspend the ones tagged easy."
> "How many cards did I review today?"

### Available MCP tools

`anki_ping`, `anki_list_decks`, `anki_create_deck`, `anki_delete_deck`,
`anki_list_models`, `anki_model_fields`, `anki_add_note`, `anki_add_notes`,
`anki_find_notes`, `anki_update_note`, `anki_delete_notes`, `anki_add_tags`,
`anki_remove_tags`, `anki_suspend`, `anki_unsuspend`, `anki_browse`,
`anki_sync`, `anki_stats`, and `anki_invoke` (call any AnkiConnect action).

## Anki search query syntax

Many commands/tools take an Anki search query (same as the Browse window):
`deck:Spanish`, `tag:verb`, `is:due`, `is:suspended`, `added:7`, `"front:hola"`,
combined with spaces (AND) / `or`. See
https://docs.ankiweb.net/searching.html.

## Files

- `client.mjs` — AnkiConnect HTTP client (zero deps, shared core).
- `anki.mjs` — CLI wrapping the client.
- `mcp-server.mjs` — MCP server wrapping the client.
- `package.json` — deps for the MCP server (`npm install`).
- `examples/notes.example.json` — sample batch-import file.

## Troubleshooting

- **"could not reach AnkiConnect"** — Anki isn't running, the add-on isn't
  installed/enabled, or it's on a different host/port (set `ANKI_CONNECT_URL`).
- **Tools work in CLI but Claude can't reach Anki** — the MCP server must run on
  the same machine as Anki; a cloud Claude session can't reach `127.0.0.1`.
- **`addNote` fails on duplicates** — pass `--allow-duplicate` (CLI) /
  `allowDuplicate: true` (tool), or change a field.
