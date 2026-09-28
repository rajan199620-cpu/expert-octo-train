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

    /** Everything that shapes your usual sit, for the backup file. */
    fun exportSettings(): Map<String, String> = buildMap {
        for (key in listOf(KEY_DURATION, KEY_OPENING, KEY_CLOSING, KEY_INTERVAL, KEY_BREATH_MINUTES, KEY_MALA_TARGET)) {
            if (sp.contains(key)) put(key, sp.getInt(key, 0).toString())
        }
        for (key in listOf(KEY_END, KEY_DND)) if (sp.contains(key)) put(key, sp.getBoolean(key, false).toString())
        if (sp.contains(KEY_VOLUME)) put(KEY_VOLUME, sp.getFloat(KEY_VOLUME, 0.6f).toString())
        for (key in listOf(KEY_ALERT, KEY_BREATH_PATTERN)) sp.getString(key, null)?.let { put(key, it) }
    }

    /** Restores settings from a backup; unknown or malformed values are ignored. */
    fun importSettings(settings: Map<String, String>) {
        sp.edit {
            for ((key, value) in settings) {
                when (key) {
                    KEY_DURATION, KEY_OPENING, KEY_CLOSING, KEY_INTERVAL, KEY_BREATH_MINUTES, KEY_MALA_TARGET ->
                        value.toIntOrNull()?.let { putInt(key, it) }
                    KEY_END, KEY_DND -> value.toBooleanStrictOrNull()?.let { putBoolean(key, it) }
                    KEY_VOLUME -> value.toFloatOrNull()?.let { putFloat(key, it.coerceIn(0f, 1f)) }
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
    }
}
