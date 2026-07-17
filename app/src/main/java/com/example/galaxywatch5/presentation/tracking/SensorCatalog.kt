package com.example.galaxywatch5.presentation.tracking

import com.samsung.android.service.health.tracking.data.HealthTrackerType
import com.samsung.android.service.health.tracking.data.PpgType

/**
 * One entry in the sensor menu. Shared by MainActivity (menu UI) and
 * SensorTrackingService (the tracking engine), so it lives outside both.
 */
data class SensorOption(
    val label: String,
    val type: HealthTrackerType,
    val ppgTypes: Set<PpgType>? = null,     // only for PPG trackers
    val onDemand: Boolean = false,          // single measurement, auto-stops
    val expectedSeconds: Int? = null,       // rough duration hint (null = continuous)
    val hint: String? = null,               // on-screen instruction (e.g. hold a button)
    val doneOnTimeout: Boolean = false      // true: timer expiry IS success (ECG/PPG capture),
                                             // false: timer expiry means it never completed
) {
    /** Stable identifier for passing through Intent extras. */
    val id: String get() = label
}

object SensorCatalog {

    // Candidate options; the menu only shows the ones the watch reports as supported.
    // On a Watch 8 you should see all of these; on a Watch 5, EDA and MF-BIA are hidden
    // automatically because the hardware doesn't report them.
    val candidates = listOf(
        SensorOption("Heart Rate", HealthTrackerType.HEART_RATE_CONTINUOUS),
        SensorOption("Accelerometer", HealthTrackerType.ACCELEROMETER_CONTINUOUS),
        SensorOption("PPG Green", HealthTrackerType.PPG_CONTINUOUS, setOf(PpgType.GREEN)),
        // EDA — the AVATAR stress/craving signal. Watch 8 only; continuous stream.
        SensorOption("EDA (skin conductance)", HealthTrackerType.EDA_CONTINUOUS,
            hint = "Wear snug, rest your arm and sit still"),
        SensorOption("PPG IR + Red (~30s)", HealthTrackerType.PPG_ON_DEMAND,
            setOf(PpgType.IR, PpgType.RED), onDemand = true,
            expectedSeconds = 30, hint = "Hold still", doneOnTimeout = true),
        SensorOption("SpO2 (~30s)", HealthTrackerType.SPO2_ON_DEMAND, onDemand = true,
            expectedSeconds = 30, hint = "Hold arm still and flat"),
        SensorOption("ECG (~30s)", HealthTrackerType.ECG_ON_DEMAND, onDemand = true,
            expectedSeconds = 30,
            hint = "Press metal crown firmly with index fingertip of other hand — stay still",
            doneOnTimeout = true),
        SensorOption("Body Comp / BIA (~15s)", HealthTrackerType.BIA_ON_DEMAND, onDemand = true,
            expectedSeconds = 15,
            hint = "Place index + middle finger of free hand on the two metal rails on the watch frame"),
        SensorOption("Multi-freq BIA (~15s)", HealthTrackerType.MF_BIA_ON_DEMAND, onDemand = true,
            expectedSeconds = 15,
            hint = "Place index + middle finger of free hand on the two metal rails on the watch frame"),
        SensorOption("Skin Temp", HealthTrackerType.SKIN_TEMPERATURE_CONTINUOUS),
        SensorOption("Skin Temp (~5s)", HealthTrackerType.SKIN_TEMPERATURE_ON_DEMAND,
            onDemand = true, expectedSeconds = 5),
    )

    // ECG removed: LEAD_OFF returns -1 on this firmware regardless of contact,
    // so we can't gate the timer on it. BIA/MF_BIA still detect contact via PROGRESS.
    val contactRequiredTypes = setOf(
        HealthTrackerType.BIA_ON_DEMAND,
        HealthTrackerType.MF_BIA_ON_DEMAND
    )

    fun byId(id: String?): SensorOption? = candidates.firstOrNull { it.id == id }
}
