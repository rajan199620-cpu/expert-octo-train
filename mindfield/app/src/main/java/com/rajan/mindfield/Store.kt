package com.rajan.mindfield

import android.content.Context
import com.rajan.mindfield.core.AppState
import com.rajan.mindfield.core.Codec
import com.rajan.mindfield.core.Concept
import com.rajan.mindfield.core.Curriculum
import com.rajan.mindfield.core.Entry
import com.rajan.mindfield.core.Guess
import com.rajan.mindfield.core.Library
import com.rajan.mindfield.core.Mode
import com.rajan.mindfield.core.Outcome
import com.rajan.mindfield.core.Plan
import com.rajan.mindfield.core.Settings
import com.rajan.mindfield.core.Spacing
import com.rajan.mindfield.core.SystemZoneClock
import com.rajan.mindfield.core.Versions
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.time.Clock
import java.time.LocalDate
import java.time.ZonedDateTime
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The single source of truth: the concept library (read-only, from assets) and your data
 * (journal, guesses, plans, review schedule, settings), saved to one JSON file in app storage.
 *
 * Every change goes through [update], which saves in the background and then tells the widget,
 * the reminders and the Google backup, so no screen has to remember to.
 */
object Store {
    private const val FILE = "mindfield.json"

    /** The phone's clock, in the phone's current time zone. Replaced in tests to move time around. */
    @Volatile var clock: Clock = SystemZoneClock

    private lateinit var app: Context
    private var _library: Library? = null
    private val _state = MutableStateFlow(AppState())
    val state: StateFlow<AppState> = _state.asStateFlow()
    private val writer = Executors.newSingleThreadExecutor()
    @Volatile private var loaded = false

    private val _saveFailed = MutableStateFlow(false)
    /** The last save didn't reach storage (usually a full phone); the app says so instead of failing quietly. */
    val saveFailed: StateFlow<Boolean> = _saveFailed.asStateFlow()

    private val _journalReset = MutableStateFlow(false)
    /** The saved journal couldn't be read at start, so the app started fresh (the file was kept aside). */
    val journalReset: StateFlow<Boolean> = _journalReset.asStateFlow()

    val library: Library get() = _library ?: error("Store.init not called")

    fun init(context: Context): Store {
        if (loaded) return this
        synchronized(this) {
            if (loaded) return this
            app = context.applicationContext
            _library = Library.parse(app.assets.open("concepts.txt").bufferedReader().use { it.readText() })
            _state.value = read(app)
            loaded = true
        }
        return this
    }

    fun today(): LocalDate = LocalDate.now(clock)

    fun now(): ZonedDateTime = ZonedDateTime.now(clock)

    private fun millis() = clock.millis()

    /** Today's concept, choosing it now if this is the first time today anything asked. */
    fun todayConcept(): Concept {
        val day = today()
        state.value.assignments[day]?.conceptId?.let { library[it] }?.let { return it }
        // A clock reading from before the app existed (a phone just switched on, before the network
        // sets the time) would fix a stray day for ever: show a concept without fixing the day.
        if (day < Curriculum.EARLIEST) {
            val s = state.value
            return s.assignments.maxByOrNull { it.key }?.value?.conceptId?.let { library[it] } ?: library[Curriculum.pick(day, library, s)]!!
        }
        update { Curriculum.assign(day, library, it, millis()) }
        return library[state.value.assignments.getValue(day).conceptId]!!
    }

    fun conceptOn(day: LocalDate): Concept? = state.value.assignments[day]?.conceptId?.let { library[it] }

    fun isUnlocked(id: String) = id in state.value.unlocked

    // --- Changes -------------------------------------------------------------------------------

    fun update(change: (AppState) -> AppState) {
        val before: AppState
        val after: AppState
        synchronized(this) {
            before = _state.value
            after = change(before)
            if (after == before) return
            _state.value = after
        }
        save(after)
        Effects.onChanged(app, before, after)
    }

    fun log(conceptId: String, mode: Mode, note: String = "", outcome: Outcome? = null, day: LocalDate = today()): Entry {
        val now = millis()
        val entry = Entry(UUID.randomUUID().toString(), conceptId, day, mode, note.trim(), outcome.takeIf { mode == Mode.USED }, now, now)
        update { it.upsert(entry).engaged(day, conceptId) }
        return entry
    }

    /**
     * Saves an edit made in the field-report sheet from [original] (the entry as the sheet opened
     * it): only the fields you changed are applied, onto the entry as it is now, so a reply added
     * from a notification or a sync meanwhile isn't overwritten. A note deleted meanwhile, here or
     * on another phone, stays deleted.
     */
    fun edit(edited: Entry, original: Entry? = null) = update { s ->
        val current = s.entries.firstOrNull { it.id == edited.id } ?: return@update s
        if (current.deleted) return@update s
        val was = original ?: current
        fun <T> pick(before: T, now: T, mine: T) = if (mine != before) mine else now
        val mode = pick(was.mode, current.mode, edited.mode)
        val changed = current.copy(
            conceptId = pick(was.conceptId, current.conceptId, edited.conceptId),
            mode = mode,
            note = pick(was.note, current.note, edited.note.trim()),
            outcome = pick(was.outcome, current.outcome, edited.outcome).takeIf { mode == Mode.USED },
        )
        if (changed == current) s else s.upsert(changed.copy(updatedAt = Versions.next(current.updatedAt, millis())))
    }

    /**
     * Adds a line written in a notification reply to an entry already logged. If that entry has
     * been deleted since (here or on another phone), the words are kept as a new note instead of
     * vanishing into the deleted one. Returns the note that now holds them.
     */
    fun addNote(entryId: String, text: String): Entry? {
        val line = text.trim()
        if (line.isEmpty()) return null
        var saved: Entry? = null
        update { s ->
            val e = s.entries.firstOrNull { it.id == entryId } ?: return@update s
            val next = if (e.deleted) {
                val now = millis()
                Entry(UUID.randomUUID().toString(), e.conceptId, e.day, e.mode, line, e.outcome.takeIf { e.mode == Mode.USED }, now, now)
            } else {
                e.copy(note = listOf(e.note, line).filter { it.isNotBlank() }.joinToString("\n"), updatedAt = Versions.next(e.updatedAt, millis()))
            }
            saved = next
            s.upsert(next)
        }
        return saved
    }

    fun delete(entryId: String) = update { s ->
        val e = s.entries.firstOrNull { it.id == entryId } ?: return@update s
        if (e.deleted) s else s.upsert(e.copy(deleted = true, note = "", updatedAt = Versions.next(e.updatedAt, millis())))
    }

    /** Only the first guess counts: changing your mind after seeing the answer would be cheating. */
    fun guess(conceptId: String, choice: Int) = update { s ->
        if (conceptId in s.guesses) s else s.copy(guesses = s.guesses + (conceptId to Guess(choice, millis()))).engaged(today(), conceptId)
    }

    fun plan(day: LocalDate, text: String) = update { s ->
        val t = text.trim()
        if (t == s.plans[day]?.text.orEmpty()) s else s.copy(plans = s.plans + (day to Plan(t, Versions.next(s.plans[day]?.updatedAt, millis())))).engaged(day)
    }

    fun grade(conceptId: String, correct: Boolean) = update { s ->
        val unlockedOn = s.unlocked[conceptId] ?: return@update s
        val card = s.cards[conceptId] ?: Spacing.newCard(conceptId, unlockedOn)
        s.copy(cards = s.cards + (conceptId to Spacing.grade(card, correct, today())))
    }

    fun settings(change: (Settings) -> Settings) = update { it.copy(settings = change(it.settings)) }

    /** Swap in a merged copy (after a Google sync or a file restore). */
    fun replace(merged: AppState) = update { merged }

    // --- Storage -------------------------------------------------------------------------------

    private fun file(context: Context) = File(context.filesDir, FILE)

    private fun read(context: Context): AppState {
        val f = file(context)
        if (!f.exists()) return AppState()
        runCatching { return Codec.decode(f.readText()) }
        // Never silently lose a journal: keep the unreadable file aside...
        runCatching { f.copyTo(File(context.filesDir, "$FILE.unreadable-${System.currentTimeMillis()}"), overwrite = true) }
        // ...and use a complete newer write that was never renamed into place, if there is one.
        val tmp = File(context.filesDir, "$FILE.tmp")
        if (tmp.exists()) runCatching { return Codec.decode(tmp.readText()) }
        _journalReset.value = true
        return AppState()
    }

    private fun save(snapshot: AppState) {
        val context = app
        writer.execute {
            // Only the newest snapshot matters; skip stale ones queued behind it.
            if (snapshot !== _state.value) return@execute
            val f = file(context)
            val tmp = File(f.parentFile, "$FILE.tmp")
            try {
                FileOutputStream(tmp).use { out ->
                    out.write(Codec.encode(snapshot, millis()).toByteArray())
                    // On disk before the rename, so a power cut can't leave an empty journal behind.
                    out.fd.sync()
                }
                // Replaces the old file in one step; if that fails, the old file is still there.
                if (!tmp.renameTo(f)) throw IOException("Couldn't replace $f")
                _saveFailed.value = false
            } catch (e: Exception) {
                // Usually a full phone. The change is still in memory and the next save tries again.
                runCatching { tmp.delete() }
                _saveFailed.value = true
            }
        }
    }

    /** The "couldn't read your journal" notice has been seen. */
    fun dismissJournalReset() {
        _journalReset.value = false
    }

    /** Tries the last failed save again (the app coming back to the front, or a tap on the banner). */
    fun retrySave() {
        if (_saveFailed.value) save(_state.value)
    }

    /** Waits for pending saves, so a receiver doesn't let the process die before the file is written. */
    fun flush() {
        runCatching { writer.submit {}.get(5, TimeUnit.SECONDS) }
    }

    /** Simulates the process dying and restarting: re-reads the saved file. */
    fun reloadForTests(context: Context) {
        flush()
        synchronized(this) { _state.value = read(context.applicationContext) }
    }

    /** Fresh state for tests. */
    fun resetForTests(context: Context) {
        synchronized(this) {
            flush()
            app = context.applicationContext
            file(app).delete()
            _library = _library ?: Library.parse(app.assets.open("concepts.txt").bufferedReader().use { it.readText() })
            _state.value = AppState()
            loaded = true
            clock = SystemZoneClock
            _saveFailed.value = false
            _journalReset.value = false
        }
    }
}
