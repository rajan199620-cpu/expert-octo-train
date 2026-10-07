package com.rajan.mindfield.core

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

/**
 * Combining two copies of your data (this phone and the Google Drive backup, or a backup file).
 *
 * Every data merge is order-free: merging A into B gives the same as B into A, and merging
 * twice changes nothing, so phones that sync at different times always end up agreeing.
 * Settings are the exception: they stay this phone's, unless this phone has no data yet.
 * Whether this phone has finished setting up stays this phone's either way, so restoring in the
 * middle of the welcome steps doesn't skip the rest of them.
 */
object Sync {
    fun merge(local: AppState, other: AppState): AppState {
        val entries = (local.entries + other.entries).groupBy { it.id }.values.map { copies ->
            copies.maxWith(compareBy<Entry>({ it.updatedAt }, { it.deleted }, { it.toString() }))
        }.sortedWith(compareBy({ it.day }, { it.createdAt }, { it.id }))

        // Two phones that picked different concepts for one day keep the one you worked on, then the earlier.
        val assignments = pickEach(local.assignments, other.assignments) { a, b ->
            minOf(a, b, compareBy<Assignment>({ !it.engaged }, { it.assignedAt }, { it.conceptId }))
        }
        val cards = pickEach(local.cards, other.cards) { a, b ->
            maxOf(a, b, compareBy<Card>({ it.lastReviewed ?: LocalDate.MIN }, { it.right + it.wrong }, { it.box }, { it.due }, { it.right }))
        }
        val guesses = pickEach(local.guesses, other.guesses) { a, b ->
            minOf(a, b, compareBy<Guess>({ it.at }, { it.choice }))
        }
        val plans = pickEach(local.plans, other.plans) { a, b ->
            maxOf(a, b, compareBy<Plan>({ it.updatedAt }, { it.text }))
        }
        val settings = if (local.isEmpty && !other.isEmpty) {
            other.settings.copy(onboarded = local.settings.onboarded)
        } else {
            local.settings
        }
        return AppState(assignments, entries, cards, guesses, plans, settings)
    }

    /**
     * Brings a backup into this phone (a Google sync or a backup file). The same as [merge], except
     * when this phone is fresh (see [AppState.isFresh]) and the backup isn't: the days this phone
     * was only shown a concept are placeholders, so they give way to the backup's (a new phone's
     * first concept must not become today's concept on every phone) and the backup's settings
     * come with it.
     */
    fun adopt(local: AppState, other: AppState): AppState {
        if (!local.isFresh || other.isFresh) return merge(local, other)
        return merge(local.copy(assignments = emptyMap()), other)
    }

    private fun <K, V> pickEach(a: Map<K, V>, b: Map<K, V>, pick: (V, V) -> V): Map<K, V> {
        val out = HashMap(a)
        for ((k, v) in b) out[k] = out[k]?.let { pick(it, v) } ?: v
        return out
    }

    /** How many field notes [merged] has that [before] didn't (to say what a restore brought back). */
    fun restoredEntries(before: AppState, merged: AppState): Int {
        val had = before.entries.mapTo(HashSet()) { it.id }
        return merged.liveEntries.count { it.id !in had }
    }
}

/**
 * Versions for last-writer-wins: a change must beat the copy it replaces even when the phone's
 * clock is behind (or was set back), or the next sync would quietly undo it.
 */
object Versions {
    fun next(previous: Long?, now: Long): Long = if (previous == null) now else maxOf(now, previous + 1)
}

/** The backup file format: plain JSON, readable by anyone, with your notes in your own words. */
object Codec {
    const val FORMAT = "mindfield-backup"
    const val VERSION = 1

    /** [pretty] lays the file out one item per line, for a backup file people may open. */
    fun encode(state: AppState, now: Long, pretty: Boolean = false): String {
        val s = state.settings
        val root = JSONObject()
            .put("format", FORMAT)
            .put("version", VERSION)
            .put("exportedAt", now)
            .put(
                "settings",
                JSONObject()
                    .put("morningOn", s.morningOn)
                    .put("morningMinute", s.morningMinute)
                    .put("eveningOn", s.eveningOn)
                    .put("eveningMinute", s.eveningMinute)
                    .put("spotCheckOn", s.spotCheckOn)
                    .put("focus", JSONArray(s.focus.map { it.key }.sorted()))
                    .put("theme", s.theme.key)
                    .put("showAll", s.showAll)
                    .put("onboarded", s.onboarded)
                    .put("weeklyGoal", s.weeklyGoal),
            )
        root.put(
            "assignments",
            JSONArray(
                state.assignments.entries.sortedBy { it.key }.map { (day, a) ->
                    JSONObject().put("day", day.toString()).put("concept", a.conceptId).put("at", a.assignedAt)
                        .apply { if (a.engaged) put("engaged", true) }
                },
            ),
        )
        root.put(
            "entries",
            JSONArray(
                state.entries.map { e ->
                    JSONObject()
                        .put("id", e.id)
                        .put("concept", e.conceptId)
                        .put("day", e.day.toString())
                        .put("mode", e.mode.key)
                        .put("note", e.note)
                        .put("outcome", e.outcome?.key ?: JSONObject.NULL)
                        .put("created", e.createdAt)
                        .put("updated", e.updatedAt)
                        .put("deleted", e.deleted)
                },
            ),
        )
        root.put(
            "cards",
            JSONArray(
                state.cards.values.sortedBy { it.conceptId }.map { c ->
                    JSONObject()
                        .put("concept", c.conceptId)
                        .put("box", c.box)
                        .put("due", c.due.toString())
                        .put("last", c.lastReviewed?.toString() ?: JSONObject.NULL)
                        .put("right", c.right)
                        .put("wrong", c.wrong)
                },
            ),
        )
        root.put(
            "guesses",
            JSONArray(
                state.guesses.entries.sortedBy { it.key }.map { (id, g) ->
                    JSONObject().put("concept", id).put("choice", g.choice).put("at", g.at)
                },
            ),
        )
        root.put(
            "plans",
            JSONArray(
                state.plans.entries.sortedBy { it.key }.map { (day, p) ->
                    JSONObject().put("day", day.toString()).put("text", p.text).put("updated", p.updatedAt)
                },
            ),
        )
        return if (pretty) root.toString(1) else root.toString()
    }

    /** Reads a backup. Unknown or damaged items are skipped rather than failing the whole restore. */
    fun decode(text: String): AppState {
        val root = JSONObject(text)
        require(root.optString("format") == FORMAT) { "This isn't a Mindfield backup file." }
        val s = root.optJSONObject("settings") ?: JSONObject()
        val defaults = Settings()
        val settings = Settings(
            morningOn = s.optBoolean("morningOn", defaults.morningOn),
            morningMinute = s.optInt("morningMinute", defaults.morningMinute).coerceIn(0, 24 * 60 - 1),
            eveningOn = s.optBoolean("eveningOn", defaults.eveningOn),
            eveningMinute = s.optInt("eveningMinute", defaults.eveningMinute).coerceIn(0, 24 * 60 - 1),
            spotCheckOn = s.optBoolean("spotCheckOn", defaults.spotCheckOn),
            focus = s.optJSONArray("focus").strings().mapNotNull { Category.of(it) }.toSet(),
            theme = ThemeMode.of(s.optString("theme")),
            showAll = s.optBoolean("showAll", defaults.showAll),
            onboarded = s.optBoolean("onboarded", defaults.onboarded),
            weeklyGoal = s.optInt("weeklyGoal", defaults.weeklyGoal).coerceIn(0, 7),
        )
        val assignments = HashMap<LocalDate, Assignment>()
        root.optJSONArray("assignments").objects().forEach { o ->
            val day = o.day("day") ?: return@forEach
            val id = o.optString("concept").ifBlank { return@forEach }
            assignments[day] = Assignment(id, o.optLong("at"), o.optBoolean("engaged"))
        }
        val entries = root.optJSONArray("entries").objects().mapNotNull { o ->
            val id = o.optString("id").ifBlank { return@mapNotNull null }
            Entry(
                id = id,
                conceptId = o.optString("concept").ifBlank { return@mapNotNull null },
                day = o.day("day") ?: return@mapNotNull null,
                mode = Mode.of(o.optString("mode")) ?: return@mapNotNull null,
                note = o.optString("note"),
                outcome = if (o.isNull("outcome")) null else Outcome.of(o.optString("outcome")),
                createdAt = o.optLong("created"),
                updatedAt = o.optLong("updated"),
                deleted = o.optBoolean("deleted"),
            )
        }.groupBy { it.id }.values.map { it.maxBy { e -> e.updatedAt } }
        val cards = root.optJSONArray("cards").objects().mapNotNull { o ->
            val id = o.optString("concept").ifBlank { return@mapNotNull null }
            Card(
                conceptId = id,
                box = o.optInt("box").coerceIn(0, Spacing.MAX_BOX),
                due = o.day("due") ?: return@mapNotNull null,
                lastReviewed = o.day("last"),
                right = o.optInt("right").coerceAtLeast(0),
                wrong = o.optInt("wrong").coerceAtLeast(0),
            )
        }.associateBy { it.conceptId }
        val guesses = root.optJSONArray("guesses").objects().mapNotNull { o ->
            val id = o.optString("concept").ifBlank { return@mapNotNull null }
            id to Guess(o.optInt("choice"), o.optLong("at"))
        }.toMap()
        val plans = root.optJSONArray("plans").objects().mapNotNull { o ->
            val day = o.day("day") ?: return@mapNotNull null
            day to Plan(o.optString("text"), o.optLong("updated"))
        }.toMap()
        return AppState(assignments, entries, cards, guesses, plans, settings)
    }

    private fun JSONArray?.objects(): List<JSONObject> =
        if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

    private fun JSONArray?.strings(): List<String> =
        if (this == null) emptyList() else (0 until length()).mapNotNull { optString(it).ifBlank { null } }

    private fun JSONObject.day(key: String): LocalDate? =
        if (isNull(key)) null else runCatching { LocalDate.parse(optString(key)) }.getOrNull()
}
