package com.rajan.meditationtimer

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface SessionState {
    data object Idle : SessionState

    /**
     * A sit in progress, possibly paused. [endingAtMs] is set while the "Ending in 5…" window is
     * open (the clock is paused then too); all times are on SystemClock.elapsedRealtime().
     */
    data class Running(val clock: SessionClock, val config: SessionConfig, val endingAtMs: Long? = null) : SessionState {
        val isEnding: Boolean get() = endingAtMs != null
        val isPaused: Boolean get() = clock.isPaused && !isEnding
    }

    /** [startedAtMs] identifies the logged record, so a reflection can be attached to it. */
    data class Finished(
        val config: SessionConfig,
        val startedAtMs: Long,
        val satSec: Int,
        /** The check-in given before the sit (0 = skipped), so the after one can be asked to match. */
        val before: Int = 0,
        /** Times the mind was caught wandering; -1 when not counting. */
        val noticed: Int = -1,
    ) : SessionState {
        val endedEarly: Boolean get() = satSec < config.durationSec
    }
}

/** Single source of truth shared by [MeditationService] (writer) and the UI (reader). */
object SessionRepository {
    private val _state = MutableStateFlow<SessionState>(SessionState.Idle)
    val state: StateFlow<SessionState> = _state.asStateFlow()

    fun update(running: SessionState.Running) {
        _state.value = running
    }

    fun finished(config: SessionConfig, startedAtMs: Long, satSec: Int, before: Int = 0, noticed: Int = -1) {
        _state.value = SessionState.Finished(config, startedAtMs, satSec, before, noticed)
    }

    /** "How do you feel right now?" answered on the way into the sit; 0 = skipped. */
    @Volatile var pendingBefore: Int = 0

    /** Whether this sit counts mind-wandering, and the count so far (the UI taps, the service logs). */
    @Volatile var counting: Boolean = false
    private val _noticed = MutableStateFlow(0)
    val noticed: StateFlow<Int> = _noticed.asStateFlow()

    fun startCounting(enabled: Boolean) {
        counting = enabled
        _noticed.value = 0
    }

    fun noticedOnce() {
        if (counting) _noticed.value = _noticed.value + 1
    }

    fun reset() {
        _state.value = SessionState.Idle
    }
}
