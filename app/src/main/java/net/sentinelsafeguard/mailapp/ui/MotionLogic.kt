package net.sentinelsafeguard.mailapp.ui

import kotlin.math.PI
import kotlin.math.sin

/**
 * The pure numbers of this app's motion layer — the Command
 * App's estate motion constants (M-series), carried over so the
 * family feels like one family, and kept in a file with no
 * Compose imports so the unit suite can pin them without a
 * device. The composables live in Motion.kt and only render
 * what these values decide.
 *
 * Purpose rule (owner, 2026-11-10): motion serves the send /
 * receive core — transitions explain where a tap took you, the
 * send morph proves the message went, the drawn tick is the
 * confirmation. Nothing ornamental, nothing that slows or
 * obscures sending or receiving. No confetti anywhere: the
 * sending confirmation is trust-adjacent and uses the morph +
 * drawn tick only, per the owner's confetti ruling (2026-11-08).
 */
object MotionLogic {
    /** M-01: pressables sink to 95% on press. */
    const val PRESS_SINK_SCALE: Float = 0.95f

    /** M-09: rows cascade at 70 ms, capped at the first 10 rows. */
    const val STAGGER_STEP_MS: Long = 70L
    const val STAGGER_CAP: Int = 10
    const val STAGGER_FADE_MS: Int = 180

    /** M-13: the error shake runs ~360 ms, peaking at 8 dp. */
    const val SHAKE_DURATION_MS: Int = 360
    const val SHAKE_PEAK_DP: Float = 8f

    /** M-06: circle-then-tick draws in 470 ms total. */
    const val TICK_DURATION_MS: Int = 470

    /** Reduced-motion fallback: a plain fade of at most 120 ms. */
    const val REDUCED_FADE_MS: Int = 120

    /** M-08: the drawn tick rests this long before the flow moves on. */
    const val MORPH_HOLD_MS: Long = 1100L

    /** Screen transitions (AppRoot): forward slides lead the eye
     * toward the message; fades stay short so navigation never
     * feels like waiting. */
    const val SCREEN_FADE_IN_MS: Int = 220
    const val SCREEN_FADE_OUT_MS: Int = 160
    const val SCREEN_SLIDE_MS: Int = 300

    /**
     * The stagger delay for the row at [index]: rows 0..9 delay
     * 0, 70, …, 630 ms; rows at or past the cap return -1, the
     * "render instantly" sentinel, so perceived calm never
     * becomes actual delay on a long list.
     */
    fun staggerDelayMs(index: Int): Long = when {
        index < 0 -> 0L
        index < STAGGER_CAP -> index * STAGGER_STEP_MS
        else -> -1L
    }

    /**
     * M-13 shake offset in dp for [fraction] of the shake: a
     * decaying sine — three swings, each smaller, ending exactly
     * at rest. Bounded by ±[SHAKE_PEAK_DP]; 0 at both ends.
     */
    fun shakeOffsetDp(fraction: Float): Float {
        val f = fraction.coerceIn(0f, 1f)
        return (SHAKE_PEAK_DP * sin(f * PI * 3.0) * (1.0 - f)).toFloat()
    }

    /** M-06 first beat: the circle draws over the first 60%. */
    fun tickCircleFraction(progress: Float): Float =
        (progress.coerceIn(0f, 1f) / 0.6f).coerceAtMost(1f)

    /** M-06 second beat: the tick draws over the last 40%. */
    fun tickMarkFraction(progress: Float): Float =
        ((progress.coerceIn(0f, 1f) - 0.6f) / 0.4f).coerceIn(0f, 1f)
}
