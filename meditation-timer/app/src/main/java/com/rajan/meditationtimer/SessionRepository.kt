package com.rajan.meditationtimer

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface SessionState {
    data object Idle : SessionState

    /** [startElapsedMs] is on the SystemClock.elapsedRealtime() clock, which keeps counting in deep sleep. */
    data class Running(val startElapsedMs: Long, val config: SessionConfig) : SessionState

    /** [startedAtMs] identifies the logged record, so a reflection can be attached to it. */
    data class Finished(val config: SessionConfig, val startedAtMs: Long) : SessionState
}

/** Single source of truth shared by [MeditationService] (writer) and the UI (reader). */
object SessionRepository {
    private val _state = MutableStateFlow<SessionState>(SessionState.Idle)
    val state: StateFlow<SessionState> = _state.asStateFlow()

    fun started(startElapsedMs: Long, config: SessionConfig) {
        _state.value = SessionState.Running(startElapsedMs, config)
    }

    fun finished(config: SessionConfig, startedAtMs: Long) {
        _state.value = SessionState.Finished(config, startedAtMs)
    }

    fun reset() {
        _state.value = SessionState.Idle
    }
}
