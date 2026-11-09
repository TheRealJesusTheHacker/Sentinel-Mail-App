package net.sentinelsafeguard.mailapp.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The Sentinel black/green system (spec §8): base black #060806,
 * Sentinel green accents, fleet danger red — and no purple anywhere.
 * Dark-only in v1. Every text pairing below clears the 4.5:1 floor:
 *  - body text #E6EDE6 on #060806  (≈ 13:1)
 *  - muted text #9DB89D on #060806 (≈ 7:1)
 *  - green #00E676 on #060806      (≈ 9:1)
 *  - black text on green buttons   (≈ 9:1)
 *  - white on danger red #B03A2E   (≈ 5.9:1)
 *
 * hunt18 ("Emerald Depth" GUI elevation): two new surface tiers —
 * [SentinelSurfaceLow] for recessed wells and [SentinelSurfaceTop]
 * for the top tier (stat bands, drawer active pill, identity card) —
 * plus [SentinelAmber], fenced to warning semantics ONLY (overdue,
 * at-risk, on-hold). New pairings, all ≥ 4.5:1: amber #E8B13C on
 * #060806 ≈ 8.6:1, amber on #0D120D ≈ 8.2:1, text on #1C2B1C
 * ≈ 11.9:1, green on #1C2B1C ≈ 7.9:1. Color values only — no
 * screen structure rides on this file.
 */
val SentinelBlack = Color(0xFF060806)
val SentinelSurfaceLow = Color(0xFF090C09)
val SentinelSurface = Color(0xFF0D120D)
val SentinelSurfaceHigh = Color(0xFF162016)
val SentinelSurfaceTop = Color(0xFF1C2B1C)
val SentinelGreen = Color(0xFF00E676)
val SentinelGreenDim = Color(0xFF00B248)
val SentinelAmber = Color(0xFFE8B13C)
val SentinelText = Color(0xFFE6EDE6)
val SentinelMuted = Color(0xFF9DB89D)
val SentinelDanger = Color(0xFFB03A2E)
val SentinelDangerText = Color(0xFFFFFFFF)
val SentinelOutline = Color(0xFF2A3A2A)

private val SentinelColors = darkColorScheme(
    primary = SentinelGreen,
    onPrimary = Color(0xFF031007),
    secondary = SentinelGreenDim,
    onSecondary = Color(0xFF031007),
    background = SentinelBlack,
    onBackground = SentinelText,
    surface = SentinelSurface,
    onSurface = SentinelText,
    surfaceVariant = SentinelSurfaceHigh,
    onSurfaceVariant = SentinelMuted,
    error = SentinelDanger,
    onError = SentinelDangerText,
    outline = SentinelOutline,
)

/**
 * The hunt18 shape system (1.0.0 defined none): small 10dp for
 * chips, badges and wells; medium 16dp for cards, rows and board
 * tickets; large 20dp for sheets and dialogs. (The splash logo
 * card keeps its web-true 18dp, set at its own call site.)
 */
val SentinelShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
)

private val SentinelTypography = Typography(
    // Display numerals (hunt18): stat values and money totals —
    // 32sp ExtraBold, −1% tracking, tabular figures so columns of
    // numbers align like instruments.
    displaySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 32.sp,
        letterSpacing = (-0.32).sp,
        fontFeatureSettings = "tnum",
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 28.sp,
        letterSpacing = (-0.28).sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 24.sp,
        fontFeatureSettings = "tnum",
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 17.sp,
        letterSpacing = 0.17.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 16.sp,
        lineHeight = 24.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    // Labels keep the monospace brand voice, lifted 0.5sp with
    // +6% letter-spacing (hunt18).
    labelLarge = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Medium,
        fontSize = 13.5.sp,
        letterSpacing = 0.81.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 12.5.sp,
        letterSpacing = 0.75.sp,
    ),
)

@Composable
fun SentinelTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = SentinelColors,
        typography = SentinelTypography,
        shapes = SentinelShapes,
        content = content,
    )
}
