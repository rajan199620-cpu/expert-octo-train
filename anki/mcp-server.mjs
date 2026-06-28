#!/usr/bin/env node
// mcp-server.mjs — Model Context Protocol server exposing Anki (via AnkiConnect)
// as tools. Run this locally and register it with Claude Code / Claude Desktop
// so Claude can control your Anki collection directly.
//
// Setup:
//   cd anki && npm install        # installs @modelcontextprotocol/sdk + zod
// Register (Claude Desktop config / Claude Code `claude mcp add`):
//   command: node   args: ["/absolute/path/to/anki/mcp-server.mjs"]
//
// Anki must be running with the AnkiConnect add-on enabled.

import { McpServer } from '@modelcontextprotocol/sdk/server/mcp.js';
import { StdioServerTransport } from '@modelcontextprotocol/sdk/server/stdio.js';
import { z } from 'zod';
import { AnkiClient, AnkiError, normalizeNote } from './client.mjs';

const anki = new AnkiClient();

const server = new McpServer({ name: 'anki', version: '1.0.0' });

/** Wrap a handler so AnkiConnect errors become readable tool errors, and
 *  results are returned as pretty JSON text content. */
function tool(name, description, schema, handler) {
  server.registerTool(name, { description, inputSchema: schema }, async (input) => {
    try {
      const result = await handler(input);
      const text = typeof result === 'string' ? result : JSON.stringify(result, null, 2);
      return { content: [{ type: 'text', text }] };
    } catch (e) {
      const msg = e instanceof AnkiError ? `AnkiConnect error: ${e.message}` : `Error: ${e.message || e}`;
      return { content: [{ type: 'text', text: msg }], isError: true };
    }
  });
}

const noteShape = {
  deck: z.string().describe('Target deck name (use :: for subdecks)'),
  model: z.string().optional().describe('Note type / model name (default: Basic)'),
  fields: z.record(z.string()).optional().describe('Field name -> value map'),
  front: z.string().optional().describe('Shorthand for the Front field (Basic model)'),
  back: z.string().optional().describe('Shorthand for the Back field (Basic model)'),
  tags: z.array(z.string()).optional().describe('Tags to attach'),
  allowDuplicate: z.boolean().optional().describe('Permit duplicate notes'),
};

function toNote(i) {
  return normalizeNote({
    deckName: i.deck,
    modelName: i.model,
    fields: i.fields,
    front: i.front,
    back: i.back,
    tags: i.tags,
    options: { allowDuplicate: !!i.allowDuplicate },
  });
}

// --- connectivity -----------------------------------------------------------

tool('anki_ping', 'Check the connection to Anki/AnkiConnect; returns the API version.', {}, async () => {
  const v = await anki.version();
  return { ok: true, apiVersion: v };
});

// --- decks ------------------------------------------------------------------

tool('anki_list_decks', 'List all deck names.', {}, () => anki.deckNames());

tool('anki_create_deck', 'Create a deck (use :: for subdecks, e.g. "Spanish::Verbs").',
  { name: z.string() }, ({ name }) => anki.createDeck(name));

tool('anki_delete_deck', 'Delete a deck and its cards.',
  { name: z.string() }, async ({ name }) => {
    await anki.deleteDecks([name], true);
    return { deleted: name };
  });

// --- note types -------------------------------------------------------------

tool('anki_list_models', 'List note types (models).', {}, () => anki.modelNames());

tool('anki_model_fields', 'List the field names of a note type.',
  { model: z.string() }, ({ model }) => anki.modelFieldNames(model));

// --- notes ------------------------------------------------------------------

tool('anki_add_note', 'Add a single note/card to a deck.', noteShape, async (i) => {
  const id = await anki.addNote(toNote(i));
  return { added: true, noteId: id };
});

tool('anki_add_notes', 'Add many notes/cards at once. Best for generated decks.',
  { notes: z.array(z.object(noteShape)).describe('Array of notes') },
  async ({ notes }) => {
    const ids = await anki.addNotes(notes.map(toNote));
    const added = ids.filter((x) => x != null).length;
    return { requested: notes.length, added, skipped: notes.length - added, noteIds: ids };
  });

tool('anki_find_notes',
  'Find notes by an Anki search query and return their details. Query syntax is the same as Anki\'s Browse (e.g. "deck:Spanish tag:verb").',
  { query: z.string() },
  async ({ query }) => {
    const ids = await anki.findNotes(query);
    if (ids.length === 0) return { count: 0, notes: [] };
    const info = await anki.notesInfo(ids);
    return { count: info.length, notes: info };
  });

tool('anki_update_note', 'Update the fields and/or tags of an existing note.',
  {
    noteId: z.number(),
    fields: z.record(z.string()).optional(),
    tags: z.array(z.string()).optional(),
  },
  async ({ noteId, fields, tags }) => {
    await anki.updateNote(noteId, { fields, tags });
    return { updated: true, noteId };
  });

tool('anki_delete_notes', 'Delete notes by id.',
  { noteIds: z.array(z.number()) },
  async ({ noteIds }) => {
    await anki.deleteNotes(noteIds);
    return { deleted: noteIds };
  });

tool('anki_add_tags', 'Add tags to all notes matching a search query.',
  { query: z.string(), tags: z.string().describe('Space-separated tags') },
  async ({ query, tags }) => {
    const ids = await anki.findNotes(query);
    if (ids.length) await anki.addTags(ids, tags);
    return { matched: ids.length, tags };
  });

tool('anki_remove_tags', 'Remove tags from all notes matching a search query.',
  { query: z.string(), tags: z.string().describe('Space-separated tags') },
  async ({ query, tags }) => {
    const ids = await anki.findNotes(query);
    if (ids.length) await anki.removeTags(ids, tags);
    return { matched: ids.length, tags };
  });

// --- cards ------------------------------------------------------------------

tool('anki_suspend', 'Suspend all cards matching a search query.',
  { query: z.string() },
  async ({ query }) => {
    const cards = await anki.findCards(query);
    if (cards.length) await anki.suspend(cards);
    return { matched: cards.length };
  });

tool('anki_unsuspend', 'Unsuspend all cards matching a search query.',
  { query: z.string() },
  async ({ query }) => {
    const cards = await anki.findCards(query);
    if (cards.length) await anki.unsuspend(cards);
    return { matched: cards.length };
  });

// --- gui & maintenance ------------------------------------------------------

tool('anki_browse', 'Open Anki\'s card browser focused on a search query.',
  { query: z.string() },
  async ({ query }) => ({ opened: await anki.guiBrowse(query) }));

tool('anki_sync', 'Sync the collection with AnkiWeb.', {}, async () => {
  await anki.sync();
  return { synced: true };
});

tool('anki_stats', 'Get deck statistics and number of cards reviewed today.',
  { deck: z.string().optional().describe('Single deck; omit for all decks') },
  async ({ deck }) => {
    const decks = deck ? [deck] : await anki.deckNames();
    const stats = await anki.getDeckStats(decks);
    const reviewed = await anki.getNumCardsReviewedToday();
    return { reviewedToday: reviewed, decks: stats };
  });

// --- escape hatch -----------------------------------------------------------

tool('anki_invoke',
  'Call ANY AnkiConnect action directly (escape hatch for the full API). See https://foosoft.net/projects/anki-connect/ for actions and params.',
  {
    action: z.string().describe('AnkiConnect action name, e.g. "deckNamesAndIds"'),
    params: z.record(z.any()).optional().describe('Action params object'),
  },
  ({ action, params }) => anki.invoke(action, params || {}));

const transport = new StdioServerTransport();
await server.connect(transport);
// stderr is safe for logs; stdout is the MCP channel.
process.stderr.write('anki MCP server running on stdio\n');
