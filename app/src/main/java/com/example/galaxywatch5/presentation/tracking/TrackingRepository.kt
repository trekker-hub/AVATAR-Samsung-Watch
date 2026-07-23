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
// Combined PPG_CONTINUOUS sample: three optical channels, each with its own status int.
data class PpgSample(
    val green: Int?, val ir: Int?, val red: Int?,
    val greenStatus: Int?, val irStatus: Int?, val redStatus: Int?,
    val n: Int, val sdkTs: Long, val recvTs: Long
)
// Skin (OBJECT_TEMPERATURE) + ambient temperature from one SkinTemperatureSet DataPoint.
data class SkinTempSample(
    val skin: Float?, val ambient: Float?, val status: Int?,
    val n: Int, val sdkTs: Long, val recvTs: Long
)

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

    private val _ppg = MutableStateFlow<PpgSample?>(null)
    val ppg: StateFlow<PpgSample?> = _ppg.asStateFlow()

    private val _skinTemp = MutableStateFlow<SkinTempSample?>(null)
    val skinTemp: StateFlow<SkinTempSample?> = _skinTemp.asStateFlow()

    // ---- Per-tracker enable switches (safety valve) ----
    // Keyed by the multi-monitor tracker labels. Known-good trio defaults ON; new sensors OFF, so
    // a misbehaving new tracker can be excluded (or dropped live) without a redeploy. Like
    // streamEnabled, deliberately NOT reset by clear() — persists for the life of the process.
    val defaultTrackerEnabled = mapOf(
        "EDA" to true, "Heart Rate" to true, "Accelerometer" to true,
        "PPG" to false, "Skin Temp" to false,
    )
    private val _trackerEnabled = MutableStateFlow(defaultTrackerEnabled)
    val trackerEnabled: StateFlow<Map<String, Boolean>> = _trackerEnabled.asStateFlow()
    internal fun setTrackerEnabled(label: String, on: Boolean) {
        _trackerEnabled.value = _trackerEnabled.value + (label to on)
    }

    // ---- Network streaming on/off (user preference; independent of sensors + local file) ----
    // Default ON so streaming "just works" once the PC server + adb reverse are up. Deliberately
    // NOT reset by clear() — the choice persists across sessions for the life of the process.
    private val _streamEnabled = MutableStateFlow(true)
    val streamEnabled: StateFlow<Boolean> = _streamEnabled.asStateFlow()
    internal fun setStreamEnabled(on: Boolean) { _streamEnabled.value = on }

    internal fun setActive(option: SensorOption?) { _active.value = option }
    internal fun publishReadings(map: Map<String, String>) { _readings.value = map }
    internal fun setAutoRun(progress: AutoRunProgress?) { _autoRun.value = progress }
    internal fun postStatus(message: String) { _statusMessage.value = message }

    // Multi-sensor setters (written directly from the SDK binder thread — StateFlow is thread-safe).
    internal fun setMonitoringAll(on: Boolean) { _monitoringAll.value = on }
    internal fun setAccel(sample: AccelSample) { _accel.value = sample }
    internal fun setHeartRate(sample: HrSample) { _heartRate.value = sample }
    internal fun setEda(sample: EdaSample) { _eda.value = sample }
    internal fun setPpg(sample: PpgSample) { _ppg.value = sample }
    internal fun setSkinTemp(sample: SkinTempSample) { _skinTemp.value = sample }

    internal fun clearMultiSamples() {
        _accel.value = null
        _heartRate.value = null
        _eda.value = null
        _ppg.value = null
        _skinTemp.value = null
    }

    internal fun clear() {
        _active.value = null
        _readings.value = emptyMap()
        _autoRun.value = null
        _monitoringAll.value = false
        clearMultiSamples()
    }
}
