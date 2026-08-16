package com.insta.reelsoff.ui

import com.insta.reelsoff.data.CaptureStatus
import com.insta.reelsoff.service.CaptureSession

/**
 * What the capture button should be saying right now.
 *
 * Derived from the timestamps the service publishes rather than stored, so the
 * screen cannot claim a session is running after its deadline has passed — which
 * it would, since the service can only write a final status if Instagram happens
 * to send one more event.
 */
enum class CapturePhase {
    IDLE,

    /** Armed, waiting for the user to reach Instagram. Nothing written yet. */
    WAITING,

    RUNNING,

    DONE,

    /** Armed, gave up waiting, never captured anything. */
    MISSED,
}

fun capturePhase(
    status: CaptureStatus,
    nowEpochMillis: Long,
    durationMillis: Long = CaptureSession.DEFAULT_DURATION_MILLIS,
    armTimeoutMillis: Long = CaptureSession.ARM_TIMEOUT_MILLIS,
): CapturePhase = when {
    status.armedAtEpochMillis == 0L -> CapturePhase.IDLE
    status.startedAtEpochMillis == 0L ->
        if (nowEpochMillis - status.armedAtEpochMillis <= armTimeoutMillis) {
            CapturePhase.WAITING
        } else {
            CapturePhase.MISSED
        }
    nowEpochMillis - status.startedAtEpochMillis <= durationMillis -> CapturePhase.RUNNING
    else -> CapturePhase.DONE
}

/** Rounded up, so a running session never displays "0 s" before it ends. */
fun remainingSeconds(
    status: CaptureStatus,
    nowEpochMillis: Long,
    durationMillis: Long = CaptureSession.DEFAULT_DURATION_MILLIS,
): Int {
    val remaining = status.startedAtEpochMillis + durationMillis - nowEpochMillis
    if (remaining <= 0) return 0
    return ((remaining + 999) / 1000).toInt()
}
