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
    data class Finished(val config: SessionConfig, val startedAtMs: Long, val satSec: Int) : SessionState {
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

    fun finished(config: SessionConfig, startedAtMs: Long, satSec: Int) {
        _state.value = SessionState.Finished(config, startedAtMs, satSec)
    }

    fun reset() {
        _state.value = SessionState.Idle
    }
}
