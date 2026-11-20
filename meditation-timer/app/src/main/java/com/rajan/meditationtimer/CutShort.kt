package com.rajan.meditationtimer

/**
 * How far a sit had got, written down as it goes (at the start, every minute and on pause; see
 * [SitCheckpoint]). Some phones close apps to save battery even while a timer runs in the
 * foreground, and a closed app can't log the sit it was timing: this is what lets the next open
 * log it anyway.
 */
data class SitProgress(
    val startedAtMs: Long,
    val plannedSec: Int,
    /** Sitting time at the last write, pauses left out. */
    val satMs: Long,
    val before: Int = 0,
    val noticed: Int = -1,
) {
    val satSec: Int get() = (satMs / 1000).coerceIn(0L, plannedSec.toLong()).toInt()

    /**
     * The record to log for a sit that was cut short: the minutes sat, like an early end. None
     * under a minute (as for an early end), or when the sit is already in the log: it finished,
     * and the app was closed before it could tidy up.
     */
    fun record(logged: List<SessionRecord>): SessionRecord? {
        if (satSec < MIN_LOGGED_SEC) return null
        if (logged.any { it.startedAtMs / 60_000 == startedAtMs / 60_000 }) return null
        return SessionRecord(startedAtMs, plannedSec, satSec, before = before, noticed = noticed)
    }

    fun encode(): String = "$startedAtMs,$plannedSec,$satMs,$before,$noticed"

    companion object {
        fun decode(text: String?): SitProgress? {
            val p = text?.split(',')?.takeIf { it.size == 5 } ?: return null
            return runCatching {
                SitProgress(p[0].toLong(), p[1].toInt(), p[2].toLong(), p[3].toInt(), p[4].toInt())
            }.getOrNull()?.takeIf { it.plannedSec > 0 && it.satMs >= 0 }
        }
    }
}

/** A sit that was cut short, as the Sit screen mentions it once afterwards. */
data class CutShort(val satSec: Int, val plannedSec: Int) {
    val logged: Boolean get() = satSec >= MIN_LOGGED_SEC

    fun message(): String {
        val planned = plannedSec / 60
        return if (logged) {
            val sat = satSec / 60
            "It stopped after $sat of its $planned minutes: Android closed the app, probably to save " +
                "battery. The $sat ${if (sat == 1) "minute is" else "minutes are"} in your history."
        } else {
            "It stopped in its first minute: Android closed the app, probably to save battery, " +
                "so there was nothing to log."
        }
    }

    fun encode(): String = "$satSec,$plannedSec"

    companion object {
        const val ADVICE = "To stop it happening, set this app's battery use to Unrestricted " +
            "(on some phones: No restrictions, or allow background activity)."

        fun of(progress: SitProgress) = CutShort(progress.satSec, progress.plannedSec)

        fun decode(text: String?): CutShort? {
            val p = text?.split(',')?.takeIf { it.size == 2 } ?: return null
            return runCatching { CutShort(p[0].toInt(), p[1].toInt()) }.getOrNull()?.takeIf { it.plannedSec > 0 }
        }
    }
}
