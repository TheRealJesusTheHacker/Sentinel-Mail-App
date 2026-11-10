package net.sentinelsafeguard.mailapp.ui

import kotlin.math.ceil

/**
 * Undo-send window math (1.2.0) — pure numbers, kept beside
 * MotionLogic so the suite can pin them without a device.
 *
 * The window is a grace period BEFORE anything is transmitted:
 * while it runs, "Undo" simply cancels — the message was never
 * sent, so there is nothing to recall and nothing is claimed
 * otherwise. When the window expires, the real send runs and
 * the M-08 morph confirms it, exactly as a direct send does.
 */
object UndoSendLogic {

    /** Seconds left in the window, rounded up — the countdown
     * shows the reader the whole second they still own. */
    fun remainingSeconds(totalSeconds: Int, elapsedMs: Long): Int {
        val leftMs = totalSeconds * 1000L - elapsedMs
        if (leftMs <= 0L) return 0
        return ceil(leftMs / 1000.0).toInt()
    }

    fun isExpired(totalSeconds: Int, elapsedMs: Long): Boolean =
        elapsedMs >= totalSeconds * 1000L

    /** The window's elapsed fraction, for the countdown ring/bar:
     * 0 at the tap, 1 when the send fires. */
    fun fraction(totalSeconds: Int, elapsedMs: Long): Float {
        if (totalSeconds <= 0) return 1f
        return (elapsedMs.toFloat() / (totalSeconds * 1000f)).coerceIn(0f, 1f)
    }
}
