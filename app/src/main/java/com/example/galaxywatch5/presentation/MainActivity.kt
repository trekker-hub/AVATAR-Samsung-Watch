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
import androidx.compose.runtime.remember
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
import com.example.galaxywatch5.presentation.logging.DataLogger
import com.example.galaxywatch5.presentation.ui.LogsScreen
import com.google.android.gms.wearable.Wearable
import java.io.File
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
        val ppgTypes: Set<PpgType>? = null,     // only for PPG trackers
        val onDemand: Boolean = false,          // single measurement, auto-stops
        val expectedSeconds: Int? = null,       // rough duration hint (null = continuous)
        val hint: String? = null,               // on-screen instruction (e.g. hold a button)
        val doneOnTimeout: Boolean = false      // true: timer expiry IS success (ECG/PPG capture),
                                                 // false: timer expiry means it never completed
    )

    // Candidate options; the menu only shows the ones the watch reports as supported.
    // On a Watch 8 you should see all of these; on a Watch 5, EDA and MF-BIA are hidden
    // automatically because the hardware doesn't report them.
    private val candidates = listOf(
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

    // ---------- UI state ----------

    private var connectionStatus by mutableStateOf("Requesting permissions...")
    private val menu = mutableStateListOf<SensorOption>()
    private var active by mutableStateOf<SensorOption?>(null)
    private val readings = mutableStateMapOf<String, String>()
    // Landing screen is the AVATAR stress gauge; tapping it opens the sensor menu.
    private var showMenu by mutableStateOf(false)
    private var showLogs by mutableStateOf(false)
    private var serviceConnected by mutableStateOf(false)

    // ---- Live values for the home gauge (streamed by dedicated continuous trackers) ----
    private var liveHr by mutableStateOf<Int?>(null)
    private var liveEda by mutableStateOf<Float?>(null)
    private var homeHrTracker: HealthTracker? = null
    private var homeEdaTracker: HealthTracker? = null

    // ---------- SDK state ----------

    private var healthService: HealthTrackingService? = null
    private var tracker: HealthTracker? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var measureStart = 0L   // SystemClock.elapsedRealtime() when tracking began
    private var contactMade = false  // true once skin/finger contact is detected
    private var packetCount = 0      // how many onDataReceived calls have fired
    private var autoRunQueue: List<SensorOption> = emptyList()
    private var autoRunIndex = -1    // -1 = not in auto-run
    private var dataLogger: DataLogger? = null
    // ECG removed: LEAD_OFF returns -1 on this firmware regardless of contact,
    // so we can't gate the timer on it. BIA/MF_BIA still detect contact via PROGRESS.
    private val contactRequiredTypes = setOf(
        HealthTrackerType.BIA_ON_DEMAND,
        HealthTrackerType.MF_BIA_ON_DEMAND
    )

    // Fires only if an on-demand sensor never reports completion.
    private val onDemandTimeout = Runnable {
        val opt = active
        if (opt?.doneOnTimeout == true) stopTracker("Done — ${opt.expectedSeconds}s capture")
        else stopTracker("Timed out — check watch fit / contact")
    }

    // In auto-run, caps continuous sensors at 30 s then advances to the next sensor.
    private val autoAdvanceRunnable = Runnable { stopTracker("⏭ 30s done") }

    // Repeats every second while a sensor is active, so the user can SEE it's alive
    // (on-demand sensors otherwise show nothing for up to 30 s).
    private val ticker = object : Runnable {
        override fun run() {
            val opt = active
            if (opt == null || tracker == null) return
            val secs = ((android.os.SystemClock.elapsedRealtime() - measureStart) / 1000).toInt()
            readings[" status"] = when {
                opt.type in contactRequiredTypes && !contactMade ->
                    "👆 waiting for contact…"
                opt.onDemand && opt.expectedSeconds != null ->
                    "⏳ measuring · ${secs}s / ~${opt.expectedSeconds}s"
                opt.onDemand -> "⏳ measuring · ${secs}s"
                else -> "🟢 live · ${secs}s"
            }
            mainHandler.postDelayed(this, 1000L)
        }
    }

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
        stopHomeMonitoring()
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
                // Q3: is ECG_ON_DEMAND explicitly in the supported list?
                val ecgOk = HealthTrackerType.ECG_ON_DEMAND in supported
                runOnUiThread {
                    menu.clear()
                    menu.addAll(candidates.filter { it.type in supported })
                    connectionStatus = "Connected (${supported.size} types, ECG_ON_DEMAND=$ecgOk)"
                    serviceConnected = true
                }
            }
            override fun onConnectionEnded() {
                runOnUiThread { connectionStatus = "Disconnected"; serviceConnected = false }
            }
            override fun onConnectionFailed(e: HealthTrackerException) {
                runOnUiThread { connectionStatus = "Failed (err ${e.errorCode})"; serviceConnected = false }
            }
        }, this)
        healthService?.connectService()
    }

    // ---------- Home gauge live monitoring ----------
    // Dedicated HR + EDA continuous trackers that feed the gauge. Kept separate from the
    // menu's single `tracker`/`active` system so the two never interfere. Runs only while
    // the gauge is on screen (started/stopped by HomeGauge's DisposableEffect).

    private fun startHomeMonitoring() {
        val svc = healthService ?: return
        if (homeHrTracker != null || homeEdaTracker != null) return  // already running
        val supported = svc.trackingCapability?.supportHealthTrackerTypes ?: emptyList()

        // Heart rate — continuous stream
        runCatching {
            val t = svc.getHealthTracker(HealthTrackerType.HEART_RATE_CONTINUOUS)
            t.setEventListener(object : HealthTracker.TrackerEventListener {
                override fun onDataReceived(dataPoints: List<DataPoint>) {
                    val dp = dataPoints.lastOrNull() ?: return
                    val hr = runCatching { dp.getValue(ValueKey.HeartRateSet.HEART_RATE) }.getOrNull()
                    if (hr != null && hr > 0) runOnUiThread { liveHr = hr }
                }
                override fun onFlushCompleted() {}
                override fun onError(e: HealthTracker.TrackerError) {}
            })
            homeHrTracker = t
        }

        // EDA (skin conductance) — continuous stream, Watch 8 only
        if (HealthTrackerType.EDA_CONTINUOUS in supported) {
            runCatching {
                val t = svc.getHealthTracker(HealthTrackerType.EDA_CONTINUOUS)
                t.setEventListener(object : HealthTracker.TrackerEventListener {
                    override fun onDataReceived(dataPoints: List<DataPoint>) {
                        val dp = dataPoints.lastOrNull() ?: return
                        val sc = runCatching {
                            (dp.getValue(ValueKey.EdaSet.SKIN_CONDUCTANCE) as Number).toFloat()
                        }.getOrNull()
                        if (sc != null) runOnUiThread { liveEda = sc }
                    }
                    override fun onFlushCompleted() {}
                    override fun onError(e: HealthTracker.TrackerError) {}
                })
                homeEdaTracker = t
            }
        }
    }

    private fun stopHomeMonitoring() {
        homeHrTracker?.let { runCatching { it.unsetEventListener() } }
        homeEdaTracker?.let { runCatching { it.unsetEventListener() } }
        homeHrTracker = null
        homeEdaTracker = null
    }

    // ---------- Start / stop ----------

    private fun startTracker(option: SensorOption) {
        // Tear down any running tracker without touching auto-run state.
        // (stopTracker(null) would reset autoRunIndex and break sequencing.)
        mainHandler.removeCallbacks(onDemandTimeout)
        mainHandler.removeCallbacks(ticker)
        mainHandler.removeCallbacks(autoAdvanceRunnable)
        tracker?.let { runCatching { it.unsetEventListener() } }
        tracker = null
        val svc = healthService ?: return
        // For individual manual taps (not part of auto-run), start a fresh session log
        if (autoRunIndex < 0 && dataLogger == null) {
            dataLogger = DataLogger(this, autoRun = false)
        }
        readings.clear()
        option.hint?.let { readings[" info"] = it }
        readings[" status"] = if (option.onDemand) "starting measurement…" else "connecting…"
        active = option
        contactMade = false
        packetCount = 0
        measureStart = android.os.SystemClock.elapsedRealtime()
        dataLogger?.event("sensor_start", option.label)

        try {
            tracker = if (option.ppgTypes != null)
                svc.getHealthTracker(option.type, option.ppgTypes)
            else
                svc.getHealthTracker(option.type)

            tracker?.setEventListener(object : HealthTracker.TrackerEventListener {
                override fun onDataReceived(dataPoints: List<DataPoint>) {
                    runOnUiThread {
                        packetCount++
                        readings["[dbg] packets"] = "#$packetCount  list.size=${dataPoints.size}"
                        val dp = dataPoints.lastOrNull() ?: return@runOnUiThread
                        format(option, dp)
                        // Log full readings snapshot including all debug fields
                        dataLogger?.log(option.label, readings.toMap())
                        if (option.onDemand && isOnDemandComplete(option, dp)) {
                            stopTracker("✅ Done")
                        }
                    }
                }
                override fun onFlushCompleted() {}
                override fun onError(e: HealthTracker.TrackerError) {
                    val msg = when (e) {
                        HealthTracker.TrackerError.PERMISSION_ERROR ->
                            "PERMISSION_ERROR — BODY_SENSORS / READ_HEART_RATE not granted at runtime"
                        HealthTracker.TrackerError.SDK_POLICY_ERROR ->
                            "SDK_POLICY_ERROR — ECG outside app scope; enable Health Platform dev mode"
                        else -> "TrackerError: $e"
                    }
                    runOnUiThread {
                        readings["[dbg] onError"] = msg
                        dataLogger?.event("sdk_error", option.label, msg)
                    }
                }
            })

            mainHandler.post(ticker)
            // Contact-required sensors delay their timeout until finger/skin contact is detected
            // (handled in format() when the first valid reading arrives)
            if (option.onDemand && option.type !in contactRequiredTypes) {
                val timeoutMs = ((option.expectedSeconds ?: 30) + 8) * 1000L
                mainHandler.postDelayed(onDemandTimeout, timeoutMs)
            }
        } catch (e: HealthTrackerException) {
            dataLogger?.event("init_error", option.label, "HealthTrackerException ${e.errorCode}")
            stopTracker("err ${e.errorCode}")
        } catch (e: IllegalArgumentException) {
            val isBia = option.type == HealthTrackerType.BIA_ON_DEMAND ||
                        option.type == HealthTrackerType.MF_BIA_ON_DEMAND
            val msg = if (isBia)
                "BIA needs profile: open Samsung Health → Profile → set age/height/weight/sex"
            else "IllegalArgumentException"
            dataLogger?.event("init_error", option.label, msg)
            stopTracker(msg)
        } catch (e: Exception) {
            dataLogger?.event("init_error", option.label, e.javaClass.simpleName)
            stopTracker(e.javaClass.simpleName)
        }
    }

    private fun stopTracker(finalStatus: String?) {
        mainHandler.removeCallbacks(onDemandTimeout)
        mainHandler.removeCallbacks(ticker)
        mainHandler.removeCallbacks(autoAdvanceRunnable)
        tracker?.let { runCatching { it.unsetEventListener() } }
        tracker = null
        active?.let { dataLogger?.event("sensor_end", it.label, finalStatus ?: "cancelled") }
        if (finalStatus != null) {
            readings[" status"] = finalStatus
            if (autoRunIndex >= 0) {
                autoRunIndex++
                if (autoRunIndex < autoRunQueue.size) {
                    mainHandler.postDelayed({ launchAutoStep() }, 1500L)
                } else {
                    finishAutoRun()
                }
            }
        } else {
            // Manual stop / back — save partial session file in background, return to menu
            autoRunIndex = -1
            autoRunQueue = emptyList()
            val logger = dataLogger
            dataLogger = null
            active = null
            if (logger != null) {
                Thread {
                    val file = logger.finish()
                    runOnUiThread {
                        connectionStatus = "Session saved: ${file.name}"
                    }
                }.start()
            }
        }
    }

    // ---------- Auto-run ----------

    private fun startAutoRun() {
        val excluded = setOf(HealthTrackerType.BIA_ON_DEMAND, HealthTrackerType.MF_BIA_ON_DEMAND)
        autoRunQueue = menu.filter { it.type !in excluded }
        if (autoRunQueue.isEmpty()) return
        autoRunIndex = 0
        dataLogger = DataLogger(this, autoRun = true)
        launchAutoStep()
    }

    private fun launchAutoStep() {
        if (autoRunIndex < 0) return  // cancelled between steps
        val opt = autoRunQueue.getOrNull(autoRunIndex) ?: run { finishAutoRun(); return }
        startTracker(opt)
        // Continuous sensors have no built-in end; cap them at 30 s in auto-run
        if (!opt.onDemand) mainHandler.postDelayed(autoAdvanceRunnable, 30_000L)
    }

    private fun finishAutoRun() {
        autoRunIndex = -1
        autoRunQueue = emptyList()
        val logger = dataLogger
        dataLogger = null
        readings[" status"] = "✅ Auto run complete — saving…"
        if (logger != null) {
            val file = logger.finish()   // fast: just writes session_end line
            sendSessionToPhone(file)     // updates readings[" status"] and eventually clears active
        } else {
            mainHandler.postDelayed({ active = null }, 2000L)
        }
    }

    private fun sendSessionToPhone(file: File) {
        if (!file.exists()) { active = null; return }
        Wearable.getNodeClient(this).connectedNodes
            .addOnSuccessListener { nodes ->
                val node = nodes.firstOrNull()
                if (node == null) {
                    readings[" status"] = "No phone connected — file on watch:\n${file.name}"
                    mainHandler.postDelayed({ active = null }, 5000L)
                    return@addOnSuccessListener
                }
                readings[" status"] = "Sending to ${node.displayName}…"
                Wearable.getChannelClient(this)
                    .openChannel(node.id, "/avatar/${file.name}")
                    .addOnSuccessListener { ch ->
                        Wearable.getChannelClient(this).getOutputStream(ch)
                            .addOnSuccessListener { stream ->
                                Thread {
                                    try {
                                        file.inputStream().use { input -> input.copyTo(stream) }
                                        stream.close()
                                        Wearable.getChannelClient(this).close(ch)
                                        file.delete()
                                        runOnUiThread {
                                            readings[" status"] = "✅ Sent to ${node.displayName}"
                                            mainHandler.postDelayed({ active = null }, 3000L)
                                        }
                                    } catch (e: Exception) {
                                        runOnUiThread {
                                            readings[" status"] =
                                                "⚠ Send error — file kept on watch:\n${file.name}"
                                            mainHandler.postDelayed({ active = null }, 5000L)
                                        }
                                    }
                                }.start()
                            }
                            .addOnFailureListener {
                                readings[" status"] = "⚠ Stream error — file kept:\n${file.name}"
                                mainHandler.postDelayed({ active = null }, 5000L)
                            }
                    }
                    .addOnFailureListener {
                        readings[" status"] = "⚠ Channel error — file kept:\n${file.name}"
                        mainHandler.postDelayed({ active = null }, 5000L)
                    }
            }
            .addOnFailureListener {
                readings[" status"] = "⚠ Node lookup failed — file kept:\n${file.name}"
                mainHandler.postDelayed({ active = null }, 5000L)
            }
    }

    private fun isOnDemandComplete(option: SensorOption, dp: DataPoint): Boolean = try {
        when (option.type) {
            HealthTrackerType.SPO2_ON_DEMAND ->
                dp.getValue(ValueKey.SpO2Set.STATUS) == 2       // MEASUREMENT_COMPLETED
            HealthTrackerType.SKIN_TEMPERATURE_ON_DEMAND ->
                dp.getValue(ValueKey.SkinTemperatureSet.STATUS) == 0
            HealthTrackerType.BIA_ON_DEMAND ->
                runCatching { dp.getValue(ValueKey.BiaSet.PROGRESS) >= 100 }.getOrElse { false }
            HealthTrackerType.MF_BIA_ON_DEMAND ->
                runCatching { dp.getValue(ValueKey.MfBiaSet.PROGRESS) >= 100 }.getOrElse { false }
            // PPG and ECG on-demand stream raw samples with no "complete" flag —
            // the doneOnTimeout timer ends them after the capture window.
            else -> false
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
                HealthTrackerType.EDA_CONTINUOUS -> {
                    readings["Skin conductance"] =
                        "${dp.getValue(ValueKey.EdaSet.SKIN_CONDUCTANCE)} µS"
                    readings["Status"] = "${dp.getValue(ValueKey.EdaSet.STATUS)}"
                }
                HealthTrackerType.ECG_ON_DEMAND -> {
                    // LEAD_OFF is informational only — on this firmware it returns -1
                    // regardless of contact and never transitions to 0 or 5.
                    // Timer starts on first data packet (ECG removed from contactRequiredTypes).
                    val leadOff = runCatching { dp.getValue(ValueKey.EcgSet.LEAD_OFF) }.getOrElse { -99 }
                    readings["LEAD_OFF"] = when (leadOff) {
                        0    -> "0 (contact OK)"
                        5    -> "5 (no contact)"
                        -99  -> "read error"
                        else -> "$leadOff"
                    }
                    runCatching { readings["ECG mV"] = "${dp.getValue(ValueKey.EcgSet.ECG_MV)}" }
                    runCatching { readings["Seq"] = "${dp.getValue(ValueKey.EcgSet.SEQUENCE)}" }
                }
                HealthTrackerType.BIA_ON_DEMAND -> {
                    val progressResult = runCatching { dp.getValue(ValueKey.BiaSet.PROGRESS) }
                    progressResult.exceptionOrNull()?.let { ex ->
                        readings["BIA err"] = "${ex.javaClass.simpleName} — set profile in Samsung Health (Profile → age/height/weight/sex)"
                        return
                    }
                    val progress = (progressResult.getOrNull() as? Number)?.toInt() ?: 0
                    readings["Progress"] = "$progress %"
                    if (progress == 0) return  // fingers not on electrodes yet
                    if (!contactMade) {
                        contactMade = true
                        measureStart = android.os.SystemClock.elapsedRealtime()
                        mainHandler.postDelayed(
                            onDemandTimeout, ((option.expectedSeconds ?: 15) + 5) * 1000L
                        )
                    }
                    if (progress >= 100) {
                        readings["Body fat"] = runCatching {
                            "${dp.getValue(ValueKey.BiaSet.BODY_FAT_RATIO)} %"
                        }.getOrElse { "— (set profile in Samsung Health)" }
                        readings["Skeletal muscle"] = runCatching {
                            "${dp.getValue(ValueKey.BiaSet.SKELETAL_MUSCLE_MASS)} kg"
                        }.getOrElse { "—" }
                        readings["Body water"] = runCatching {
                            "${dp.getValue(ValueKey.BiaSet.TOTAL_BODY_WATER)} L"
                        }.getOrElse { "—" }
                        readings["BMR"] = runCatching {
                            "${dp.getValue(ValueKey.BiaSet.BASAL_METABOLIC_RATE)} kcal"
                        }.getOrElse { "—" }
                    }
                }
                HealthTrackerType.MF_BIA_ON_DEMAND -> {
                    val progressResult = runCatching { dp.getValue(ValueKey.MfBiaSet.PROGRESS) }
                    progressResult.exceptionOrNull()?.let { ex ->
                        readings["BIA err"] = "${ex.javaClass.simpleName} — set profile in Samsung Health"
                        return
                    }
                    val progress = (progressResult.getOrNull() as? Number)?.toInt() ?: 0
                    readings["Progress"] = "$progress %"
                    if (progress == 0) return  // fingers not on electrodes yet
                    if (!contactMade) {
                        contactMade = true
                        measureStart = android.os.SystemClock.elapsedRealtime()
                        mainHandler.postDelayed(
                            onDemandTimeout, ((option.expectedSeconds ?: 15) + 5) * 1000L
                        )
                    }
                    readings["Z 5kHz"] = runCatching {
                        "${dp.getValue(ValueKey.MfBiaSet.BODY_IMPEDANCE_MAGNITUDE_5K)} Ω"
                    }.getOrElse { "—" }
                    readings["Z 50kHz"] = runCatching {
                        "${dp.getValue(ValueKey.MfBiaSet.BODY_IMPEDANCE_MAGNITUDE_50K)} Ω"
                    }.getOrElse { "—" }
                    readings["Z 250kHz"] = runCatching {
                        "${dp.getValue(ValueKey.MfBiaSet.BODY_IMPEDANCE_MAGNITUDE_250K)} Ω"
                    }.getOrElse { "—" }
                    readings["Phase 50kHz"] = runCatching {
                        "${dp.getValue(ValueKey.MfBiaSet.BODY_IMPEDANCE_PHASE_50K)}°"
                    }.getOrElse { "—" }
                }
                else -> readings["raw"] = "data received"
            }
        } catch (e: Exception) {
            readings["parse"] = e.javaClass.simpleName
            dataLogger?.event("parse_error", option.label, e.javaClass.simpleName)
        }
    }

    // ---------- UI ----------

    @androidx.compose.runtime.Composable
    private fun Screen() {
        // Landing on the AVATAR gauge when no sensor is running and the menu isn't open.
        if (active == null && !showMenu) {
            HomeGauge()
            return
        }
        // Logs browser (reached from the sensor menu).
        if (active == null && showLogs) {
            LogsScreen(onBack = { showLogs = false })
            return
        }
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
            val inAuto = autoRunIndex >= 0
            if (current == null) {
                // -------- Menu --------
                BasicText(
                    connectionStatus,
                    style = TextStyle(color = Color.White, fontSize = 11.sp,
                        textAlign = TextAlign.Center)
                )
                Spacer(Modifier.height(10.dp))
                MenuButton("‹ Home (gauge)", Color(0xFF2A2440)) { showMenu = false }
                Spacer(Modifier.height(6.dp))
                MenuButton("🗎 Logs / Sessions", Color(0xFF23303A)) { showLogs = true }
                Spacer(Modifier.height(6.dp))
                MenuButton("▶ Auto Run All", Color(0xFF1A3A28)) { startAutoRun() }
                Spacer(Modifier.height(6.dp))
                menu.forEach { option ->
                    MenuButton(option.label, Color(0xFF1E1E1E)) { startTracker(option) }
                }
                if (menu.isEmpty() && connectionStatus.startsWith("Connected")) {
                    BasicText("No supported sensors found",
                        style = TextStyle(color = Color.Gray, fontSize = 10.sp))
                }
            } else {
                // -------- Live readings --------
                val title = if (inAuto)
                    "${current.label}  (${autoRunIndex + 1}/${autoRunQueue.size})"
                else
                    current.label
                BasicText(
                    title,
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
                val stopLabel = if (inAuto) "Cancel Auto Run" else "Stop / Back"
                MenuButton(stopLabel, Color(0xFF3A1212)) { stopTracker(null) }
            }
        }
    }

    /**
     * AVATAR landing screen: the stress gauge, mapped from whatever the app already has in
     * [readings]. Reads that state only — no changes to the tracker callbacks or SDK layer.
     * Tap anywhere to open the sensor menu.
     */
    @androidx.compose.runtime.Composable
    private fun HomeGauge() {
        // Stream live HR + EDA only while this screen is shown; stop on leave.
        androidx.compose.runtime.DisposableEffect(serviceConnected) {
            if (serviceConnected) startHomeMonitoring()
            onDispose { stopHomeMonitoring() }
        }
        // Live values once the sensors report; sample fallbacks until the first packet.
        val hr = liveHr ?: 72
        val eda = liveEda ?: 2.1f
        val score = remember(hr, eda) { deriveStressPlaceholder(hr, eda) }
        // Whole face is tappable to open the sensor menu (no on-screen label needed).
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable { showMenu = true }
        ) {
            StressGaugeRing(
                stressScore = score,
                stressLabel = avatarStressLabel(score),
                hr = hr,
                eda = eda,
                isMonitoring = serviceConnected && liveHr != null,
                ringColor = avatarStressColor(score),
            )
        }
    }

    /**
     * Placeholder stress score from live HR + EDA. NOT a validated stress model — it just lets
     * the gauge visibly respond to real data until a real model is wired in. The mockup fakes
     * this value too.
     */
    private fun deriveStressPlaceholder(hr: Int, eda: Float): Int {
        val hrPart = (hr - 60).coerceIn(0, 60) * 0.6f   // 0..36
        val edaPart = (eda * 6f).coerceIn(0f, 60f)      // 0..60
        return ((hrPart + edaPart) / 2f).toInt().coerceIn(5, 95)
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