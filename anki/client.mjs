// AnkiConnect client — a thin, zero-dependency wrapper over the AnkiConnect
// add-on's HTTP API (https://foosoft.net/projects/anki-connect/).
//
// AnkiConnect listens on http://127.0.0.1:8765 on the machine where Anki is
// running. This module talks JSON over that endpoint. It exposes a generic
// `invoke(action, params)` escape hatch (so the *entire* API is reachable) plus
// named convenience methods for the operations used day to day.
//
// Requires Node 18+ (global fetch). No npm install needed for this file.

const ANKI_CONNECT_VERSION = 6;

export class AnkiError extends Error {
  constructor(message, { action, params } = {}) {
    super(message);
    this.name = 'AnkiError';
    this.action = action;
    this.params = params;
  }
}

export class AnkiClient {
  /**
   * @param {object} [opts]
   * @param {string} [opts.url]   AnkiConnect endpoint. Default env ANKI_CONNECT_URL or http://127.0.0.1:8765
   * @param {string} [opts.key]   AnkiConnect API key, if one is configured. Default env ANKI_CONNECT_KEY
   * @param {number} [opts.timeoutMs] Per-request timeout. Default 15000.
   */
  constructor(opts = {}) {
    this.url = opts.url || process.env.ANKI_CONNECT_URL || 'http://127.0.0.1:8765';
    this.key = opts.key || process.env.ANKI_CONNECT_KEY || undefined;
    this.timeoutMs = opts.timeoutMs ?? 15000;
  }

  /**
   * Call any AnkiConnect action. This is the universal entry point — every
   * other method is sugar over this one.
   * @param {string} action  e.g. "deckNames", "addNote", "findNotes"
   * @param {object} [params] action-specific params object
   * @returns {Promise<any>} the action's `result`
   */
  async invoke(action, params = {}) {
    const body = { action, version: ANKI_CONNECT_VERSION, params };
    if (this.key) body.key = this.key;

    const ctrl = new AbortController();
    const timer = setTimeout(() => ctrl.abort(), this.timeoutMs);
    let resp;
    try {
      resp = await fetch(this.url, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body),
        signal: ctrl.signal,
      });
    } catch (e) {
      const hint =
        e.name === 'AbortError'
          ? `timed out after ${this.timeoutMs}ms`
          : `could not reach AnkiConnect at ${this.url}`;
      throw new AnkiError(
        `${hint}. Is Anki running with the AnkiConnect add-on installed and enabled? (${String(e.message || e)})`,
        { action, params },
      );
    } finally {
      clearTimeout(timer);
    }

    if (!resp.ok) {
      throw new AnkiError(`AnkiConnect HTTP ${resp.status} ${resp.statusText}`, { action, params });
    }

    const json = await resp.json();
    // AnkiConnect always returns { result, error }. error is null on success.
    if (json == null || typeof json !== 'object' || !('error' in json) || !('result' in json)) {
      throw new AnkiError('unexpected AnkiConnect response shape', { action, params });
    }
    if (json.error != null) {
      throw new AnkiError(json.error, { action, params });
    }
    return json.result;
  }

  // --- connectivity ---------------------------------------------------------

  /** AnkiConnect API version (number). Doubles as a connectivity check. */
  version() {
    return this.invoke('version');
  }

  /** Prompt Anki to grant API access (useful first call when a key is set). */
  requestPermission() {
    return this.invoke('requestPermission');
  }

  // --- decks ----------------------------------------------------------------

  deckNames() {
    return this.invoke('deckNames');
  }

  deckNamesAndIds() {
    return this.invoke('deckNamesAndIds');
  }

  createDeck(deck) {
    return this.invoke('createDeck', { deck });
  }

  /** @param {string[]} decks @param {boolean} cardsToo also delete the cards */
  deleteDecks(decks, cardsToo = true) {
    return this.invoke('deleteDecks', { decks, cardsToo });
  }

  // --- note types (models) --------------------------------------------------

  modelNames() {
    return this.invoke('modelNames');
  }

  modelFieldNames(modelName) {
    return this.invoke('modelFieldNames', { modelName });
  }

  // --- notes ----------------------------------------------------------------

  /**
   * @param {object} note { deckName, modelName, fields, tags?, options? }
   * @returns {Promise<number>} the new note id
   */
  addNote(note) {
    return this.invoke('addNote', { note: normalizeNote(note) });
  }

  /**
   * @param {object[]} notes array of note objects (see addNote)
   * @returns {Promise<Array<number|null>>} ids; null where a note could not be added
   */
  addNotes(notes) {
    return this.invoke('addNotes', { notes: notes.map(normalizeNote) });
  }

  /** Check which of the given notes can be added (no duplicates / valid). */
  canAddNotes(notes) {
    return this.invoke('canAddNotes', { notes: notes.map(normalizeNote) });
  }

  /** @param {number} id @param {object} fields field name -> value */
  updateNoteFields(id, fields) {
    return this.invoke('updateNoteFields', { note: { id, fields } });
  }

  /** Update fields and/or tags of an existing note. */
  updateNote(id, { fields, tags } = {}) {
    const note = { id };
    if (fields) note.fields = fields;
    if (tags) note.tags = tags;
    return this.invoke('updateNote', { note });
  }

  /** @param {string} query Anki search query @returns {Promise<number[]>} note ids */
  findNotes(query) {
    return this.invoke('findNotes', { query });
  }

  /** @param {number[]} notes note ids @returns detailed note info */
  notesInfo(notes) {
    return this.invoke('notesInfo', { notes });
  }

  deleteNotes(notes) {
    return this.invoke('deleteNotes', { notes });
  }

  addTags(notes, tags) {
    return this.invoke('addTags', { notes, tags });
  }

  removeTags(notes, tags) {
    return this.invoke('removeTags', { notes, tags });
  }

  // --- cards ----------------------------------------------------------------

  findCards(query) {
    return this.invoke('findCards', { query });
  }

  cardsInfo(cards) {
    return this.invoke('cardsInfo', { cards });
  }

  suspend(cards) {
    return this.invoke('suspend', { cards });
  }

  unsuspend(cards) {
    return this.invoke('unsuspend', { cards });
  }

  // --- gui ------------------------------------------------------------------

  /** Open the card browser focused on a search query. */
  guiBrowse(query) {
    return this.invoke('guiBrowse', { query });
  }

  // --- maintenance ----------------------------------------------------------

  /** Trigger a sync with AnkiWeb. */
  sync() {
    return this.invoke('sync');
  }

  getDeckStats(decks) {
    return this.invoke('getDeckStats', { decks });
  }

  getNumCardsReviewedToday() {
    return this.invoke('getNumCardsReviewedToday');
  }
}

/**
 * Normalize a loosely-specified note into AnkiConnect's expected shape.
 * Accepts { deck|deckName, model|modelName, fields|front/back, tags, options }.
 */
export function normalizeNote(note) {
  if (!note || typeof note !== 'object') {
    throw new AnkiError('note must be an object');
  }
  const deckName = note.deckName || note.deck;
  const modelName = note.modelName || note.model || 'Basic';

  let fields = note.fields;
  if (!fields && (note.front != null || note.back != null)) {
    fields = { Front: note.front ?? '', Back: note.back ?? '' };
  }
  if (!deckName) throw new AnkiError('note is missing a deck (deckName/deck)');
  if (!fields || typeof fields !== 'object') {
    throw new AnkiError('note is missing fields (fields, or front/back)');
  }

  const out = { deckName, modelName, fields };
  if (note.tags) out.tags = Array.isArray(note.tags) ? note.tags : String(note.tags).split(/[\s,]+/).filter(Boolean);
  out.options = { allowDuplicate: false, ...(note.options || {}) };
  return out;
}
