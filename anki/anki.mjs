#!/usr/bin/env node
// anki.mjs — a zero-dependency CLI for driving Anki via the AnkiConnect add-on.
//
// Anki must be running on this machine with AnkiConnect installed & enabled.
// Every subcommand maps onto an AnkiConnect action; `invoke` is a passthrough
// for anything not given a dedicated command.
//
// Usage:
//   node anki/anki.mjs <command> [args...]
//   node anki/anki.mjs help
//
// Examples:
//   node anki/anki.mjs ping
//   node anki/anki.mjs decks
//   node anki/anki.mjs create-deck "Spanish::Verbs"
//   node anki/anki.mjs add --deck Default --front "hola" --back "hello" --tags spanish,greetings
//   node anki/anki.mjs add --deck Default --model Basic --field Front=hola --field Back=hello
//   node anki/anki.mjs add-batch anki/examples/notes.example.json
//   node anki/anki.mjs find "deck:Default tag:spanish"
//   node anki/anki.mjs browse "deck:Default"
//   node anki/anki.mjs suspend "deck:Default tag:hard"
//   node anki/anki.mjs sync
//   node anki/anki.mjs invoke deckNamesAndIds

import { readFile } from 'node:fs/promises';
import { AnkiClient, AnkiError } from './client.mjs';

const client = new AnkiClient();

function print(x) {
  process.stdout.write((typeof x === 'string' ? x : JSON.stringify(x, null, 2)) + '\n');
}

/** Parse argv into { _: positionals, flags, fields } supporting repeated --field K=V. */
function parseArgs(argv) {
  const out = { _: [], flags: {}, fields: {} };
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    if (a.startsWith('--')) {
      const key = a.slice(2);
      const next = argv[i + 1];
      const takeVal = () => {
        if (next === undefined || next.startsWith('--')) return true; // boolean flag
        i++;
        return next;
      };
      if (key === 'field') {
        const v = takeVal();
        const eq = String(v).indexOf('=');
        if (eq === -1) throw new Error('--field expects Name=Value');
        out.fields[v.slice(0, eq)] = v.slice(eq + 1);
      } else {
        out.flags[key] = takeVal();
      }
    } else {
      out._.push(a);
    }
  }
  return out;
}

function collectFields(args) {
  const fields = { ...args.fields };
  if (args.flags.front != null) fields.Front = args.flags.front;
  if (args.flags.back != null) fields.Back = args.flags.back;
  return fields;
}

const HELP = `anki — control Anki via the AnkiConnect add-on

Connectivity:
  ping                         Show AnkiConnect version (connection test)

Decks:
  decks                        List deck names
  create-deck <name>           Create a deck (use :: for subdecks)
  delete-deck <name>           Delete a deck and its cards

Note types:
  models                       List note types (models)
  fields <model>               List field names of a model

Notes / cards:
  add [flags]                  Add one note
      --deck <name>            (required) target deck
      --model <name>           note type (default: Basic)
      --front <text> --back <text>   shorthand for Basic fields
      --field Name=Value       set an arbitrary field (repeatable)
      --tags a,b,c             comma/space separated tags
      --allow-duplicate        permit duplicates
  add-batch <file.json>        Add many notes from a JSON array
  find <query>                 Find notes by Anki search; prints note info
  find-cards <query>           Find card ids by Anki search
  update <noteId> [--field K=V ...] [--tags a,b]   Update a note
  delete <noteId> [noteId...]  Delete notes
  tag-add <query> <tags>       Add tags to notes matching query
  tag-remove <query> <tags>    Remove tags from notes matching query
  suspend <query>              Suspend cards matching query
  unsuspend <query>            Unsuspend cards matching query
  browse <query>               Open Anki's card browser on a query

Maintenance:
  sync                         Sync with AnkiWeb
  stats [deck]                 Deck stats (all decks if omitted) + reviews today

Escape hatch:
  invoke <action> [jsonParams] Call ANY AnkiConnect action directly
      e.g. invoke createDeck '{"deck":"X"}'

Env: ANKI_CONNECT_URL (default http://127.0.0.1:8765), ANKI_CONNECT_KEY`;

async function notesFromQuery(query) {
  const ids = await client.findNotes(query);
  return ids;
}

async function main() {
  const argv = process.argv.slice(2);
  const cmd = argv[0];
  const args = parseArgs(argv.slice(1));

  switch (cmd) {
    case undefined:
    case 'help':
    case '-h':
    case '--help':
      print(HELP);
      return;

    case 'ping': {
      const v = await client.version();
      print(`AnkiConnect OK — API version ${v}`);
      return;
    }

    case 'decks':
      print(await client.deckNames());
      return;

    case 'create-deck': {
      const name = args._[0];
      if (!name) throw new Error('create-deck needs a deck name');
      print(await client.createDeck(name));
      return;
    }

    case 'delete-deck': {
      const name = args._[0];
      if (!name) throw new Error('delete-deck needs a deck name');
      await client.deleteDecks([name], true);
      print(`deleted deck: ${name}`);
      return;
    }

    case 'models':
      print(await client.modelNames());
      return;

    case 'fields': {
      const model = args._[0];
      if (!model) throw new Error('fields needs a model name');
      print(await client.modelFieldNames(model));
      return;
    }

    case 'add': {
      const deck = args.flags.deck;
      if (!deck) throw new Error('add needs --deck');
      const fields = collectFields(args);
      if (Object.keys(fields).length === 0) throw new Error('add needs fields (--front/--back or --field K=V)');
      const tags = args.flags.tags ? String(args.flags.tags).split(/[\s,]+/).filter(Boolean) : undefined;
      const id = await client.addNote({
        deckName: deck,
        modelName: args.flags.model || 'Basic',
        fields,
        tags,
        options: { allowDuplicate: !!args.flags['allow-duplicate'] },
      });
      print({ added: true, noteId: id });
      return;
    }

    case 'add-batch': {
      const file = args._[0];
      if (!file) throw new Error('add-batch needs a JSON file path');
      const raw = await readFile(file, 'utf8');
      const parsed = JSON.parse(raw);
      const notes = Array.isArray(parsed) ? parsed : parsed.notes;
      if (!Array.isArray(notes)) throw new Error('JSON must be an array of notes, or { "notes": [...] }');
      const ids = await client.addNotes(notes);
      const added = ids.filter((x) => x != null).length;
      print({ requested: notes.length, added, skipped: notes.length - added, noteIds: ids });
      return;
    }

    case 'find': {
      const query = args._.join(' ');
      if (!query) throw new Error('find needs a query');
      const ids = await client.findNotes(query);
      if (ids.length === 0) {
        print({ count: 0, notes: [] });
        return;
      }
      const info = await client.notesInfo(ids);
      print({ count: info.length, notes: info });
      return;
    }

    case 'find-cards': {
      const query = args._.join(' ');
      if (!query) throw new Error('find-cards needs a query');
      print(await client.findCards(query));
      return;
    }

    case 'update': {
      const noteId = Number(args._[0]);
      if (!Number.isFinite(noteId)) throw new Error('update needs a numeric noteId');
      const fields = collectFields(args);
      const tags = args.flags.tags ? String(args.flags.tags).split(/[\s,]+/).filter(Boolean) : undefined;
      await client.updateNote(noteId, {
        fields: Object.keys(fields).length ? fields : undefined,
        tags,
      });
      print({ updated: true, noteId });
      return;
    }

    case 'delete': {
      const ids = args._.map(Number).filter(Number.isFinite);
      if (ids.length === 0) throw new Error('delete needs one or more numeric noteIds');
      await client.deleteNotes(ids);
      print({ deleted: ids });
      return;
    }

    case 'tag-add':
    case 'tag-remove': {
      const query = args._[0];
      const tags = args._[1];
      if (!query || !tags) throw new Error(`${cmd} needs <query> <tags>`);
      const ids = await notesFromQuery(query);
      if (ids.length === 0) {
        print({ matched: 0 });
        return;
      }
      if (cmd === 'tag-add') await client.addTags(ids, tags);
      else await client.removeTags(ids, tags);
      print({ matched: ids.length, tags });
      return;
    }

    case 'suspend':
    case 'unsuspend': {
      const query = args._.join(' ');
      if (!query) throw new Error(`${cmd} needs a query`);
      const cards = await client.findCards(query);
      if (cards.length === 0) {
        print({ matched: 0 });
        return;
      }
      if (cmd === 'suspend') await client.suspend(cards);
      else await client.unsuspend(cards);
      print({ matched: cards.length });
      return;
    }

    case 'browse': {
      const query = args._.join(' ');
      if (!query) throw new Error('browse needs a query');
      print({ opened: await client.guiBrowse(query) });
      return;
    }

    case 'sync':
      await client.sync();
      print('sync triggered');
      return;

    case 'stats': {
      const deck = args._[0];
      const decks = deck ? [deck] : await client.deckNames();
      const stats = await client.getDeckStats(decks);
      const reviewed = await client.getNumCardsReviewedToday();
      print({ reviewedToday: reviewed, decks: stats });
      return;
    }

    case 'invoke': {
      const action = args._[0];
      if (!action) throw new Error('invoke needs an action name');
      const params = args._[1] ? JSON.parse(args._[1]) : {};
      print(await client.invoke(action, params));
      return;
    }

    default:
      print(`Unknown command: ${cmd}\n\n${HELP}`);
      process.exitCode = 2;
  }
}

main().catch((e) => {
  if (e instanceof AnkiError) {
    process.stderr.write(`AnkiConnect error: ${e.message}\n`);
  } else {
    process.stderr.write(`Error: ${e.message || e}\n`);
  }
  process.exitCode = 1;
});
