package com.rajan.meditationtimer

import android.content.Context
import androidx.core.content.edit

/** Remembers the last-used settings so the next sit is one tap away. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    val timerConfig: SessionConfig
        get() = SessionConfig(
            durationSec = sp.getInt(KEY_DURATION, 20) * 60,
            openingBellSec = sp.getInt(KEY_OPENING, 5),
            closingBellSec = sp.getInt(KEY_CLOSING, 10),
            bellAtEnd = sp.getBoolean(KEY_END, false),
            intervalMin = sp.getInt(KEY_INTERVAL, 0),
        )
    val volume: Float get() = sp.getFloat(KEY_VOLUME, 0.6f)
    val alertMode: AlertMode get() = enumOrDefault(sp.getString(KEY_ALERT, null), AlertMode.BELL)
    val autoDnd: Boolean get() = sp.getBoolean(KEY_DND, false)

    fun saveTimer(config: SessionConfig, volume: Float, alertMode: AlertMode, autoDnd: Boolean) {
        sp.edit {
            putInt(KEY_DURATION, config.durationSec / 60)
            putInt(KEY_OPENING, config.openingBellSec)
            putInt(KEY_CLOSING, config.closingBellSec)
            putBoolean(KEY_END, config.bellAtEnd)
            putInt(KEY_INTERVAL, config.intervalMin)
            putFloat(KEY_VOLUME, volume)
            putString(KEY_ALERT, alertMode.name)
            putBoolean(KEY_DND, autoDnd)
        }
    }

    /** Background sound during a sit, its own volume, and the user's recording if they chose one. */
    var ambience: Ambience
        get() = enumOrDefault(sp.getString(KEY_AMBIENCE, null), Ambience.OFF)
        set(value) = sp.edit { putString(KEY_AMBIENCE, value.name) }
    var ambienceVolume: Float
        get() = sp.getFloat(KEY_AMBIENCE_VOLUME, 0.5f)
        set(value) = sp.edit { putFloat(KEY_AMBIENCE_VOLUME, value.coerceIn(0f, 1f)) }
    /** Content URI of "My recording", with a persisted read grant; stays on this phone only. */
    var ambienceUri: String?
        get() = sp.getString(KEY_AMBIENCE_URI, null)
        set(value) = sp.edit { putString(KEY_AMBIENCE_URI, value) }
    var ambienceName: String?
        get() = sp.getString(KEY_AMBIENCE_NAME, null)
        set(value) = sp.edit { putString(KEY_AMBIENCE_NAME, value) }

    /** Everything that shapes your usual sit, for the backup file. */
    fun exportSettings(): Map<String, String> = buildMap {
        for (key in listOf(KEY_DURATION, KEY_OPENING, KEY_CLOSING, KEY_INTERVAL, KEY_BREATH_MINUTES, KEY_MALA_TARGET, KEY_WEEKLY_GOAL)) {
            if (sp.contains(key)) put(key, sp.getInt(key, 0).toString())
        }
        for (key in listOf(KEY_END, KEY_DND, KEY_COUNT, KEY_CHECK_INS, KEY_REMINDER_ON)) {
            if (sp.contains(key)) put(key, sp.getBoolean(key, false).toString())
        }
        if (sp.contains(KEY_REMINDER_TIME)) put(KEY_REMINDER_TIME, sp.getInt(KEY_REMINDER_TIME, 0).toString())
        // The settings line is "key=value;..." so free text is URL-encoded.
        sp.getString(KEY_REMINDER_CUE, null)?.let { put(KEY_REMINDER_CUE, java.net.URLEncoder.encode(it, "UTF-8")) }
        if (sp.contains(KEY_VOLUME)) put(KEY_VOLUME, sp.getFloat(KEY_VOLUME, 0.6f).toString())
        if (sp.contains(KEY_AMBIENCE_VOLUME)) put(KEY_AMBIENCE_VOLUME, sp.getFloat(KEY_AMBIENCE_VOLUME, 0.5f).toString())
        // A recording lives on one phone, so a backup carries the built-in sounds only.
        ambience.takeIf { it != Ambience.CUSTOM && sp.contains(KEY_AMBIENCE) }?.let { put(KEY_AMBIENCE, it.name) }
        for (key in listOf(KEY_ALERT, KEY_BREATH_PATTERN)) sp.getString(key, null)?.let { put(key, it) }
    }

    /** Restores settings from a backup; unknown or malformed values are ignored. */
    fun importSettings(settings: Map<String, String>) {
        sp.edit {
            for ((key, value) in settings) {
                when (key) {
                    KEY_DURATION, KEY_OPENING, KEY_CLOSING, KEY_INTERVAL, KEY_BREATH_MINUTES, KEY_MALA_TARGET ->
                        value.toIntOrNull()?.let { putInt(key, it) }
                    KEY_WEEKLY_GOAL -> value.toIntOrNull()?.takeIf { it in 0..7 }?.let { putInt(key, it) }
                    KEY_END, KEY_DND, KEY_COUNT, KEY_CHECK_INS, KEY_REMINDER_ON ->
                        value.toBooleanStrictOrNull()?.let { putBoolean(key, it) }
                    KEY_REMINDER_TIME -> value.toIntOrNull()?.takeIf { it in 0 until 24 * 60 }?.let { putInt(key, it) }
                    KEY_REMINDER_CUE -> runCatching { java.net.URLDecoder.decode(value, "UTF-8") }.getOrNull()?.let { putString(key, it.limitText(60)) }
                    KEY_VOLUME, KEY_AMBIENCE_VOLUME -> value.toFloatOrNull()?.takeIf { !it.isNaN() }?.let { putFloat(key, it.coerceIn(0f, 1f)) }
                    KEY_AMBIENCE -> Ambience.entries.firstOrNull { it.name == value && it != Ambience.CUSTOM }?.let { putString(key, it.name) }
                    KEY_ALERT, KEY_BREATH_PATTERN -> putString(key, value)
                }
            }
        }
        version.value++
    }

    var breathPattern: String
        get() = sp.getString(KEY_BREATH_PATTERN, BreathPattern.ALL.first().name)!!
        set(value) = sp.edit { putString(KEY_BREATH_PATTERN, value) }

    var breathMinutes: Int
        get() = sp.getInt(KEY_BREATH_MINUTES, 3)
        set(value) = sp.edit { putInt(KEY_BREATH_MINUTES, value) }

    /** Breath-count attention checks, oldest first. */
    val breathChecks: List<BreathCheck>
        get() = sp.getString(KEY_BREATH_CHECKS, "")!!.lines().mapNotNull(BreathCheck::decode)

    fun addBreathCheck(check: BreathCheck) {
        sp.edit { putString(KEY_BREATH_CHECKS, (breathChecks + check).joinToString("\n") { it.encode() }) }
    }

    /** Tap or press a volume key during a sit each time you notice the mind has wandered. */
    var countDistractions: Boolean
        get() = sp.getBoolean(KEY_COUNT, true)
        set(value) = sp.edit { putBoolean(KEY_COUNT, value) }

    /** Days a week you mean to sit (Monday–Sunday); 0 = no goal. 5 leaves room for a busy week. */
    var weeklyGoal: Int
        get() = sp.getInt(KEY_WEEKLY_GOAL, 5).coerceIn(0, 7)
        set(value) = sp.edit { putInt(KEY_WEEKLY_GOAL, value.coerceIn(0, 7)) }

    /** One-tap "how do you feel?" just before and just after each sit. */
    var checkIns: Boolean
        get() = sp.getBoolean(KEY_CHECK_INS, true)
        set(value) = sp.edit { putBoolean(KEY_CHECK_INS, value) }

    /** Daily reminder, anchored to a habit you already have ("After morning tea"). */
    var reminder: Reminder
        get() = Reminder(
            enabled = sp.getBoolean(KEY_REMINDER_ON, false),
            minuteOfDay = sp.getInt(KEY_REMINDER_TIME, 7 * 60),
            cue = sp.getString(KEY_REMINDER_CUE, "") ?: "",
        )
        set(value) = sp.edit {
            putBoolean(KEY_REMINDER_ON, value.enabled)
            putInt(KEY_REMINDER_TIME, value.minuteOfDay)
            putString(KEY_REMINDER_CUE, value.cue)
        }

    /** The day the "on this day" note was put away: it stays hidden until tomorrow. */
    var memoryHiddenOn: String?
        get() = sp.getString(KEY_MEMORY_HIDDEN, null)
        set(value) = sp.edit { putString(KEY_MEMORY_HIDDEN, value) }

    /** The last month whose "your month in review is ready" nudge was opened or put away. */
    var recapSeen: String?
        get() = sp.getString(KEY_RECAP_SEEN, null)
        set(value) = sp.edit { putString(KEY_RECAP_SEEN, value) }

    var mala: MalaCount
        get() = MalaCount(sp.getInt(KEY_MALA_BEADS, 0), sp.getInt(KEY_MALA_ROUNDS, 0), sp.getInt(KEY_MALA_TARGET, 108))
        set(value) = sp.edit {
            putInt(KEY_MALA_BEADS, value.beads)
            putInt(KEY_MALA_ROUNDS, value.rounds)
            putInt(KEY_MALA_TARGET, value.target)
        }

    private inline fun <reified T : Enum<T>> enumOrDefault(name: String?, default: T): T =
        enumValues<T>().firstOrNull { it.name == name } ?: default

    companion object {
        /** Bumped when settings are restored, so open screens reload them. */
        val version = kotlinx.coroutines.flow.MutableStateFlow(0)

        private const val KEY_WEEKLY_GOAL = "weekly_goal"
        private const val KEY_AMBIENCE = "ambience"
        private const val KEY_AMBIENCE_VOLUME = "ambience_volume"
        private const val KEY_AMBIENCE_URI = "ambience_uri"
        private const val KEY_AMBIENCE_NAME = "ambience_name"
        private const val KEY_MEMORY_HIDDEN = "memory_hidden_on"
        private const val KEY_RECAP_SEEN = "recap_seen"
        private const val KEY_DURATION = "duration_min"
        private const val KEY_OPENING = "opening_bell_sec"
        private const val KEY_CLOSING = "closing_bell_sec"
        private const val KEY_END = "bell_at_end"
        private const val KEY_INTERVAL = "interval_min"
        private const val KEY_VOLUME = "volume"
        private const val KEY_ALERT = "alert_mode"
        private const val KEY_DND = "auto_dnd"
        private const val KEY_BREATH_PATTERN = "breath_pattern"
        private const val KEY_BREATH_MINUTES = "breath_minutes"
        private const val KEY_MALA_BEADS = "mala_beads"
        private const val KEY_MALA_ROUNDS = "mala_rounds"
        private const val KEY_MALA_TARGET = "mala_target"
        private const val KEY_BREATH_CHECKS = "breath_checks"
        private const val KEY_COUNT = "count_distractions"
        private const val KEY_CHECK_INS = "check_ins"
        private const val KEY_REMINDER_ON = "reminder_on"
        private const val KEY_REMINDER_TIME = "reminder_minute"
        private const val KEY_REMINDER_CUE = "reminder_cue"
    }
}
