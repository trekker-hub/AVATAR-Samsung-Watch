package com.example.galaxywatch5.presentation

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/**
 * DESIGN PLAYGROUND — not shipped UI. Open this file in Android Studio, switch the editor to
 * "Split"/"Design", and click the interactive (▶) icon on the preview. Then DRAG the sliders
 * to tune the gauge live — no rebuild, no watch needed. When you like a setting, tell me the
 * numbers and I'll bake them into the real defaults in StressGaugeRing.kt.
 */
@Preview(name = "Gauge playground", showBackground = true, widthDp = 260, heightDp = 540)
@Composable
private fun StressGaugePlayground() {
    var score by remember { mutableFloatStateOf(24f) }
    var stroke by remember { mutableFloatStateOf(14f) }
    var sweep by remember { mutableFloatStateOf(270f) }
    var start by remember { mutableFloatStateOf(135f) }

    val s = score.roundToInt()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF201E29))
            .padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // The gauge, clipped to a circle so it reads like a round watch face.
        androidx.compose.foundation.layout.Box(
            Modifier
                .size(220.dp)
                .clip(CircleShape),
        ) {
            StressGaugeRing(
                stressScore = s,
                stressLabel = avatarStressLabel(s),
                hr = 60 + (s * 0.6f).roundToInt(),
                eda = (1.6f + s * 0.07f),
                isMonitoring = true,
                ringColor = avatarStressColor(s),
                ringStrokeWidth = stroke.dp,
                startAngle = start,
                sweepAngle = sweep,
            )
        }
        Spacer(Modifier.height(14.dp))
        DragSlider("Stress score", score, 0f, 100f) { score = it }
        DragSlider("Ring thickness", stroke, 6f, 26f) { stroke = it }
        DragSlider("Arc sweep", sweep, 180f, 360f) { sweep = it }
        DragSlider("Start angle", start, 90f, 180f) { start = it }
    }
}

/** Minimal drag-to-set slider — custom-drawn so it needs no material Slider dependency. */
@Composable
private fun DragSlider(
    label: String,
    value: Float,
    min: Float,
    max: Float,
    onChange: (Float) -> Unit,
) {
    val accent = Color(0xFF9B8CFF)
    Column(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        BasicText(
            "$label   ${value.roundToInt()}",
            style = TextStyle(color = Color(0xFFCFCBE6), fontSize = 12.sp),
        )
        Spacer(Modifier.height(4.dp))
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(26.dp)
                .pointerInput(min, max) {
                    fun emit(x: Float) {
                        val f = (x / size.width).coerceIn(0f, 1f)
                        onChange(min + f * (max - min))
                    }
                    detectHorizontalDragGestures(
                        onDragStart = { emit(it.x) },
                    ) { change, _ -> emit(change.position.x) }
                },
        ) {
            val cy = size.height / 2f
            drawLine(
                Color(0xFF3A3550), Offset(0f, cy), Offset(size.width, cy),
                strokeWidth = 4.dp.toPx(),
            )
            val fx = ((value - min) / (max - min)).coerceIn(0f, 1f) * size.width
            drawLine(accent, Offset(0f, cy), Offset(fx, cy), strokeWidth = 4.dp.toPx())
            drawCircle(accent, radius = 9.dp.toPx(), center = Offset(fx, cy))
        }
    }
}
