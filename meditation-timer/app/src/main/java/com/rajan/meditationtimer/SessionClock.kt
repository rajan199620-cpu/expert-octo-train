package com.rajan.meditationtimer

/**
 * Time actually sat, on the SystemClock.elapsedRealtime() clock, with pauses taken out.
 * Kept free of Android types so it can be unit-tested; the service and the UI both read it.
 */
data class SessionClock(
    val startMs: Long,
    val pausedTotalMs: Long = 0,
    val pausedSinceMs: Long? = null,
) {
    val isPaused: Boolean get() = pausedSinceMs != null

    /** While paused the clock stands still at the moment of pausing. */
    fun elapsedAt(nowMs: Long): Long = ((pausedSinceMs ?: nowMs) - startMs - pausedTotalMs).coerceAtLeast(0)

    fun pause(nowMs: Long): SessionClock = if (isPaused) this else copy(pausedSinceMs = nowMs)

    fun resume(nowMs: Long): SessionClock {
        val since = pausedSinceMs ?: return this
        return copy(pausedTotalMs = pausedTotalMs + (nowMs - since).coerceAtLeast(0), pausedSinceMs = null)
    }

    companion object {
        /** Tapping End opens this window: the sit is paused and you can still choose to keep going. */
        const val END_CONFIRM_MS = 5_000L
    }
}
