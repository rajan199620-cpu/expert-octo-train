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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
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

    /** Replaced in tests to move time around. */
    @Volatile var clock: Clock = Clock.systemDefaultZone()

    private lateinit var app: Context
    private var _library: Library? = null
    private val _state = MutableStateFlow(AppState())
    val state: StateFlow<AppState> = _state.asStateFlow()
    private val writer = Executors.newSingleThreadExecutor()
    @Volatile private var loaded = false

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
        if (state.value.assignments[day]?.conceptId?.let { library.contains(it) } != true) {
            update { Curriculum.assign(day, library, it, millis()) }
        }
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
        update { it.upsert(entry) }
        return entry
    }

    fun edit(entry: Entry) {
        val fixed = entry.copy(note = entry.note.trim(), outcome = entry.outcome.takeIf { entry.mode == Mode.USED }, updatedAt = millis())
        update { it.upsert(fixed) }
    }

    /** Adds a line written in a notification reply to an entry already logged. */
    fun addNote(entryId: String, text: String) {
        val line = text.trim()
        if (line.isEmpty()) return
        update { s ->
            val e = s.entries.firstOrNull { it.id == entryId } ?: return@update s
            s.upsert(e.copy(note = listOf(e.note, line).filter { it.isNotBlank() }.joinToString("\n"), updatedAt = millis()))
        }
    }

    fun delete(entryId: String) = update { s ->
        val e = s.entries.firstOrNull { it.id == entryId } ?: return@update s
        s.upsert(e.copy(deleted = true, note = "", updatedAt = millis()))
    }

    /** Only the first guess counts: changing your mind after seeing the answer would be cheating. */
    fun guess(conceptId: String, choice: Int) = update { s ->
        if (conceptId in s.guesses) s else s.copy(guesses = s.guesses + (conceptId to Guess(choice, millis())))
    }

    fun plan(day: LocalDate, text: String) = update { s ->
        val t = text.trim()
        if (t == s.plans[day]?.text.orEmpty()) s else s.copy(plans = s.plans + (day to Plan(t, millis())))
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
        return runCatching { Codec.decode(f.readText()) }.getOrElse {
            // Never silently lose a journal: keep the unreadable file aside and start fresh.
            f.copyTo(File(context.filesDir, "$FILE.unreadable-${System.currentTimeMillis()}"), overwrite = true)
            AppState()
        }
    }

    private fun save(snapshot: AppState) {
        val context = app
        writer.execute {
            // Only the newest snapshot matters; skip stale ones queued behind it.
            if (snapshot !== _state.value) return@execute
            val f = file(context)
            val tmp = File(f.parentFile, "$FILE.tmp")
            tmp.writeText(Codec.encode(snapshot, millis()))
            if (!tmp.renameTo(f)) {
                f.delete()
                tmp.renameTo(f)
            }
        }
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
            clock = Clock.systemDefaultZone()
        }
    }
}
