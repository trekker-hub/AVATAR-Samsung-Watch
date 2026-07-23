package com.example.galaxywatch5.presentation

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.tooling.preview.devices.WearDevices
import com.example.galaxywatch5.presentation.theme.AvatarTokens
import kotlinx.coroutines.delay
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * AVATAR calm-first palette, now sourced from the shared [AvatarTokens] design system so every
 * screen matches. These are thin references to the tokens — the values are identical, so the
 * gauge renders exactly as before; expose only [avatarStressColor] / [avatarStressLabel] for
 * callers that map bands.
 */
private object AvatarColors {
    val Face = AvatarTokens.Face
    val Ink = AvatarTokens.Ink
    val Accent = AvatarTokens.Accent
    val Tint = AvatarTokens.Tint
    val Green = AvatarTokens.Green
    val Orange = AvatarTokens.Orange
    val Track = AvatarTokens.Track
    val Muted = AvatarTokens.Muted
    val Faint = AvatarTokens.Faint
}

/** Ring geometry: a 270° gauge with a 90° "mouth" at the bottom (matches the mockup). */
private const val GAUGE_START_ANGLE = 135f // bottom-left, in Compose drawArc degrees (0° = 3 o'clock)
private const val GAUGE_SWEEP = 270f

/** Maps a 0–100 stress score to its band color. Callers pass the result as [StressGaugeRing]'s ringColor. */
fun avatarStressColor(score: Int): Color = when {
    score < 35 -> AvatarColors.Green
    score < 68 -> AvatarColors.Accent
    else -> AvatarColors.Orange
}

/** Maps a 0–100 stress score to its band label (matches the mockup's statusWord). */
fun avatarStressLabel(score: Int): String = when {
    score < 25 -> "CALM"
    score < 35 -> "STEADY"
    score < 55 -> "MILD"
    score < 68 -> "ELEVATED"
    else -> "HIGH"
}

/**
 * Full-screen circular stress gauge for a round Wear OS face.
 *
 * All values are parameters — nothing about the reading is hardcoded. [ringColor] is
 * decoupled from [stressScore] so the caller can map score → band color however it likes
 * (see [avatarStressColor]).
 */
@Composable
fun StressGaugeRing(
    stressScore: Int,
    stressLabel: String,
    hr: Int,
    eda: Float,
    isMonitoring: Boolean,
    modifier: Modifier = Modifier,
    ringColor: Color = avatarStressColor(stressScore),
    // Design knobs — defaults match the mockup; the playground drives these live.
    ringStrokeWidth: Dp = 14.dp,
    startAngle: Float = GAUGE_START_ANGLE,
    sweepAngle: Float = GAUGE_SWEEP,
) {
    // System time, small — refreshed every 20s so it stays roughly current.
    var timeText by remember { mutableStateOf(currentTimeText()) }
    LaunchedEffectTick { timeText = currentTimeText() }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(AvatarColors.Face),
        contentAlignment = Alignment.Center,
    ) {
        // ---- Ring: gray track + proportional progress arc, same geometry ----
        val fraction = (stressScore.coerceIn(0, 100)) / 100f
        Canvas(Modifier.fillMaxSize()) {
            val strokePx = ringStrokeWidth.toPx()
            val margin = strokePx / 2f + 3.dp.toPx()
            val topLeft = Offset(margin, margin)
            val arcSize = Size(size.width - margin * 2f, size.height - margin * 2f)
            val stroke = Stroke(width = strokePx, cap = StrokeCap.Round)

            drawArc(
                color = AvatarColors.Track,
                startAngle = startAngle,
                sweepAngle = sweepAngle,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = stroke,
            )
            drawArc(
                color = ringColor,
                startAngle = startAngle,
                sweepAngle = sweepAngle * fraction,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = stroke,
            )
        }

        // ---- Center column ----
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 20.dp),
        ) {
            BasicText(
                timeText,
                style = TextStyle(
                    color = AvatarColors.Ink, fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
                ),
            )
            Spacer(Modifier.height(4.dp))
            // "AVATAR" pill — small caps, letter-spaced, pale lavender background
            Box(
                Modifier
                    .clip(RoundedCornerShape(30.dp))
                    .background(AvatarColors.Tint)
                    .padding(horizontal = 11.dp, vertical = 3.dp),
            ) {
                BasicText(
                    "AVATAR",
                    style = TextStyle(
                        color = AvatarColors.Accent, fontSize = 9.sp,
                        fontWeight = FontWeight.Medium, letterSpacing = 2.4.sp,
                    ),
                )
            }
            Spacer(Modifier.height(4.dp))
            // Stress score — very large, bold, high contrast
            BasicText(
                stressScore.toString(),
                style = TextStyle(
                    color = AvatarColors.Ink, fontSize = 52.sp,
                    fontWeight = FontWeight.Bold, letterSpacing = (-1).sp,
                    textAlign = TextAlign.Center,
                ),
            )
            Spacer(Modifier.height(2.dp))
            // "STRESS · CALM" — small caps, letter-spaced, muted (band color on the word)
            Row {
                BasicText(
                    "STRESS · ",
                    style = TextStyle(
                        color = AvatarColors.Muted, fontSize = 10.sp,
                        fontWeight = FontWeight.Medium, letterSpacing = 1.6.sp,
                    ),
                )
                BasicText(
                    stressLabel,
                    style = TextStyle(
                        color = ringColor, fontSize = 10.sp,
                        fontWeight = FontWeight.Medium, letterSpacing = 1.6.sp,
                    ),
                )
            }
            Spacer(Modifier.height(8.dp))
            // "● Monitoring active" — small, with a colored status dot
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(if (isMonitoring) AvatarColors.Green else AvatarColors.Faint),
                )
                Spacer(Modifier.width(6.dp))
                BasicText(
                    if (isMonitoring) "Monitoring active" else "Paused",
                    style = TextStyle(
                        color = AvatarColors.Muted, fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                    ),
                )
            }

            // ---- Metric chips: HR + EDA ----
            // Kept INSIDE the centered column (not pinned to the bottom edge) so they can't
            // slide under the round bezel or jump around as the live values change.
            Spacer(Modifier.height(12.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                MetricChip("HR", "$hr bpm", AvatarColors.Green, MetricIcon.Pulse)
                Spacer(Modifier.width(10.dp))
                Box(
                    Modifier
                        .height(20.dp)
                        .width(1.dp)
                        .background(AvatarColors.Ink.copy(alpha = 0.10f)),
                )
                Spacer(Modifier.width(10.dp))
                MetricChip("EDA", formatMicroSiemens(eda), AvatarColors.Accent, MetricIcon.Droplet)
            }
        }
    }
}

/** EDA comes off the sensor as a long raw float; show a stable, single-decimal reading. */
private fun formatMicroSiemens(eda: Float): String =
    java.lang.String.format(java.util.Locale.US, "%.1f µS", eda)

private enum class MetricIcon { Pulse, Droplet }

@Composable
private fun MetricChip(label: String, value: String, iconColor: Color, icon: MetricIcon) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.size(13.dp)) {
            val p = Path()
            when (icon) {
                MetricIcon.Pulse -> {
                    // heartbeat polyline, scaled from a 24x24 viewbox
                    val u = size.width / 24f
                    p.moveTo(2f * u, 12f * u)
                    p.lineTo(6f * u, 12f * u)
                    p.lineTo(8f * u, 7f * u)
                    p.lineTo(11f * u, 16f * u)
                    p.lineTo(13.5f * u, 9f * u)
                    p.lineTo(15f * u, 12f * u)
                    p.lineTo(22f * u, 12f * u)
                }
                MetricIcon.Droplet -> {
                    val w = size.width
                    val h = size.height
                    p.moveTo(w * 0.5f, h * 0.10f)
                    p.quadraticTo(w * 0.95f, h * 0.55f, w * 0.5f, h * 0.92f)
                    p.quadraticTo(w * 0.05f, h * 0.55f, w * 0.5f, h * 0.10f)
                }
            }
            drawPath(p, color = iconColor, style = Stroke(width = 2f * (size.width / 13f)))
        }
        Spacer(Modifier.width(0.2.dp))
        Column {
            BasicText(
                label,
                style = TextStyle(
                    color = AvatarColors.Faint, fontSize = 8.sp, letterSpacing = 0.8.sp,
                ),
            )
            BasicText(
                value,
                maxLines = 1,
                softWrap = false,
                style = TextStyle(
                    color = AvatarColors.Ink, fontSize = 10.sp, fontWeight = FontWeight.SemiBold,
                ),
            )
        }
    }
}

private fun currentTimeText(): String =
    LocalTime.now().format(DateTimeFormatter.ofPattern("h:mm"))

/** Small helper: re-runs [onTick] every 20s while composed (keeps the clock fresh). */
@Composable
private fun LaunchedEffectTick(onTick: () -> Unit) {
    androidx.compose.runtime.LaunchedEffect(Unit) {
        while (true) {
            delay(20_000)
            onTick()
        }
    }
}

@Preview(device = WearDevices.LARGE_ROUND, showBackground = true)
@Composable
private fun StressGaugeRingPreview() {
    StressGaugeRing(
        stressScore = 24,
        stressLabel = "CALM",
        hr = 72,
        eda = 2.1f,
        isMonitoring = true,
    )
}

@Preview(device = WearDevices.LARGE_ROUND, showBackground = true)
@Composable
private fun StressGaugeRingElevatedPreview() {
    val score = 84
    StressGaugeRing(
        stressScore = score,
        stressLabel = avatarStressLabel(score),
        hr = 112,
        eda = 7.8f,
        isMonitoring = true,
        ringColor = avatarStressColor(score),
    )
}
