package com.example.galaxywatch5.presentation.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.flow.StateFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.CompactChip
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.ListHeader
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.ToggleChip
import androidx.wear.compose.material.ToggleChipDefaults
import com.example.galaxywatch5.presentation.theme.AvatarTokens
import com.example.galaxywatch5.presentation.theme.AvatarWearScaffold
import com.example.galaxywatch5.presentation.tracking.SensorOption
import com.example.galaxywatch5.presentation.tracking.TrackingRepository

/** Destinations reachable from the settings hub (top-level secondary navigation). */
enum class AvatarScreen { Settings, Toggles, Diagnostic, Streaming, SensorTests, Logs }

/** Human sensor names, keyed by the tracker labels the service/repository already use. */
data class AvatarSensor(val key: String, val displayName: String)

val AVATAR_SENSORS = listOf(
    AvatarSensor("Heart Rate", "Heart rate"),
    AvatarSensor("EDA", "Skin conductance"),
    AvatarSensor("Accelerometer", "Accelerometer"),
    AvatarSensor("PPG", "PPG"),
    AvatarSensor("Skin Temp", "Skin temperature"),
)

/** A sensor is "delivering" if its newest sample arrived within this window. */
private const val SENSOR_STALE_MS = 4_000L

// -------------------------------------------------------------------------------------
// Small shared pieces
// -------------------------------------------------------------------------------------

@Composable
private fun Header(text: String) {
    ListHeader { Text(text, style = AvatarTokens.Title, textAlign = TextAlign.Center) }
}

@Composable
private fun StatusDot(color: Color) {
    Box(Modifier.size(AvatarTokens.DotSize).clip(CircleShape).background(color))
}

@Composable
private fun BackChip(onBack: () -> Unit) {
    CompactChip(
        label = { Text("Back", style = AvatarTokens.ChipLabel) },
        onClick = onBack,
        colors = AvatarTokens.chipColors(active = false),
        modifier = Modifier.fillMaxWidth(),
    )
}

/** Latest receipt time + running count per sensor key, read from the repository StateFlows. */
private data class SensorLive(val recvTs: Long, val count: Long)

@Composable
private fun collectSensorLive(): Map<String, SensorLive> {
    val accel by TrackingRepository.accel.collectAsStateValue()
    val hr by TrackingRepository.heartRate.collectAsStateValue()
    val eda by TrackingRepository.eda.collectAsStateValue()
    val ppg by TrackingRepository.ppg.collectAsStateValue()
    val skin by TrackingRepository.skinTemp.collectAsStateValue()
    return mapOf(
        "Heart Rate" to SensorLive(hr?.recvTs ?: 0L, 1L),
        "EDA" to SensorLive(eda?.recvTs ?: 0L, 1L),
        "Accelerometer" to SensorLive(accel?.recvTs ?: 0L, (accel?.n ?: 0).toLong()),
        "PPG" to SensorLive(ppg?.recvTs ?: 0L, (ppg?.n ?: 0).toLong()),
        "Skin Temp" to SensorLive(skin?.recvTs ?: 0L, (skin?.n ?: 0).toLong()),
    )
}

/** Local alias so this file reads cleanly; delegates to Compose's collectAsState. */
@Composable
private fun <T> StateFlow<T>.collectAsStateValue() = collectAsState()

// -------------------------------------------------------------------------------------
// a. Settings hub
// -------------------------------------------------------------------------------------

@Composable
fun SettingsMenuScreen(
    onHome: () -> Unit,
    onGo: (AvatarScreen) -> Unit,
    onMonitorAll: () -> Unit,
    onAutoRun: () -> Unit,
) {
    val state = rememberScalingLazyListState()
    AvatarWearScaffold(state) {
        ScalingLazyColumn(
            state = state,
            contentPadding = AvatarTokens.ListContentPadding,
            modifier = Modifier.fillMaxWidth(),
        ) {
            item { Header("Settings") }
            item { MenuChip("Home gauge", onHome) }
            item { MenuChip("Sensor diagnostic") { onGo(AvatarScreen.Diagnostic) } }
            item { MenuChip("Sensor toggles") { onGo(AvatarScreen.Toggles) } }
            item { MenuChip("Streaming") { onGo(AvatarScreen.Streaming) } }
            item { MenuChip("Monitor all signals", onMonitorAll) }
            item { MenuChip("Sensor tests") { onGo(AvatarScreen.SensorTests) } }
            item { MenuChip("Auto run all", onAutoRun) }
            item { MenuChip("Logs & sessions") { onGo(AvatarScreen.Logs) } }
        }
    }
}

@Composable
private fun MenuChip(label: String, onClick: () -> Unit) {
    Chip(
        label = { Text(label, style = AvatarTokens.ChipLabel) },
        onClick = onClick,
        colors = AvatarTokens.chipColors(),
        modifier = Modifier.fillMaxWidth(),
    )
}

// -------------------------------------------------------------------------------------
// b. Sensor toggles
// -------------------------------------------------------------------------------------

@Composable
fun SensorToggleScreen(onToggle: (key: String, on: Boolean) -> Unit, onBack: () -> Unit) {
    val enabled by TrackingRepository.trackerEnabled.collectAsStateValue()
    val live = collectSensorLive()
    val now = remember { System.currentTimeMillis() }
    val state = rememberScalingLazyListState()
    AvatarWearScaffold(state) {
        ScalingLazyColumn(
            state = state,
            contentPadding = AvatarTokens.ListContentPadding,
            modifier = Modifier.fillMaxWidth(),
        ) {
            item { Header("Sensor toggles") }
            items(AVATAR_SENSORS) { sensor ->
                val on = enabled[sensor.key] == true
                val delivering = (live[sensor.key]?.recvTs ?: 0L) > now - SENSOR_STALE_MS
                val dot = when {
                    on && delivering -> AvatarTokens.StatusActive
                    on -> AvatarTokens.StatusIdle
                    else -> AvatarTokens.StatusIdle
                }
                ToggleChip(
                    checked = on,
                    onCheckedChange = { onToggle(sensor.key, it) },
                    label = { Text(sensor.displayName, style = AvatarTokens.ChipLabel) },
                    secondaryLabel = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            StatusDot(dot)
                            Spacer(Modifier.width(6.dp))
                            Text(
                                if (!on) "Off" else if (delivering) "Streaming" else "Idle",
                                style = AvatarTokens.Label,
                            )
                        }
                    },
                    toggleControl = {
                        Icon(
                            imageVector = ToggleChipDefaults.switchIcon(on),
                            contentDescription = if (on) "On" else "Off",
                        )
                    },
                    colors = AvatarTokens.toggleChipColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item { BackChip(onBack) }
        }
    }
}

// -------------------------------------------------------------------------------------
// c. Diagnostic / probe (read-only over existing flows)
// -------------------------------------------------------------------------------------

private const val PROBE_WINDOW_MS = 4_000L

private data class ProbeResult(val delivered: Boolean, val ratePerSec: Double)

@Composable
fun DiagnosticScreen(onBack: () -> Unit) {
    val live = collectSensorLive()
    var probing by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<Map<String, ProbeResult>>(emptyMap()) }

    // Run probe: accumulate each sensor's sample count over the window from the flows already
    // delivering, then derive rate. Read-only — no tracker/service calls.
    androidx.compose.runtime.LaunchedEffect(probing) {
        if (!probing) return@LaunchedEffect
        val startCounts = AVATAR_SENSORS.associate { it.key to (live[it.key]?.count ?: 0L) }
        val startTs = AVATAR_SENSORS.associate { it.key to (live[it.key]?.recvTs ?: 0L) }
        val delivered = AVATAR_SENSORS.associate { it.key to false }.toMutableMap()
        val samples = AVATAR_SENSORS.associate { it.key to 0L }.toMutableMap()
        val elapsed = PROBE_WINDOW_MS
        val step = 200L
        var waited = 0L
        while (waited < elapsed) {
            kotlinx.coroutines.delay(step)
            waited += step
            val snap = currentSensorLive()
            for (s in AVATAR_SENSORS) {
                val cur = snap[s.key] ?: continue
                if (cur.recvTs != startTs[s.key]) delivered[s.key] = true
            }
        }
        val end = currentSensorLive()
        results = AVATAR_SENSORS.associate { s ->
            val got = (end[s.key]?.count ?: 0L) - (startCounts[s.key] ?: 0L)
            val rate = if (got > 0) got.toDouble() / (elapsed / 1000.0) else 0.0
            s.key to ProbeResult(delivered[s.key] == true || got > 0, rate)
        }
        probing = false
    }

    val state = rememberScalingLazyListState()
    AvatarWearScaffold(state) {
        ScalingLazyColumn(
            state = state,
            contentPadding = AvatarTokens.ListContentPadding,
            modifier = Modifier.fillMaxWidth(),
        ) {
            item { Header("Sensor diagnostic") }
            item {
                Chip(
                    label = { Text(if (probing) "Probing…" else "Run probe", style = AvatarTokens.ChipLabel) },
                    onClick = { if (!probing) { results = emptyMap(); probing = true } },
                    colors = AvatarTokens.primaryChipColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            items(AVATAR_SENSORS) { sensor ->
                val r = results[sensor.key]
                val stateColor = when {
                    r == null -> AvatarTokens.StatusIdle
                    r.delivered -> AvatarTokens.StatusActive
                    else -> AvatarTokens.StatusError
                }
                val stateText = when {
                    probing -> "…"
                    r == null -> "Not run"
                    r.delivered -> "Pass · ${formatRate(r.ratePerSec)}"
                    else -> "Fail"
                }
                Chip(
                    label = { Text(sensor.displayName, style = AvatarTokens.ChipLabel) },
                    secondaryLabel = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            StatusDot(stateColor)
                            Spacer(Modifier.width(6.dp))
                            Text(stateText, style = AvatarTokens.Label)
                        }
                    },
                    onClick = { },
                    colors = AvatarTokens.chipColors(active = false),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item { BackChip(onBack) }
        }
    }
}

private fun formatRate(r: Double): String =
    java.lang.String.format(java.util.Locale.US, "%.1f /s", r)

/** Non-composable snapshot of the repository sample flows for use inside coroutines. */
private fun currentSensorLive(): Map<String, SensorLive> {
    val accel = TrackingRepository.accel.value
    val hr = TrackingRepository.heartRate.value
    val eda = TrackingRepository.eda.value
    val ppg = TrackingRepository.ppg.value
    val skin = TrackingRepository.skinTemp.value
    return mapOf(
        "Heart Rate" to SensorLive(hr?.recvTs ?: 0L, 1L),
        "EDA" to SensorLive(eda?.recvTs ?: 0L, 1L),
        "Accelerometer" to SensorLive(accel?.recvTs ?: 0L, (accel?.n ?: 0).toLong()),
        "PPG" to SensorLive(ppg?.recvTs ?: 0L, (ppg?.n ?: 0).toLong()),
        "Skin Temp" to SensorLive(skin?.recvTs ?: 0L, (skin?.n ?: 0).toLong()),
    )
}

// -------------------------------------------------------------------------------------
// d. Streaming
// -------------------------------------------------------------------------------------

@Composable
fun StreamingScreen(onToggle: (on: Boolean) -> Unit, onBack: () -> Unit) {
    val enabled by TrackingRepository.streamEnabled.collectAsStateValue()
    val live = collectSensorLive()
    val now = remember { System.currentTimeMillis() }
    // "Connected" is inferred from the stream being enabled AND fresh samples flowing (they are what
    // gets sent); reconnecting = enabled but nothing arriving yet.
    val fresh = live.values.any { it.recvTs > now - SENSOR_STALE_MS }
    val stateText = when {
        !enabled -> "Off"
        fresh -> "Connected"
        else -> "Reconnecting"
    }
    val stateColor = when {
        !enabled -> AvatarTokens.StatusIdle
        fresh -> AvatarTokens.StatusActive
        else -> AvatarTokens.StatusWarning
    }
    val state = rememberScalingLazyListState()
    AvatarWearScaffold(state) {
        ScalingLazyColumn(
            state = state,
            contentPadding = AvatarTokens.ListContentPadding,
            modifier = Modifier.fillMaxWidth(),
        ) {
            item { Header("Streaming") }
            item {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                ) {
                    StatusDot(stateColor)
                    Spacer(Modifier.width(8.dp))
                    Text(stateText, style = AvatarTokens.Title)
                }
            }
            item {
                ToggleChip(
                    checked = enabled,
                    onCheckedChange = { onToggle(it) },
                    label = { Text("Stream to PC", style = AvatarTokens.ChipLabel) },
                    secondaryLabel = { Text(if (enabled) "Sending" else "Paused", style = AvatarTokens.Label) },
                    toggleControl = {
                        Icon(
                            imageVector = ToggleChipDefaults.switchIcon(enabled),
                            contentDescription = if (enabled) "On" else "Off",
                        )
                    },
                    colors = AvatarTokens.toggleChipColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item { BackChip(onBack) }
        }
    }
}

// -------------------------------------------------------------------------------------
// e. Monitor All — restyled live readout (toggles + streaming now live on their own screens)
// -------------------------------------------------------------------------------------

@Composable
fun MonitorAllScreenStyled(onStop: () -> Unit) {
    val accel by TrackingRepository.accel.collectAsStateValue()
    val hr by TrackingRepository.heartRate.collectAsStateValue()
    val eda by TrackingRepository.eda.collectAsStateValue()
    val ppg by TrackingRepository.ppg.collectAsStateValue()
    val skin by TrackingRepository.skinTemp.collectAsStateValue()

    val rows = listOf(
        "Heart rate" to hr?.let { "${it.hr} bpm" },
        "IBI" to hr?.let { if (it.ibi.isEmpty()) "—" else it.ibi.joinToString() },
        "Skin conductance" to eda?.let { "${it.skinConductance} µS" },
        "Accelerometer" to accel?.let { "x ${it.x}  y ${it.y}  z ${it.z}" },
        "PPG" to ppg?.let { "g ${it.green}  ir ${it.ir}  r ${it.red}" },
        "Skin temperature" to skin?.let { "${it.skin} °C  amb ${it.ambient} °C" },
    )
    val state = rememberScalingLazyListState()
    AvatarWearScaffold(state) {
        ScalingLazyColumn(
            state = state,
            contentPadding = AvatarTokens.ListContentPadding,
            modifier = Modifier.fillMaxWidth(),
        ) {
            item { Header("Monitoring") }
            items(rows) { (label, value) -> SignalCard(label, value) }
            item {
                Chip(
                    label = { Text("Stop", style = AvatarTokens.ChipLabel) },
                    onClick = onStop,
                    colors = AvatarTokens.dangerChipColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun SignalCard(label: String, value: String?) {
    Chip(
        label = { Text(label, style = AvatarTokens.Label) },
        secondaryLabel = {
            Text(value ?: "waiting…",
                style = if (value != null) AvatarTokens.Body
                else AvatarTokens.Label.copy(color = AvatarTokens.Faint))
        },
        onClick = { },
        colors = AvatarTokens.chipColors(active = false),
        modifier = Modifier.fillMaxWidth(),
    )
}

// -------------------------------------------------------------------------------------
// f. Single-sensor tests (catalog) + live readings
// -------------------------------------------------------------------------------------

@Composable
fun SensorTestsScreen(options: List<SensorOption>, onStart: (id: String) -> Unit, onBack: () -> Unit) {
    val state = rememberScalingLazyListState()
    AvatarWearScaffold(state) {
        ScalingLazyColumn(
            state = state,
            contentPadding = AvatarTokens.ListContentPadding,
            modifier = Modifier.fillMaxWidth(),
        ) {
            item { Header("Sensor tests") }
            if (options.isEmpty()) {
                item { Text("No supported sensors found", style = AvatarTokens.Label,
                    textAlign = TextAlign.Center) }
            }
            items(options) { option ->
                CompactChip(
                    label = { Text(option.label, style = AvatarTokens.ChipLabel) },
                    onClick = { onStart(option.id) },
                    colors = AvatarTokens.chipColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item { BackChip(onBack) }
        }
    }
}

@Composable
fun LiveReadingsScreen(title: String, readings: Map<String, String>, stopLabel: String, onStop: () -> Unit) {
    val entries = readings.entries.sortedBy { it.key }.toList()
    val state = rememberScalingLazyListState()
    AvatarWearScaffold(state) {
        ScalingLazyColumn(
            state = state,
            contentPadding = AvatarTokens.ListContentPadding,
            modifier = Modifier.fillMaxWidth(),
        ) {
            item { Header(title) }
            items(entries) { e -> SignalCard(e.key, e.value) }
            item {
                Chip(
                    label = { Text(stopLabel, style = AvatarTokens.ChipLabel) },
                    onClick = onStop,
                    colors = AvatarTokens.dangerChipColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
