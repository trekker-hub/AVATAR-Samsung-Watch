package com.example.galaxywatch5.presentation.tracking

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class AutoRunProgress(val index: Int, val total: Int)

/**
 * Single source of truth for tracking-session state. SensorTrackingService writes,
 * the Compose UI collects. Keeps the tracker lifecycle fully decoupled from the
 * Activity: the UI can die and come back mid-session without touching the stream.
 */
object TrackingRepository {

    private val _active = MutableStateFlow<SensorOption?>(null)
    val active: StateFlow<SensorOption?> = _active.asStateFlow()

    private val _readings = MutableStateFlow<Map<String, String>>(emptyMap())
    val readings: StateFlow<Map<String, String>> = _readings.asStateFlow()

    private val _autoRun = MutableStateFlow<AutoRunProgress?>(null)
    val autoRun: StateFlow<AutoRunProgress?> = _autoRun.asStateFlow()

    // Transient banner text, e.g. "Session saved: avatar_x.jsonl"
    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage: StateFlow<String?> = _statusMessage.asStateFlow()

    internal fun setActive(option: SensorOption?) { _active.value = option }
    internal fun publishReadings(map: Map<String, String>) { _readings.value = map }
    internal fun setAutoRun(progress: AutoRunProgress?) { _autoRun.value = progress }
    internal fun postStatus(message: String) { _statusMessage.value = message }

    internal fun clear() {
        _active.value = null
        _readings.value = emptyMap()
        _autoRun.value = null
    }
}
