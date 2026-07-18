package com.example.galaxywatch5.presentation.tracking

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class AutoRunProgress(val index: Int, val total: Int)

// ---- Multi-sensor monitoring samples (one per continuous tracker) ----
// sdkTs = the sample's own SDK timestamp; recvTs = System.currentTimeMillis() at receipt.

data class AccelSample(val x: Int, val y: Int, val z: Int, val n: Int, val sdkTs: Long, val recvTs: Long)
// IBI rides inside the heart-rate DataPoint, so it is a field here rather than a separate tracker/flow.
data class HrSample(
    val hr: Int, val status: Int,
    val ibi: List<Int>, val ibiStatus: List<Int>,
    val sdkTs: Long, val recvTs: Long
)
data class EdaSample(val skinConductance: Float, val status: Int, val sdkTs: Long, val recvTs: Long)

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

    // ---- Multi-sensor "Monitor All" mode: one flow per continuous signal ----
    private val _monitoringAll = MutableStateFlow(false)
    val monitoringAll: StateFlow<Boolean> = _monitoringAll.asStateFlow()

    private val _accel = MutableStateFlow<AccelSample?>(null)
    val accel: StateFlow<AccelSample?> = _accel.asStateFlow()

    private val _heartRate = MutableStateFlow<HrSample?>(null)
    val heartRate: StateFlow<HrSample?> = _heartRate.asStateFlow()

    private val _eda = MutableStateFlow<EdaSample?>(null)
    val eda: StateFlow<EdaSample?> = _eda.asStateFlow()

    internal fun setActive(option: SensorOption?) { _active.value = option }
    internal fun publishReadings(map: Map<String, String>) { _readings.value = map }
    internal fun setAutoRun(progress: AutoRunProgress?) { _autoRun.value = progress }
    internal fun postStatus(message: String) { _statusMessage.value = message }

    // Multi-sensor setters (written directly from the SDK binder thread — StateFlow is thread-safe).
    internal fun setMonitoringAll(on: Boolean) { _monitoringAll.value = on }
    internal fun setAccel(sample: AccelSample) { _accel.value = sample }
    internal fun setHeartRate(sample: HrSample) { _heartRate.value = sample }
    internal fun setEda(sample: EdaSample) { _eda.value = sample }

    internal fun clearMultiSamples() {
        _accel.value = null
        _heartRate.value = null
        _eda.value = null
    }

    internal fun clear() {
        _active.value = null
        _readings.value = emptyMap()
        _autoRun.value = null
        _monitoringAll.value = false
        clearMultiSamples()
    }
}
