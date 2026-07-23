package com.example.galaxywatch5.presentation.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.ScalingLazyListState
import androidx.wear.compose.material.ChipColors
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.PositionIndicator
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.TimeText
import androidx.wear.compose.material.TimeTextDefaults
import androidx.wear.compose.material.ToggleChipColors
import androidx.wear.compose.material.ToggleChipDefaults
import androidx.wear.compose.material.Vignette
import androidx.wear.compose.material.VignettePosition
import androidx.compose.foundation.layout.Box

/**
 * The AVATAR design system — the single source of truth for every secondary screen's palette,
 * typography, shape, spacing, and chip/scaffold styling.
 *
 * Values are lifted verbatim from the main gauge (StressGaugeRing) so the whole app shares one
 * "calm-first" visual language: warm cream surface, high-contrast ink text, lilac accent, and
 * green/orange status bands. Nothing in the secondary screens hardcodes a color, size, or radius —
 * it all comes from here.
 */
object AvatarTokens {

    // ---- Core palette (exact values from the gauge mockup) ----
    val Face = Color(0xFFFAF9F5)    // warm cream background
    val Ink = Color(0xFF141628)     // high-contrast text
    val Accent = Color(0xFF5A4FC4)  // lilac
    val Tint = Color(0xFFE9E7FB)    // pale lavender (pill / on-surface)
    val Green = Color(0xFF0F8A66)   // calm / active
    val Orange = Color(0xFFCC5220)  // high stress / error

    val Track = Ink.copy(alpha = 0.08f)  // ring track / hairline divider
    val Muted = Ink.copy(alpha = 0.55f)  // secondary text
    val Faint = Ink.copy(alpha = 0.40f)  // tertiary text / idle indicator
    val SurfaceOff = Ink.copy(alpha = 0.06f)  // muted chip surface for OFF rows

    // ---- Semantic status colors (one meaning app-wide) ----
    val StatusActive = Green    // valid / delivering / connected
    val StatusWarning = Orange  // reconnecting / degraded
    val StatusError = Orange    // failed
    val StatusIdle = Faint      // off / not run / no data

    // ---- Shape & rhythm ----
    val CornerLarge = 30.dp     // pill
    val CornerMedium = 16.dp    // chip / card
    val RingStroke = 14.dp      // gauge + streaming arc

    // Spacing scale (dp).
    val s1 = 4.dp
    val s2 = 8.dp
    val s3 = 12.dp
    val s4 = 20.dp

    val DotSize = 9.dp  // status indicator dot

    // Circular-safe content padding for every ScalingLazyColumn so the first/last items and the
    // curved edges clear the round bezel.
    val ListContentPadding = PaddingValues(top = 28.dp, bottom = 44.dp, start = 8.dp, end = 8.dp)

    // ---- Typography (sizes/weights already used by the gauge) ----
    val MetricValue = TextStyle(color = Ink, fontSize = 52.sp, fontWeight = FontWeight.Bold,
        letterSpacing = (-1).sp)
    val Title = TextStyle(color = Ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    val ChipLabel = TextStyle(color = Ink, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    val Body = TextStyle(color = Ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    val Label = TextStyle(color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Medium)
    val Caption = TextStyle(color = Faint, fontSize = 9.sp, fontWeight = FontWeight.Medium,
        letterSpacing = 0.8.sp)

    /** Chip fill: accent by default, or a muted surface for inactive rows. */
    @Composable
    fun chipColors(active: Boolean = true): ChipColors = ChipDefaults.chipColors(
        backgroundColor = if (active) Tint else SurfaceOff,
        contentColor = Ink,
        secondaryContentColor = Muted,
    )

    /** Prominent primary action (e.g. "Run probe"): accent fill, cream content. */
    @Composable
    fun primaryChipColors(): ChipColors = ChipDefaults.chipColors(
        backgroundColor = Accent,
        contentColor = Face,
        secondaryContentColor = Face.copy(alpha = 0.85f),
    )

    /** Destructive action (Stop / Delete): orange fill. */
    @Composable
    fun dangerChipColors(): ChipColors = ChipDefaults.chipColors(
        backgroundColor = Orange,
        contentColor = Face,
    )

    /** Toggle chip: accent when ON, muted surface when OFF. */
    @Composable
    fun toggleChipColors(): ToggleChipColors = ToggleChipDefaults.toggleChipColors(
        checkedStartBackgroundColor = Tint,
        checkedEndBackgroundColor = Tint,
        checkedContentColor = Ink,
        checkedSecondaryContentColor = Muted,
        checkedToggleControlColor = Accent,
        uncheckedStartBackgroundColor = SurfaceOff,
        uncheckedEndBackgroundColor = SurfaceOff,
        uncheckedContentColor = Muted,
        uncheckedSecondaryContentColor = Faint,
        uncheckedToggleControlColor = Faint,
    )
}

/**
 * Standard Wear scaffold every AVATAR screen renders inside: cream background, curved [TimeText]
 * recolored to ink (the classic default is white, invisible on cream), right-curve
 * [PositionIndicator] scroll bar, and top/bottom [Vignette] edge fade. Pass the screen's
 * [ScalingLazyListState] so the position indicator tracks the list.
 */
@Composable
fun AvatarWearScaffold(
    scrollState: ScalingLazyListState? = null,
    content: @Composable () -> Unit,
) {
    // Scaffold itself is transparent; the cream surface is painted by the Box behind it so we
    // don't depend on the theme's background color.
    MaterialTheme {
        Box(Modifier.fillMaxSize().background(AvatarTokens.Face)) {
            Scaffold(
                modifier = Modifier.fillMaxSize(),
                timeText = {
                    TimeText(timeTextStyle = TimeTextDefaults.timeTextStyle(color = AvatarTokens.Ink))
                },
                vignette = { Vignette(vignettePosition = VignettePosition.TopAndBottom) },
                positionIndicator = {
                    if (scrollState != null) PositionIndicator(scalingLazyListState = scrollState)
                },
            ) {
                content()
            }
        }
    }
}

