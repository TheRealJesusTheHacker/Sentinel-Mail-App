package net.sentinelsafeguard.mailapp

import net.sentinelsafeguard.mailapp.ui.MotionLogic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** The motion layer's pure math (1.1.0), pinned like the
 * Command App pins its own MotionLogic: the stagger cap keeps
 * long lists instant, the shake starts and ends at rest, and
 * the drawn tick's two beats meet exactly at 60%. */
class MotionLogicTest {

    @Test
    fun staggerCapsAtTenRows() {
        assertEquals(0L, MotionLogic.staggerDelayMs(0))
        assertEquals(70L, MotionLogic.staggerDelayMs(1))
        assertEquals(630L, MotionLogic.staggerDelayMs(9))
        assertEquals(-1L, MotionLogic.staggerDelayMs(10))
        assertEquals(-1L, MotionLogic.staggerDelayMs(500))
        assertEquals(0L, MotionLogic.staggerDelayMs(-3))
    }

    @Test
    fun shakeStartsAndEndsAtRestAndStaysBounded() {
        assertEquals(0f, MotionLogic.shakeOffsetDp(0f), 0.001f)
        assertEquals(0f, MotionLogic.shakeOffsetDp(1f), 0.001f)
        var peak = 0f
        var f = 0f
        while (f <= 1f) {
            peak = maxOf(peak, abs(MotionLogic.shakeOffsetDp(f)))
            f += 0.01f
        }
        assertTrue(peak <= MotionLogic.SHAKE_PEAK_DP + 0.001f)
        assertTrue(peak > 4f) // it does actually move
        // Decaying: the last swing is smaller than the first.
        assertTrue(
            abs(MotionLogic.shakeOffsetDp(0.85f)) <
                abs(MotionLogic.shakeOffsetDp(0.15f))
        )
    }

    @Test
    fun tickBeatsMeetAtSixtyPercent() {
        assertEquals(0f, MotionLogic.tickCircleFraction(0f), 0.001f)
        assertEquals(1f, MotionLogic.tickCircleFraction(0.6f), 0.001f)
        assertEquals(1f, MotionLogic.tickCircleFraction(1f), 0.001f)
        assertEquals(0f, MotionLogic.tickMarkFraction(0.6f), 0.001f)
        assertEquals(1f, MotionLogic.tickMarkFraction(1f), 0.001f)
        assertEquals(0.5f, MotionLogic.tickCircleFraction(0.3f), 0.001f)
    }

    @Test
    fun reducedFadeIsTheFamilyConstant() {
        assertEquals(120, MotionLogic.REDUCED_FADE_MS)
        assertEquals(470, MotionLogic.TICK_DURATION_MS)
    }
}
