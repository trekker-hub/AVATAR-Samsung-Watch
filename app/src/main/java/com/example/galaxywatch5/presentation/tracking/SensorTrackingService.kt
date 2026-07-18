package com.example.galaxywatch5.presentation.tracking

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.health.services.client.ExerciseClient
import androidx.health.services.client.ExerciseUpdateCallback
import androidx.health.services.client.HealthServices
import androidx.health.services.client.data.Availability
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.ExerciseConfig
import androidx.health.services.client.data.ExerciseLapSummary
import androidx.health.services.client.data.ExerciseType
import androidx.health.services.client.data.ExerciseUpdate
import com.example.galaxywatch5.R
import com.example.galaxywatch5.presentation.MainActivity
import com.example.galaxywatch5.presentation.logging.DataLogger
import com.google.android.gms.wearable.Wearable
import com.samsung.android.service.health.tracking.ConnectionListener
import com.samsung.android.service.health.tracking.HealthTracker
import com.samsung.android.service.health.tracking.HealthTrackerException
import com.samsung.android.service.health.tracking.HealthTrackingService
import com.samsung.android.service.health.tracking.data.DataPoint
import com.samsung.android.service.health.tracking.data.HealthTrackerType
import com.samsung.android.service.health.tracking.data.PpgType
import com.samsung.android.service.health.tracking.data.ValueKey
import java.io.File

/**
 * Foreground service that owns the sensor-session engine (Samsung Health SDK connection,
 * the active tracker, JSONL logging, auto-run sequencing). Sessions keep streaming with
 * the screen off / app backgrounded until the user explicitly stops them.
 *
 * Owns its own HealthTrackingService connection, independent of MainActivity's
 * (which the gauge and the menu capability query still use).
 *
 * State flows out through [TrackingRepository]; commands come in as start-intents.
 */
class SensorTrackingService : Service() {

    companion object {
        const val ACTION_START_SENSOR = "com.example.galaxywatch5.action.START_SENSOR"
        const val ACTION_AUTO_RUN = "com.example.galaxywatch5.action.AUTO_RUN"
        const val ACTION_MONITOR_ALL = "com.example.galaxywatch5.action.MONITOR_ALL"
        const val ACTION_STOP = "com.example.galaxywatch5.action.STOP"
        const val EXTRA_SENSOR_ID = "sensor_id"

        private const val CHANNEL_ID = "tracking"
        private const val NOTIF_ID = 1
        private const val TAG = "SensorTrackingSvc"

        // ---- Multi-sensor diagnostics / tuning ----
        // Delay between each tracker's start. EDA is fussiest to initialize, so it starts first
        // (index 0, no delay) and the rest follow this stagger. Tune here.
        private const val STAGGER_MS = 500L

        // Which sensors "Monitor All" starts. Flip this + rebuild to isolate a combination bug:
        // EDA is always first. (b) proves software-vs-hardware since accel shares nothing with EDA.
        private val DIAG_COMBO = DiagCombo.FULL

        // Optional low-latency accelerometer: force the batched buffer out on a short timer instead
        // of waiting for the SDK to flush it (~10 s batches). OFF by default — batched delivery is
        // fine for post-hoc artifact rejection and flushing costs battery.
        private const val ACCEL_FLUSH_ENABLED = false
        private const val ACCEL_FLUSH_MS = 1500L
    }

    /** Ordered sensor sets for the multi-sensor diagnostic. EDA is always first. */
    private enum class DiagCombo(val sensors: List<Pair<String, HealthTrackerType>>) {
        EDA_ONLY(listOf("EDA" to HealthTrackerType.EDA_CONTINUOUS)),
        EDA_ACCEL(listOf(
            "EDA" to HealthTrackerType.EDA_CONTINUOUS,
            "Accelerometer" to HealthTrackerType.ACCELEROMETER_CONTINUOUS)),
        EDA_HR(listOf(
            "EDA" to HealthTrackerType.EDA_CONTINUOUS,
            "Heart Rate" to HealthTrackerType.HEART_RATE_CONTINUOUS)),
        FULL(listOf(
            "EDA" to HealthTrackerType.EDA_CONTINUOUS,
            "Heart Rate" to HealthTrackerType.HEART_RATE_CONTINUOUS,
            "Accelerometer" to HealthTrackerType.ACCELEROMETER_CONTINUOUS)),
    }

    /** One live multi-sensor tracker plus the dedicated thread its callbacks are delivered on. */
    private class ActiveTracker(
        val label: String,
        val thread: HandlerThread,
        val tracker: HealthTracker,
    )

    // ---------- Engine state (moved from MainActivity) ----------

    private val mainHandler = Handler(Looper.getMainLooper())
    private var healthService: HealthTrackingService? = null
    private var connected = false
    private var pendingAction: (() -> Unit)? = null

    private var tracker: HealthTracker? = null
    private var active: SensorOption? = null
        set(value) { field = value; TrackingRepository.setActive(value) }
    private val readings = LinkedHashMap<String, String>()
    private var measureStart = 0L   // SystemClock.elapsedRealtime() when tracking began
    private var contactMade = false  // true once skin/finger contact is detected
    private var packetCount = 0      // how many onDataReceived calls have fired
    private var autoRunQueue: List<SensorOption> = emptyList()
    private var autoRunIndex = -1    // -1 = not in auto-run
    private var dataLogger: DataLogger? = null

    // ---------- Screen-off keep-alive (Samsung suspends plain FGS during idle) ----------

    private var wakeLock: PowerManager.WakeLock? = null
    private var exerciseClient: ExerciseClient? = null
    private var exerciseStartRequested = false

    // ---------- Multi-sensor "Monitor All" mode (independent of the single-tracker engine) ----------
    // Each tracker gets its OWN HandlerThread so its callbacks are delivered on a dedicated thread,
    // per Samsung's sample pattern — no shared handler across trackers.
    private val activeTrackers = mutableListOf<ActiveTracker>()
    private var multiActive = false

    /** Push a snapshot of [readings] to the UI. Call after every mutation batch. */
    private fun publish() = TrackingRepository.publishReadings(LinkedHashMap(readings))

    private fun syncAutoRun() = TrackingRepository.setAutoRun(
        if (autoRunIndex >= 0) AutoRunProgress(autoRunIndex, autoRunQueue.size) else null
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
                opt.type in SensorCatalog.contactRequiredTypes && !contactMade ->
                    "👆 waiting for contact…"
                opt.onDemand && opt.expectedSeconds != null ->
                    "⏳ measuring · ${secs}s / ~${opt.expectedSeconds}s"
                opt.onDemand -> "⏳ measuring · ${secs}s"
                else -> "🟢 live · ${secs}s"
            }
            publish()
            mainHandler.postDelayed(this, 1000L)
        }
    }

    // ---------- Lifecycle ----------

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Must run before anything else — startForegroundService requires it within 5 s,
        // for every action including STOP.
        startInForeground(active?.label ?: "starting…")

        when (intent?.action) {
            ACTION_START_SENSOR -> {
                val option = SensorCatalog.byId(intent.getStringExtra(EXTRA_SENSOR_ID))
                if (option == null) {
                    TrackingRepository.postStatus("Unknown sensor requested")
                    stopSelf()
                } else {
                    acquireWakeLock()
                    startExerciseSession()
                    withConnection { startTracker(option) }
                }
            }
            ACTION_AUTO_RUN -> {
                acquireWakeLock()
                startExerciseSession()
                withConnection { startAutoRun() }
            }
            ACTION_MONITOR_ALL -> {
                acquireWakeLock()
                startExerciseSession()
                withConnection { startMultiMonitor() }
            }
            // Stop the multi-sensor mode if it's running, otherwise the single-tracker session.
            ACTION_STOP -> if (multiActive) stopMultiMonitor() else stopTracker(null)
            // Null intent = sticky restart after process death; the session state is
            // gone, so shut down cleanly rather than track nothing under a notification.
            else -> stopSelf()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            mainHandler.removeCallbacksAndMessages(null)
            teardownMultiTrackers()
            tracker?.let { runCatching { it.unsetEventListener() } }
            tracker = null
            // Ensure an interrupted session file still gets its session_end line.
            dataLogger?.let { runCatching { it.finish() } }
            dataLogger = null
            healthService?.let { runCatching { it.disconnectService() } }
            healthService = null
            connected = false
            TrackingRepository.clear()
        } finally {
            // Every stop path funnels through stopSelf() → here, so the exercise session
            // and wake lock can never outlive tracking. Lock released last so the CPU
            // stays up through teardown.
            endExerciseSession()
            releaseWakeLock()
        }
    }

    // ---------- Screen-off keep-alive ----------

    @SuppressLint("WakelockTimeout")  // held for the whole tracking session by design
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return  // repeat start-intent while already tracking
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "avatar:hr_tracking")
            .apply {
                setReferenceCounted(false)  // one release always suffices
                acquire()
            }
        Log.i(TAG, "Partial wake lock acquired")
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.takeIf { it.isHeld }?.release()
            if (wakeLock != null) Log.i(TAG, "Partial wake lock released")
        } catch (e: RuntimeException) {
            Log.w(TAG, "Wake lock release failed", e)
        } finally {
            wakeLock = null
        }
    }

    // Health Services delivers no data here — the Samsung SDK stays the source. The
    // exercise session only marks the sensor work as legitimate so Samsung's power
    // management doesn't suspend the service (and CPU) during screen-off idle.
    private val exerciseCallback = object : ExerciseUpdateCallback {
        override fun onRegistered() {}
        override fun onRegistrationFailed(throwable: Throwable) {
            Log.w(TAG, "WHS callback registration failed", throwable)
        }
        override fun onExerciseUpdateReceived(update: ExerciseUpdate) {
            Log.d(TAG, "WHS exercise state: ${update.exerciseStateInfo.state}")
        }
        override fun onLapSummaryReceived(lapSummary: ExerciseLapSummary) {}
        override fun onAvailabilityChanged(dataType: DataType<*, *>, availability: Availability) {}
    }

    private fun startExerciseSession() {
        if (exerciseStartRequested) return  // once per service tracking session
        exerciseStartRequested = true
        val client = HealthServices.getClient(this).exerciseClient
        exerciseClient = client
        client.setUpdateCallback(mainExecutor, exerciseCallback)
        val config = ExerciseConfig.builder(ExerciseType.WORKOUT)
            .setDataTypes(emptySet())               // no WHS data — Samsung SDK is the source
            .setIsAutoPauseAndResumeEnabled(false)  // never let WHS pause the hold
            .setIsGpsEnabled(false)
            .build()
        val future = client.startExerciseAsync(config)
        future.addListener({
            try {
                future.get()
                Log.i(TAG, "WHS exercise session started (screen-off hold active)")
            } catch (e: Exception) {
                // e.g. another app owns the active exercise; keep collecting on the
                // wake lock alone rather than failing the Samsung SDK session.
                Log.w(TAG, "WHS startExercise failed — continuing on wake lock only", e)
            }
        }, mainExecutor)
    }

    private fun endExerciseSession() {
        if (!exerciseStartRequested) return
        // Requested-flag (not confirmed-started) so a stop racing the start future
        // still issues the end call.
        exerciseStartRequested = false
        val client = exerciseClient ?: return
        exerciseClient = null
        val future = client.endExerciseAsync()
        future.addListener({
            try {
                future.get()
                Log.i(TAG, "WHS exercise session ended")
            } catch (e: Exception) {
                Log.w(TAG, "WHS endExercise: ${e.message}")  // "no active exercise" is fine
            }
            runCatching { client.clearUpdateCallbackAsync(exerciseCallback) }
        }, mainExecutor)
    }

    // ---------- Multi-sensor "Monitor All" mode ----------
    // Registers CONTINUOUS trackers at once — Accelerometer, Heart Rate (which also carries IBI),
    // and EDA — each with its own listener. Every tracker is availability-gated and fully isolated:
    // an unsupported type is skipped, and a failure/onError in one sensor is logged and contained,
    // never torn into the others. Every sample streams to logcat, the repo's per-sensor StateFlows
    // (UI), and a JSONL session file (persistent, time-alignable). Runs on the service's own SDK
    // connection.

    private fun startMultiMonitor() {
        // Defensively drop any single-tracker session so the two engines never overlap.
        mainHandler.removeCallbacks(onDemandTimeout)
        mainHandler.removeCallbacks(ticker)
        mainHandler.removeCallbacks(autoAdvanceRunnable)
        tracker?.let { runCatching { it.unsetEventListener() } }
        tracker = null
        active = null

        multiActive = true
        TrackingRepository.setMonitoringAll(true)
        updateNotification("Monitoring sensors")

        // One JSONL file for the whole multi-sensor session so every sample is retained with its
        // own timestamp (all three sensors interleaved → time-alignable). Written concurrently from
        // the per-tracker threads; DataLogger is synchronized.
        dataLogger?.let { runCatching { it.finish() } }
        dataLogger = DataLogger(this, autoRun = false)

        val supported = supportedTypes()
        val combo = DIAG_COMBO
        Log.i(TAG, "[multi] starting combo=$combo (stagger=${STAGGER_MS}ms) — " +
                "supported types: ${supported.joinToString()}")

        // Staggered, EDA-first start: register each tracker on its own HandlerThread, spaced by
        // STAGGER_MS, so EDA gets the first claim on resources and nothing races on a shared thread.
        combo.sensors.forEachIndexed { index, (label, type) ->
            mainHandler.postDelayed({ registerThreaded(label, type, supported) }, index * STAGGER_MS)
        }
    }

    /**
     * Availability-gate + permission-check one continuous tracker, then register it on its OWN
     * dedicated HandlerThread with its OWN distinct listener — Samsung's supported pattern for
     * running multiple continuous trackers at once. The callbacks are delivered on avatar-<label>.
     */
    private fun registerThreaded(
        label: String,
        type: HealthTrackerType,
        supported: List<HealthTrackerType>
    ) {
        if (!multiActive) return  // stopped during the stagger window
        if (type !in supported) {
            Log.w(TAG, "[multi] $label ($type) not supported on this device — skipping")
            return
        }
        // Log any missing runtime permission (types differ), but still attempt so the SDK's own
        // PERMISSION_ERROR surfaces via onError too.
        requiredPermissions(type)
            .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
            .forEach { Log.w(TAG, "[multi] $label missing permission: $it") }

        val svc = healthService ?: return
        val thread = HandlerThread("avatar-$label").apply { start() }
        val handler = Handler(thread.looper)
        try {
            val t = svc.getHealthTracker(type)
            val listener = makeListener(label, type)  // distinct instance per tracker
            handler.post {
                Log.i(TAG, "[multi] $label setEventListener on thread=${Thread.currentThread().name}")
                t.setEventListener(listener)
            }
            activeTrackers.add(ActiveTracker(label, thread, t))
            Log.i(TAG, "[multi] $label registered (own HandlerThread)")

            // Optional: force the accelerometer's batched buffer out on a short timer. Runs on the
            // tracker's own handler and stops when the thread is quit on teardown.
            if (ACCEL_FLUSH_ENABLED && type == HealthTrackerType.ACCELEROMETER_CONTINUOUS) {
                val flush = object : Runnable {
                    override fun run() {
                        runCatching { t.flush() }
                        handler.postDelayed(this, ACCEL_FLUSH_MS)
                    }
                }
                handler.postDelayed(flush, ACCEL_FLUSH_MS)
            }
        } catch (e: Exception) {
            Log.e(TAG, "[multi] $label failed to register: ${e.javaClass.simpleName}", e)
            thread.quitSafely()
        }
    }

    /** A fresh, distinct listener per tracker (never shared) — dispatches parsing by type. */
    private fun makeListener(label: String, type: HealthTrackerType) =
        object : HealthTracker.TrackerEventListener {
            private var first = true
            override fun onDataReceived(dataPoints: List<DataPoint>) {
                val recvTs = System.currentTimeMillis()
                if (dataPoints.isEmpty()) return
                if (first) {
                    first = false
                    Log.i(TAG, "[multi] $label FIRST onDataReceived thread=${Thread.currentThread().name} " +
                            "sdkTs=${dataPoints.last().timestamp} recvTs=$recvTs")
                }
                try {
                    when (type) {
                        HealthTrackerType.ACCELEROMETER_CONTINUOUS -> parseAccel(dataPoints, recvTs)
                        HealthTrackerType.HEART_RATE_CONTINUOUS -> parseHr(dataPoints, recvTs)
                        HealthTrackerType.EDA_CONTINUOUS -> parseEda(dataPoints, recvTs)
                        else -> {}
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "[multi] $label parse error: ${e.javaClass.simpleName}", e)
                }
            }
            override fun onFlushCompleted() {}
            override fun onError(e: HealthTracker.TrackerError) {
                // Full capture: this is where a swallowed EDA failure becomes visible. Isolated —
                // one tracker's error never tears down the others.
                Log.e(TAG, "[ERROR] $label onError = $e")
            }
        }

    // ---- Per-sensor parsing (status-first: read status, guard each value, always log) ----

    // Accelerometer has no STATUS field in this SDK — only X/Y/Z. The SDK batches many samples per
    // callback (~300 every ~12 s = 25 Hz when the screen dims), so iterate the WHOLE list and store
    // every sample with its own timestamp — reading only the last would drop ~299 of ~300 per batch.
    private fun parseAccel(dps: List<DataPoint>, recvTs: Long) {
        val n = dps.size
        val midIdx = n / 2
        var stored = 0
        var allZero = true
        var allIdentical = true
        // First stored sample, used as the reference for the all-identical check.
        var refX: Int? = null; var refY: Int? = null; var refZ: Int? = null
        var lastX: Int? = null; var lastY: Int? = null; var lastZ: Int? = null
        val probes = StringBuilder()  // X/Y/Z at first / middle / last positions, to see values vary

        for ((i, dp) in dps.withIndex()) {
            val x = runCatching { dp.getValue(ValueKey.AccelerometerSet.ACCELEROMETER_X) }.getOrNull()
            val y = runCatching { dp.getValue(ValueKey.AccelerometerSet.ACCELEROMETER_Y) }.getOrNull()
            val z = runCatching { dp.getValue(ValueKey.AccelerometerSet.ACCELEROMETER_Z) }.getOrNull()
            if (x != null && y != null && z != null) {
                dataLogger?.log("Accelerometer", mapOf(
                    "x" to x, "y" to y, "z" to z, "sdkTs" to dp.timestamp, "recvTs" to recvTs))
                stored++
                if (x != 0 || y != 0 || z != 0) allZero = false
                if (refX == null) { refX = x; refY = y; refZ = z }
                else if (x != refX || y != refY || z != refZ) allIdentical = false
                lastX = x; lastY = y; lastZ = z
            }
            if (i == 0 || i == midIdx || i == n - 1) {
                probes.append(" [$i]=($x,$y,$z)@${dp.timestamp}")
            }
        }

        // One summary line per callback (not ~300) — every sample is still persisted above.
        val firstTs = dps.first().timestamp
        val lastTs = dps.last().timestamp
        val spanMs = lastTs - firstTs
        val avgMs = if (n > 1) spanMs.toDouble() / (n - 1) else 0.0
        Log.d(TAG, "[multi] Accelerometer n=$n stored=$stored " +
                "spanS=${"%.1f".format(spanMs / 1000.0)} avgMs=${"%.1f".format(avgMs)} " +
                "first=$firstTs last=$lastTs probes:$probes recvTs=$recvTs")

        // Distinguish a real read fault from healthy batching.
        if (stored > 1 && allZero)
            Log.w(TAG, "[multi] Accelerometer WARNING batch all-zero (n=$n) — possible read problem, not batching")
        if (stored > 1 && allIdentical)
            Log.w(TAG, "[multi] Accelerometer WARNING batch all-identical ($refX,$refY,$refZ) — possible stuck read")

        if (lastX != null && lastY != null && lastZ != null)
            TrackingRepository.setAccel(AccelSample(lastX, lastY, lastZ, n, lastTs, recvTs))
    }

    // Heart rate + IBI come from the SAME DataPoint — no separate IBI tracker exists.
    private fun parseHr(dps: List<DataPoint>, recvTs: Long) {
        val dp = dps.last()
        val status = runCatching { dp.getValue(ValueKey.HeartRateSet.HEART_RATE_STATUS) }.getOrNull()
        val hr = runCatching { dp.getValue(ValueKey.HeartRateSet.HEART_RATE) }.getOrNull()
        val ibi = runCatching { dp.getValue(ValueKey.HeartRateSet.IBI_LIST) }.getOrNull() ?: emptyList()
        val ibiStatus = runCatching { dp.getValue(ValueKey.HeartRateSet.IBI_STATUS_LIST) }.getOrNull() ?: emptyList()
        val reading = if (hr != null) "hr=$hr ibiLen=${ibi.size} ibi=$ibi" else "no valid reading"
        Log.d(TAG, "[multi] Heart Rate n=${dps.size} ${decodeStatus("HeartRate", status)} $reading " +
                "sdkTs=${dp.timestamp} recvTs=$recvTs")
        dataLogger?.log("HeartRate", mapOf(
            "hr" to hr, "status" to status, "ibi" to ibi.toString(),
            "ibiStatus" to ibiStatus.toString(), "sdkTs" to dp.timestamp, "recvTs" to recvTs))
        if (hr != null && status != null)
            TrackingRepository.setHeartRate(HrSample(hr, status, ibi, ibiStatus, dp.timestamp, recvTs))
    }

    private fun parseEda(dps: List<DataPoint>, recvTs: Long) {
        val dp = dps.last()
        val status = runCatching { dp.getValue(ValueKey.EdaSet.STATUS) }.getOrNull()
        val sc = runCatching { dp.getValue(ValueKey.EdaSet.SKIN_CONDUCTANCE) }.getOrNull()
        val reading = if (sc != null) "skinConductance=$sc µS" else "no valid reading"
        Log.d(TAG, "[multi] EDA n=${dps.size} ${decodeStatus("EDA", status)} $reading " +
                "sdkTs=${dp.timestamp} recvTs=$recvTs")
        dataLogger?.log("EDA", mapOf(
            "skinConductance" to sc, "status" to status, "sdkTs" to dp.timestamp, "recvTs" to recvTs))
        if (sc != null && status != null)
            TrackingRepository.setEda(EdaSample(sc, status, dp.timestamp, recvTs))
    }

    /** Runtime permissions the SDK needs per tracker type (differ by type). */
    private fun requiredPermissions(type: HealthTrackerType): List<String> = when (type) {
        HealthTrackerType.HEART_RATE_CONTINUOUS,
        HealthTrackerType.EDA_CONTINUOUS -> listOf(Manifest.permission.BODY_SENSORS)
        HealthTrackerType.ACCELEROMETER_CONTINUOUS -> listOf(Manifest.permission.ACTIVITY_RECOGNITION)
        else -> emptyList()
    }

    /**
     * Render a status int for logging: always keeps the raw number, and adds a plain-English
     * label ONLY for codes Samsung officially documents. EDA codes are never invented — they log
     * raw plus a pointer to the API reference to be filled in later.
     */
    private fun decodeStatus(sensor: String, status: Int?): String {
        if (status == null) return "st=<unreadable>"
        val label = when (sensor) {
            "HeartRate" -> when (status) {
                -3 -> "NOT WORN"            // Samsung-documented
                1  -> "OK / valid reading"  // empirically observed here; only -3 is Samsung-documented
                else -> null
            }
            // ECG uses LEAD_OFF, not STATUS: 0 = electrodes in contact, 5 = not in contact (both
            // documented). Kept ready for a future ECG handler — nothing calls this branch yet.
            "ECG" -> when (status) {
                0 -> "electrodes in contact"
                5 -> "not in contact"
                else -> null
            }
            else -> null  // EDA: never invent meanings
        }
        if (label != null) return "st=$status ($label)"
        val note = when (sensor) {
            "EDA" -> " (negative = no valid reading; exact codes per Samsung ValueKey.EdaSet reference)"
            else  -> ""
        }
        return "st=$status$note"
    }

    private fun stopMultiMonitor() {
        teardownMultiTrackers()
        stopSelf()
    }

    /**
     * Unset every multi-sensor listener and quit each dedicated HandlerThread. Idempotent — safe
     * from stop and onDestroy. Sets multiActive=false FIRST so any pending staggered start no-ops.
     */
    private fun teardownMultiTrackers() {
        multiActive = false
        // Null the logger first so any in-flight parse on a tracker thread stops writing, then
        // finish it after the threads are quit (DataLogger is synchronized + closed-guarded, so a
        // late write can't corrupt or throw).
        val logger = dataLogger
        if (activeTrackers.isNotEmpty()) {
            dataLogger = null
            activeTrackers.forEach { at ->
                runCatching { at.tracker.unsetEventListener() }
                at.thread.quitSafely()
            }
            activeTrackers.clear()
            runCatching { logger?.finish() }
            Log.i(TAG, "[multi] all trackers stopped + threads quit + log saved")
        }
        TrackingRepository.setMonitoringAll(false)
        TrackingRepository.clearMultiSamples()
    }

    // ---------- Foreground notification ----------

    private fun startInForeground(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Sensor tracking", NotificationManager.IMPORTANCE_LOW)
        )
        val notif = buildNotification(text)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH)
        } else {
            // FOREGROUND_SERVICE_TYPE_HEALTH doesn't exist pre-API 34; the manifest
            // foregroundServiceType attribute is ignored there too.
            startForeground(NOTIF_ID, notif)
        }
    }

    private fun buildNotification(text: String): Notification {
        val tapIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("AVATAR tracking")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(tapIntent)
            .build()
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(text))
    }

    // ---------- Connection (service-owned, independent of the Activity's) ----------

    private fun withConnection(block: () -> Unit) {
        if (connected) { block(); return }
        pendingAction = block
        if (healthService != null) return  // connect already in flight
        healthService = HealthTrackingService(object : ConnectionListener {
            override fun onConnectionSuccess() {
                mainHandler.post {
                    connected = true
                    pendingAction?.invoke()
                    pendingAction = null
                }
            }
            override fun onConnectionEnded() {
                mainHandler.post { connected = false }
            }
            override fun onConnectionFailed(e: HealthTrackerException) {
                mainHandler.post {
                    connected = false
                    pendingAction = null
                    TrackingRepository.postStatus("Tracking failed: SDK err ${e.errorCode}")
                    stopSelf()
                }
            }
        }, this)
        healthService?.connectService()
    }

    // ---------- Start / stop (moved from MainActivity, logic unchanged) ----------

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
        publish()
        updateNotification(option.label)
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
                    // Receipt time captured on the SDK binder thread, before the Handler
                    // hop, so queuing latency doesn't pollute the continuity diagnostic.
                    val recvTs = System.currentTimeMillis()
                    dataPoints.lastOrNull()?.let { dp ->
                        Log.d(
                            TAG, "onDataReceived[${option.label}] n=${dataPoints.size} " +
                                "sdkTs=${dp.timestamp} recvTs=$recvTs lagMs=${recvTs - dp.timestamp}"
                        )
                    }
                    mainHandler.post {
                        packetCount++
                        readings["[dbg] packets"] = "#$packetCount  list.size=${dataPoints.size}"
                        val dp = dataPoints.lastOrNull() ?: run { publish(); return@post }
                        format(option, dp)
                        // Log full readings snapshot including all debug fields
                        dataLogger?.log(option.label, readings.toMap())
                        publish()
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
                    mainHandler.post {
                        readings["[dbg] onError"] = msg
                        publish()
                        dataLogger?.event("sdk_error", option.label, msg)
                    }
                }
            })

            mainHandler.post(ticker)
            // Contact-required sensors delay their timeout until finger/skin contact is detected
            // (handled in format() when the first valid reading arrives)
            if (option.onDemand && option.type !in SensorCatalog.contactRequiredTypes) {
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
            publish()
            if (autoRunIndex >= 0) {
                autoRunIndex++
                syncAutoRun()
                if (autoRunIndex < autoRunQueue.size) {
                    mainHandler.postDelayed({ launchAutoStep() }, 1500L)
                } else {
                    finishAutoRun()
                }
            }
        } else {
            // Explicit stop — save partial session file in background, then shut down.
            autoRunIndex = -1
            autoRunQueue = emptyList()
            syncAutoRun()
            val logger = dataLogger
            dataLogger = null
            active = null
            if (logger != null) {
                Thread {
                    val file = logger.finish()
                    mainHandler.post {
                        TrackingRepository.postStatus("Session saved: ${file.name}")
                        stopSelf()
                    }
                }.start()
            } else {
                stopSelf()
            }
        }
    }

    // ---------- Auto-run ----------

    private fun startAutoRun() {
        val excluded = setOf(HealthTrackerType.BIA_ON_DEMAND, HealthTrackerType.MF_BIA_ON_DEMAND)
        autoRunQueue = SensorCatalog.candidates
            .filter { it.type !in excluded }
            .filter { it.type in supportedTypes() }
        if (autoRunQueue.isEmpty()) { stopSelf(); return }
        autoRunIndex = 0
        syncAutoRun()
        dataLogger = DataLogger(this, autoRun = true)
        launchAutoStep()
    }

    private fun supportedTypes(): List<HealthTrackerType> =
        healthService?.trackingCapability?.supportHealthTrackerTypes ?: emptyList()

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
        syncAutoRun()
        val logger = dataLogger
        dataLogger = null
        readings[" status"] = "✅ Auto run complete — saving…"
        publish()
        if (logger != null) {
            val file = logger.finish()   // fast: just writes session_end line
            sendSessionToPhone(file)     // updates readings[" status"] and eventually shuts down
        } else {
            endSessionAfter(2000L)
        }
    }

    /** Clear the active session and stop the service (removes the notification). */
    private fun endSessionAfter(delayMs: Long) {
        mainHandler.postDelayed({ active = null; stopSelf() }, delayMs)
    }

    private fun sendSessionToPhone(file: File) {
        if (!file.exists()) { active = null; stopSelf(); return }
        Wearable.getNodeClient(this).connectedNodes
            .addOnSuccessListener { nodes ->
                val node = nodes.firstOrNull()
                if (node == null) {
                    readings[" status"] = "No phone connected — file on watch:\n${file.name}"
                    publish()
                    endSessionAfter(5000L)
                    return@addOnSuccessListener
                }
                readings[" status"] = "Sending to ${node.displayName}…"
                publish()
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
                                        mainHandler.post {
                                            readings[" status"] = "✅ Sent to ${node.displayName}"
                                            publish()
                                            endSessionAfter(3000L)
                                        }
                                    } catch (e: Exception) {
                                        mainHandler.post {
                                            readings[" status"] =
                                                "⚠ Send error — file kept on watch:\n${file.name}"
                                            publish()
                                            endSessionAfter(5000L)
                                        }
                                    }
                                }.start()
                            }
                            .addOnFailureListener {
                                readings[" status"] = "⚠ Stream error — file kept:\n${file.name}"
                                publish()
                                endSessionAfter(5000L)
                            }
                    }
                    .addOnFailureListener {
                        readings[" status"] = "⚠ Channel error — file kept:\n${file.name}"
                        publish()
                        endSessionAfter(5000L)
                    }
            }
            .addOnFailureListener {
                readings[" status"] = "⚠ Node lookup failed — file kept:\n${file.name}"
                publish()
                endSessionAfter(5000L)
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
}
