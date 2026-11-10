package net.sentinelsafeguard.mailapp.ui

import android.os.Build
import android.provider.Settings
import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import net.sentinelsafeguard.mailapp.ui.theme.SentinelGreen

/**
 * The estate motion layer for Sentinel Mail (1.1.0) — the
 * Command App's M-series patterns, ported: M-09 list entrances,
 * M-13 error shake, M-06 drawn tick, the M-08 send morph's
 * pieces, and the family haptics. The pure numbers live in
 * [MotionLogic]; this file only renders them.
 *
 * Every pattern honors the system animator setting (reduced
 * motion) through [rememberReducedMotion] — the same
 * Settings.Global check the family has always used. A pattern
 * with no reduced-motion fallback does not ship.
 */

/**
 * True when the system animator duration scale is 0 — the
 * device's reduced-motion setting. Read once per composition
 * that asks. Every motion pattern in this file consults it.
 */
@Composable
fun rememberReducedMotion(): Boolean {
    val context = LocalContext.current
    return remember {
        try {
            Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f,
            ) == 0f
        } catch (e: Exception) {
            false
        }
    }
}

/**
 * The family haptic tick: one light keyboard-tap tick on
 * navigation and picks — the surfaces the owner moves by feel.
 * Never on passive rendering. Haptics are garnish: a device
 * without a vibrator path must never break the interaction.
 */
@Composable
fun rememberHapticTick(): () -> Unit {
    val view = LocalView.current
    return remember(view) {
        {
            try {
                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            } catch (ignored: Exception) {
                // Garnish only — never break the action.
            }
        }
    }
}

/**
 * The primary-action haptic: a confirming thock for a send,
 * stronger than the light navigation tick. CONFIRM on API 30+,
 * LONG_PRESS below it, always guarded.
 */
@Composable
fun rememberPrimaryHaptic(): () -> Unit {
    val view = LocalView.current
    return remember(view) {
        {
            try {
                if (Build.VERSION.SDK_INT >= 30) {
                    view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                } else {
                    view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                }
            } catch (ignored: Exception) {
                // Garnish only — never break the action.
            }
        }
    }
}

/**
 * M-09 list entrance for the row at [index]: a short fade +
 * settle, delayed by the capped 70 ms stagger. Rows past the
 * cap and every row under reduced motion render instantly —
 * the cascade orients, it never gates reading mail.
 */
@Composable
fun Modifier.listEntrance(index: Int): Modifier {
    val reduced = rememberReducedMotion()
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        val delayMs = MotionLogic.staggerDelayMs(index)
        if (reduced || delayMs < 0L) {
            progress.snapTo(1f)
        } else {
            if (delayMs > 0L) delay(delayMs)
            progress.animateTo(1f, tween(MotionLogic.STAGGER_FADE_MS))
        }
    }
    return this.graphicsLayer {
        alpha = progress.value
        translationY = (1f - progress.value) * 12f * density
    }
}

/**
 * M-13 error shake: when [trigger] changes to a new non-zero
 * value, the content shakes once (~360 ms, decaying). The error
 * TEXT beside it is untouched — the shake carries the "no," the
 * words stay exactly as they were. Under reduced motion nothing
 * moves.
 */
@Composable
fun Modifier.errorShake(trigger: Int): Modifier {
    val reduced = rememberReducedMotion()
    val progress = remember { Animatable(0f) }
    LaunchedEffect(trigger) {
        if (trigger != 0) {
            progress.snapTo(0f)
            if (!reduced) {
                progress.animateTo(1f, tween(MotionLogic.SHAKE_DURATION_MS))
            } else {
                progress.snapTo(1f)
            }
        }
    }
    return this.graphicsLayer {
        translationX = MotionLogic.shakeOffsetDp(progress.value) * density
    }
}

/**
 * M-06 drawn success tick: the circle stroke draws first, then
 * the tick, 470 ms total. Reserved for confirmations — in this
 * app, the send morph's resolution. NO confetti accompanies it:
 * sending is trust-adjacent, and the owner's confetti ruling
 * keeps trust moments to morph + drawn tick only. Under
 * reduced motion the finished tick simply appears.
 */
@Composable
fun DrawnTick(modifier: Modifier = Modifier, color: Color = SentinelGreen) {
    val reduced = rememberReducedMotion()
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        if (reduced) {
            progress.snapTo(1f)
        } else {
            progress.animateTo(1f, tween(MotionLogic.TICK_DURATION_MS))
        }
    }
    Canvas(modifier) {
        val stroke = 2.5.dp.toPx()
        val radius = size.minDimension / 2f - stroke
        val center = Offset(size.width / 2f, size.height / 2f)
        val circleFrac = MotionLogic.tickCircleFraction(progress.value)
        if (circleFrac > 0f) {
            drawArc(
                color = color,
                startAngle = -90f,
                sweepAngle = 360f * circleFrac,
                useCenter = false,
                topLeft = Offset(center.x - radius, center.y - radius),
                size = Size(radius * 2f, radius * 2f),
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
        }
        val markFrac = MotionLogic.tickMarkFraction(progress.value)
        if (markFrac > 0f) {
            val a = Offset(size.width * 0.30f, size.height * 0.53f)
            val b = Offset(size.width * 0.45f, size.height * 0.68f)
            val c = Offset(size.width * 0.72f, size.height * 0.34f)
            val first = (markFrac / 0.5f).coerceAtMost(1f)
            val second = ((markFrac - 0.5f) / 0.5f).coerceIn(0f, 1f)
            if (first > 0f) {
                drawLine(
                    color = color,
                    start = a,
                    end = Offset(a.x + (b.x - a.x) * first, a.y + (b.y - a.y) * first),
                    strokeWidth = stroke,
                    cap = StrokeCap.Round,
                )
            }
            if (second > 0f) {
                drawLine(
                    color = color,
                    start = b,
                    end = Offset(b.x + (c.x - b.x) * second, b.y + (c.y - b.y) * second),
                    strokeWidth = stroke,
                    cap = StrokeCap.Round,
                )
            }
        }
    }
}
