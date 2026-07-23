package com.example.galaxywatch5.presentation

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.WindowManager
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
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
import com.example.galaxywatch5.presentation.tracking.SensorCatalog
import com.example.galaxywatch5.presentation.tracking.SensorOption
import com.example.galaxywatch5.presentation.tracking.SensorTrackingService
import com.example.galaxywatch5.presentation.tracking.TrackingRepository
import com.example.galaxywatch5.presentation.ui.AvatarScreen
import com.example.galaxywatch5.presentation.ui.DiagnosticScreen
import com.example.galaxywatch5.presentation.ui.LiveReadingsScreen
import com.example.galaxywatch5.presentation.ui.LogsScreen
import com.example.galaxywatch5.presentation.ui.MonitorAllScreenStyled
import com.example.galaxywatch5.presentation.ui.SensorTestsScreen
import com.example.galaxywatch5.presentation.ui.SensorToggleScreen
import com.example.galaxywatch5.presentation.ui.SettingsMenuScreen
import com.example.galaxywatch5.presentation.ui.StreamingScreen
import com.samsung.android.service.health.tracking.ConnectionListener
import com.samsung.android.service.health.tracking.HealthTracker
import com.samsung.android.service.health.tracking.HealthTrackerException
import com.samsung.android.service.health.tracking.HealthTrackingService
import com.samsung.android.service.health.tracking.data.DataPoint
import com.samsung.android.service.health.tracking.data.HealthTrackerType
import com.samsung.android.service.health.tracking.data.ValueKey

class MainActivity : ComponentActivity() {

    // ---------- UI state ----------

    private var connectionStatus by mutableStateOf("Requesting permissions...")
    private val menu = mutableStateListOf<SensorOption>()
    // Landing screen is the AVATAR stress gauge; tapping it opens the settings hub. null = gauge.
    private var secondaryScreen by mutableStateOf<AvatarScreen?>(null)
    private var serviceConnected by mutableStateOf(false)

    // ---- Live values for the home gauge (streamed by dedicated continuous trackers) ----
    private var liveHr by mutableStateOf<Int?>(null)
    private var liveEda by mutableStateOf<Float?>(null)
    private var homeHrTracker: HealthTracker? = null
    private var homeEdaTracker: HealthTracker? = null

    // ---------- SDK state ----------
    // The Activity keeps its own connection for the gauge streams and the capability
    // query that builds the menu. Sensor SESSIONS run on SensorTrackingService's own
    // independent connection so they survive the Activity being killed.

    private var healthService: HealthTrackingService? = null

    private val requiredPermissions = buildList {
        add(Manifest.permission.BODY_SENSORS)
        add(Manifest.permission.ACTIVITY_RECOGNITION)
        // The tracking foreground-service notification needs runtime consent on API 33+
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
    }.toTypedArray()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        // Notification denial must not block tracking — only sensors gate the SDK.
        if (sensorPermissionsGranted()) connectToHealth()
        else connectionStatus = "Permissions denied"
        // After the permission dialogs so the two system prompts never stack.
        ensureBatteryExemption()
    }

    private fun sensorPermissionsGranted(): Boolean = listOf(
        Manifest.permission.BODY_SENSORS,
        Manifest.permission.ACTIVITY_RECOGNITION
    ).all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    // ---------- Lifecycle ----------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { Screen() }

        if (requiredPermissions.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) {
            connectToHealth()
            ensureBatteryExemption()
        } else {
            permissionLauncher.launch(requiredPermissions)
        }
    }

    // Samsung suspends third-party foreground services during screen-off idle unless the
    // app is exempt from battery optimization. One UI Watch exposes no per-app battery
    // menu, so this in-app request is the only reliable way for the user to grant it.
    private fun ensureBatteryExemption() {
        val pm = getSystemService(PowerManager::class.java)
        if (pm.isIgnoringBatteryOptimizations(packageName)) return
        try {
            startActivity(
                Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:$packageName")
                )
            )
        } catch (e: ActivityNotFoundException) {
            runCatching {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }w
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopHomeMonitoring()
        // Do NOT touch SensorTrackingService here — sessions must outlive the Activity.
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
                    menu.addAll(SensorCatalog.candidates.filter { it.type in supported })
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

    // ---------- Tracking commands (handled by the foreground service) ----------

    private fun sendTrackingCommand(action: String, sensorId: String? = null) {
        val intent = Intent(this, SensorTrackingService::class.java).setAction(action)
        sensorId?.let { intent.putExtra(SensorTrackingService.EXTRA_SENSOR_ID, it) }
        startForegroundService(intent)
    }

    // ---------- Home gauge live monitoring ----------
    // Dedicated HR + EDA continuous trackers that feed the gauge. Kept separate from the
    // service's session tracker so the two never interfere. Runs only while
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
                    // Additive: stream IBI-derived instantaneous HR to logcat (does not touch gauge/JSON)
                    dataPoints.forEach { streamIbiBeats(it) }
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

    /**
     * Additive live stream (does NOT touch the JSON logger, the gauge, or the manifest).
     * For each good heartbeat interval in [dp], logs one logcat line under tag AVATAR_STREAM:
     *   IHR,<timestamp_ms>,<bpm>,<ibi_ms>      where bpm = 60000 / ibi_ms
     * Chart it on a computer with e.g.:  adb logcat -s AVATAR_STREAM
     *
     * Rules:
     *  - Skip the whole packet unless HEART_RATE_STATUS == 1.
     *  - Read IBI_LIST paired with IBI_STATUS_LIST; keep only IBIs whose status == 0.
     *    A missing/short status entry is treated as not-good (skipped), never assumed good.
     *  - Drop any IBI outside ~300–2000 ms (guards merged/garbage intervals; also excludes 0).
     *  - Timestamp with dp.getTimestamp(); offset each beat by the cumulative IBI sum within the
     *    packet so multiple beats land at their true times instead of sharing one timestamp.
     */
    private fun streamIbiBeats(dp: DataPoint) {
        val hrStatus = runCatching { dp.getValue(ValueKey.HeartRateSet.HEART_RATE_STATUS) }.getOrNull()
        if (hrStatus != 1) return
        val ibiList = runCatching { dp.getValue(ValueKey.HeartRateSet.IBI_LIST) }.getOrNull() ?: return
        val statusList = runCatching { dp.getValue(ValueKey.HeartRateSet.IBI_STATUS_LIST) }.getOrNull()
            ?: emptyList()
        // Diagnostic: only fires when the firmware does NOT return a status per IBI, so you can
        // see in logcat whether IBI_STATUS_LIST is actually populated on this device.
        if (statusList.size != ibiList.size) {
            android.util.Log.d("AVATAR_STREAM", "IBI_STATUS,ibi=${ibiList.size},status=${statusList.size}")
        }
        val baseTs = dp.getTimestamp()
        var cumulative = 0L
        ibiList.forEachIndexed { i, ibi ->
            val status = statusList.getOrNull(i) ?: return@forEachIndexed  // no paired status → skip
            if (status != 0) return@forEachIndexed
            if (ibi < 300 || ibi > 2000) return@forEachIndexed
            val ts = baseTs + cumulative
            cumulative += ibi
            val bpm = 60000 / ibi
            android.util.Log.d("AVATAR_STREAM", "IHR,$ts,$bpm,$ibi")
        }
    }

    // ---------- UI ----------

    @androidx.compose.runtime.Composable
    private fun Screen() {
        // Session state lives in the foreground service; the UI just observes it.
        val active by TrackingRepository.active.collectAsState()
        val readings by TrackingRepository.readings.collectAsState()
        val autoRun by TrackingRepository.autoRun.collectAsState()
        val statusMessage by TrackingRepository.statusMessage.collectAsState()
        val monitoringAll by TrackingRepository.monitoringAll.collectAsState()

        // Force the display on for the whole session: screen-off idle throttles the sensor
        // stream, so foreground + screen-on is now the primary keep-alive. Scoped to an active
        // session (single-sensor OR multi-sensor) and inherently foreground-only; cleared on stop
        // and on leaving composition.
        val keepScreenOn = active != null || monitoringAll
        DisposableEffect(keepScreenOn) {
            if (keepScreenOn)
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            else
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            onDispose { window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        }

        // Hardware / swipe back: pop sub-screen → settings hub → gauge; end an active session.
        androidx.activity.compose.BackHandler(
            enabled = monitoringAll || active != null || secondaryScreen != null
        ) {
            when {
                monitoringAll || active != null -> sendTrackingCommand(SensorTrackingService.ACTION_STOP)
                secondaryScreen == AvatarScreen.Settings -> secondaryScreen = null
                secondaryScreen != null -> secondaryScreen = AvatarScreen.Settings
            }
        }

        // Multi-sensor "Monitor All" mode takes over the screen while it runs.
        if (monitoringAll) {
            MonitorAllScreenStyled(onStop = { sendTrackingCommand(SensorTrackingService.ACTION_STOP) })
            return
        }
        // Single-sensor session: live readings.
        val current = active
        if (current != null) {
            val title = autoRun?.let { "${current.label}  (${it.index + 1}/${it.total})" } ?: current.label
            val stopLabel = if (autoRun != null) "Cancel auto run" else "Stop"
            LiveReadingsScreen(title, readings, stopLabel,
                onStop = { sendTrackingCommand(SensorTrackingService.ACTION_STOP) })
            return
        }
        // Idle: gauge, or a secondary screen reached from the settings hub.
        when (secondaryScreen) {
            null -> HomeGauge()
            AvatarScreen.Settings -> SettingsMenuScreen(
                onHome = { secondaryScreen = null },
                onGo = { secondaryScreen = it },
                onMonitorAll = { sendTrackingCommand(SensorTrackingService.ACTION_MONITOR_ALL) },
                onAutoRun = { sendTrackingCommand(SensorTrackingService.ACTION_AUTO_RUN) },
            )
            AvatarScreen.Toggles -> SensorToggleScreen(
                onToggle = { key, on ->
                    val intent = Intent(this, SensorTrackingService::class.java)
                        .setAction(SensorTrackingService.ACTION_SET_TRACKER)
                        .putExtra(SensorTrackingService.EXTRA_TRACKER_LABEL, key)
                        .putExtra(SensorTrackingService.EXTRA_TRACKER_ON, on)
                    startForegroundService(intent)
                },
                onBack = { secondaryScreen = AvatarScreen.Settings },
            )
            AvatarScreen.Diagnostic -> DiagnosticScreen(onBack = { secondaryScreen = AvatarScreen.Settings })
            AvatarScreen.Streaming -> StreamingScreen(
                onToggle = { on ->
                    sendTrackingCommand(
                        if (on) SensorTrackingService.ACTION_STREAM_ON
                        else SensorTrackingService.ACTION_STREAM_OFF
                    )
                },
                onBack = { secondaryScreen = AvatarScreen.Settings },
            )
            AvatarScreen.SensorTests -> SensorTestsScreen(
                options = menu.toList(),
                onStart = { id -> sendTrackingCommand(SensorTrackingService.ACTION_START_SENSOR, id) },
                onBack = { secondaryScreen = AvatarScreen.Settings },
            )
            AvatarScreen.Logs -> LogsScreen(onBack = { secondaryScreen = AvatarScreen.Settings })
        }
    }

    /**
     * AVATAR landing screen: the stress gauge, streamed by the Activity's own HR/EDA
     * trackers. Reads that state only — no changes to the service's session engine.
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
        // Whole face is tappable to open the settings hub (no on-screen label needed).
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable { secondaryScreen = AvatarScreen.Settings }
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

}
