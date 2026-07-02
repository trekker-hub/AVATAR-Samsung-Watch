package com.example.galaxywatch5.presentation

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.samsung.android.service.health.tracking.ConnectionListener
import com.samsung.android.service.health.tracking.HealthTracker
import com.samsung.android.service.health.tracking.HealthTrackerException
import com.samsung.android.service.health.tracking.HealthTrackingService
import com.samsung.android.service.health.tracking.data.DataPoint
import com.samsung.android.service.health.tracking.data.HealthTrackerType
import com.samsung.android.service.health.tracking.data.PpgType
import com.samsung.android.service.health.tracking.data.ValueKey

class MainActivity : ComponentActivity() {

    // ---------- Sensor menu definition ----------

    private data class SensorOption(
        val label: String,
        val type: HealthTrackerType,
        val ppgTypes: Set<PpgType>? = null,   // only for PPG trackers
        val onDemand: Boolean = false          // single ~30 s measurement, auto-stops
    )

    // Candidate options; the menu only shows the ones the watch reports as supported.
    private val candidates = listOf(
        SensorOption("Heart Rate", HealthTrackerType.HEART_RATE_CONTINUOUS),
        SensorOption("Accelerometer", HealthTrackerType.ACCELEROMETER_CONTINUOUS),
        SensorOption("PPG Green", HealthTrackerType.PPG_CONTINUOUS, setOf(PpgType.GREEN)),
        SensorOption("PPG IR + Red (30s)", HealthTrackerType.PPG_ON_DEMAND,
            setOf(PpgType.IR, PpgType.RED), onDemand = true),
        SensorOption("SpO2 (30s)", HealthTrackerType.SPO2_ON_DEMAND, onDemand = true),
        SensorOption("Skin Temp", HealthTrackerType.SKIN_TEMPERATURE_CONTINUOUS),
        SensorOption("Skin Temp (30s)", HealthTrackerType.SKIN_TEMPERATURE_ON_DEMAND,
            onDemand = true),
    )

    // ---------- UI state ----------

    private var connectionStatus by mutableStateOf("Requesting permissions...")
    private val menu = mutableStateListOf<SensorOption>()
    private var active by mutableStateOf<SensorOption?>(null)
    private val readings = mutableStateMapOf<String, String>()

    // ---------- SDK state ----------

    private var healthService: HealthTrackingService? = null
    private var tracker: HealthTracker? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val onDemandTimeout = Runnable { stopTracker("Timed out — check watch fit") }

    private val requiredPermissions = arrayOf(
        Manifest.permission.BODY_SENSORS,
        Manifest.permission.ACTIVITY_RECOGNITION
    )

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants.values.all { it }) connectToHealth()
        else connectionStatus = "Permissions denied"
    }

    // ---------- Lifecycle ----------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { Screen() }

        if (requiredPermissions.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) {
            connectToHealth()
        } else {
            permissionLauncher.launch(requiredPermissions)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopTracker(null)
        healthService?.disconnectService()
    }

    // ---------- Connection ----------

    private fun connectToHealth() {
        connectionStatus = "Connecting..."
        healthService = HealthTrackingService(object : ConnectionListener {
            override fun onConnectionSuccess() {
                val supported = healthService?.trackingCapability
                    ?.supportHealthTrackerTypes ?: emptyList()
                runOnUiThread {
                    menu.clear()
                    menu.addAll(candidates.filter { it.type in supported })
                    connectionStatus = "Connected — pick a sensor"
                }
            }
            override fun onConnectionEnded() {
                runOnUiThread { connectionStatus = "Disconnected" }
            }
            override fun onConnectionFailed(e: HealthTrackerException) {
                runOnUiThread { connectionStatus = "Failed (err ${e.errorCode})" }
            }
        }, this)
        healthService?.connectService()
    }

    // ---------- Start / stop ----------

    private fun startTracker(option: SensorOption) {
        stopTracker(null)  // safety: never two trackers at once
        val svc = healthService ?: return
        readings.clear()
        readings["status"] = if (option.onDemand) "measuring… hold still" else "waiting…"
        active = option

        try {
            tracker = if (option.ppgTypes != null)
                svc.getHealthTracker(option.type, option.ppgTypes)
            else
                svc.getHealthTracker(option.type)

            tracker?.setEventListener(object : HealthTracker.TrackerEventListener {
                override fun onDataReceived(dataPoints: List<DataPoint>) {
                    val dp = dataPoints.lastOrNull() ?: return
                    runOnUiThread {
                        readings.remove("status")
                        format(option, dp)
                        // On-demand: stop once we have a completed reading
                        if (option.onDemand && isOnDemandComplete(option, dp)) {
                            stopTracker("Done")
                        }
                    }
                }
                override fun onFlushCompleted() {}
                override fun onError(e: HealthTracker.TrackerError) {
                    runOnUiThread {
                        readings["status"] = "error: $e"
                        // SDK_POLICY_ERROR → enable Health Platform developer mode
                    }
                }
            })

            if (option.onDemand) mainHandler.postDelayed(onDemandTimeout, 35_000L)
        } catch (e: HealthTrackerException) {
            readings["status"] = "err ${e.errorCode}"
        } catch (e: Exception) {
            readings["status"] = e.javaClass.simpleName
        }
    }

    private fun stopTracker(finalStatus: String?) {
        mainHandler.removeCallbacks(onDemandTimeout)
        tracker?.let { runCatching { it.unsetEventListener() } }
        tracker = null
        if (finalStatus != null) readings["status"] = finalStatus
        else active = null
    }

    private fun isOnDemandComplete(option: SensorOption, dp: DataPoint): Boolean = try {
        when (option.type) {
            HealthTrackerType.SPO2_ON_DEMAND ->
                dp.getValue(ValueKey.SpO2Set.STATUS) == 2       // MEASUREMENT_COMPLETED
            HealthTrackerType.SKIN_TEMPERATURE_ON_DEMAND ->
                dp.getValue(ValueKey.SkinTemperatureSet.STATUS) == 0
            else -> false  // PPG on-demand: let the 30 s timer end it
        }
    } catch (_: Exception) { false }

    // ---------- Formatting (current ValueKey sets only) ----------

    private fun format(option: SensorOption, dp: DataPoint) {
        try {
            when (option.type) {
                HealthTrackerType.HEART_RATE_CONTINUOUS -> {
                    val st = dp.getValue(ValueKey.HeartRateSet.HEART_RATE_STATUS)
                    readings["Heart rate"] =
                        "${dp.getValue(ValueKey.HeartRateSet.HEART_RATE)} bpm"
                    readings["Signal"] = if (st == 1) "good" else "poor (st $st)"
                    val ibi = dp.getValue(ValueKey.HeartRateSet.IBI_LIST)
                    readings["IBI"] = ibi?.joinToString() ?: "—"
                }
                HealthTrackerType.ACCELEROMETER_CONTINUOUS -> {
                    readings["x"] = "${dp.getValue(ValueKey.AccelerometerSet.ACCELEROMETER_X)}"
                    readings["y"] = "${dp.getValue(ValueKey.AccelerometerSet.ACCELEROMETER_Y)}"
                    readings["z"] = "${dp.getValue(ValueKey.AccelerometerSet.ACCELEROMETER_Z)}"
                }
                HealthTrackerType.PPG_CONTINUOUS, HealthTrackerType.PPG_ON_DEMAND -> {
                    option.ppgTypes?.forEach { t ->
                        when (t) {
                            PpgType.GREEN -> readings["PPG green"] =
                                "${dp.getValue(ValueKey.PpgSet.PPG_GREEN)} " +
                                        "(st ${dp.getValue(ValueKey.PpgSet.GREEN_STATUS)})"
                            PpgType.IR -> readings["PPG IR"] =
                                "${dp.getValue(ValueKey.PpgSet.PPG_IR)} " +
                                        "(st ${dp.getValue(ValueKey.PpgSet.IR_STATUS)})"
                            PpgType.RED -> readings["PPG red"] =
                                "${dp.getValue(ValueKey.PpgSet.PPG_RED)} " +
                                        "(st ${dp.getValue(ValueKey.PpgSet.RED_STATUS)})"
                        }
                    }
                }
                HealthTrackerType.SPO2_ON_DEMAND -> {
                    val st = dp.getValue(ValueKey.SpO2Set.STATUS)
                    val v = dp.getValue(ValueKey.SpO2Set.SPO2)
                    readings["SpO2"] = if (st == 2) "$v %" else "calculating… (st $st)"
                    val hr = runCatching {
                        dp.getValue(ValueKey.SpO2Set.HEART_RATE)
                    }.getOrNull()
                    if (hr != null) readings["HR"] = "$hr bpm"
                }
                HealthTrackerType.SKIN_TEMPERATURE_CONTINUOUS,
                HealthTrackerType.SKIN_TEMPERATURE_ON_DEMAND -> {
                    readings["Skin"] =
                        "${dp.getValue(ValueKey.SkinTemperatureSet.OBJECT_TEMPERATURE)} °C"
                    readings["Ambient"] =
                        "${dp.getValue(ValueKey.SkinTemperatureSet.AMBIENT_TEMPERATURE)} °C"
                }
                else -> readings["raw"] = "data received"
            }
        } catch (e: Exception) {
            readings["parse"] = e.javaClass.simpleName
        }
    }

    // ---------- UI ----------

    @androidx.compose.runtime.Composable
    private fun Screen() {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            BasicText(
                "AVATAR — Sensor Test",
                style = TextStyle(
                    color = Color(0xFF0FE0B0), fontSize = 14.sp,
                    fontWeight = FontWeight.Bold, textAlign = TextAlign.Center
                )
            )
            Spacer(Modifier.height(4.dp))

            val current = active
            if (current == null) {
                // -------- Menu --------
                BasicText(
                    connectionStatus,
                    style = TextStyle(color = Color.White, fontSize = 11.sp,
                        textAlign = TextAlign.Center)
                )
                Spacer(Modifier.height(10.dp))
                menu.forEach { option ->
                    MenuButton(option.label, Color(0xFF1E1E1E)) { startTracker(option) }
                }
                if (menu.isEmpty() && connectionStatus.startsWith("Connected")) {
                    BasicText("No supported sensors found",
                        style = TextStyle(color = Color.Gray, fontSize = 10.sp))
                }
            } else {
                // -------- Live readings --------
                BasicText(
                    current.label,
                    style = TextStyle(color = Color.White, fontSize = 12.sp,
                        fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                )
                Spacer(Modifier.height(8.dp))
                readings.entries.sortedBy { it.key }.forEach { (name, value) ->
                    BasicText(name, style = TextStyle(color = Color(0xFF888888),
                        fontSize = 10.sp, textAlign = TextAlign.Center))
                    BasicText(value, style = TextStyle(color = Color(0xFF33F26B),
                        fontSize = 12.sp, textAlign = TextAlign.Center))
                    Spacer(Modifier.height(6.dp))
                }
                Spacer(Modifier.height(10.dp))
                MenuButton("Stop / Back", Color(0xFF3A1212)) { stopTracker(null) }
            }
        }
    }

    @androidx.compose.runtime.Composable
    private fun MenuButton(label: String, bg: Color, onClick: () -> Unit) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 3.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(bg)
                .clickable { onClick() }
                .padding(vertical = 10.dp),
            contentAlignment = Alignment.Center
        ) {
            BasicText(label, style = TextStyle(color = Color.White, fontSize = 12.sp))
        }
    }
}