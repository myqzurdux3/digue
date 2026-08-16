package com.insta.reelsoff.ui

import com.insta.reelsoff.data.CaptureStatus
import com.insta.reelsoff.service.CaptureSession
import org.junit.Assert.assertEquals
import org.junit.Test

private const val T0 = 1_700_000_000_000L

class CaptureProgressTest {

    @Test
    fun `nothing armed is idle`() {
        assertEquals(CapturePhase.IDLE, capturePhase(CaptureStatus(), T0))
    }

    @Test
    fun `armed but not started is waiting for Instagram`() {
        val status = CaptureStatus(armedAtEpochMillis = T0)

        assertEquals(CapturePhase.WAITING, capturePhase(status, T0 + 5_000))
    }

    @Test
    fun `armed too long ago with nothing captured is a miss`() {
        val status = CaptureStatus(armedAtEpochMillis = T0)

        // The distinction earns its keep: WAITING says "keep going", MISSED says
        // "you never reached Instagram, press it again" — opposite instructions.
        assertEquals(
            CapturePhase.MISSED,
            capturePhase(status, T0 + CaptureSession.ARM_TIMEOUT_MILLIS + 1),
        )
    }

    @Test
    fun `a started window is running until its deadline`() {
        val status = CaptureStatus(armedAtEpochMillis = T0, startedAtEpochMillis = T0, count = 3)

        assertEquals(CapturePhase.RUNNING, capturePhase(status, T0))
        assertEquals(
            CapturePhase.RUNNING,
            capturePhase(status, T0 + CaptureSession.DEFAULT_DURATION_MILLIS),
        )
        assertEquals(
            CapturePhase.DONE,
            capturePhase(status, T0 + CaptureSession.DEFAULT_DURATION_MILLIS + 1),
        )
    }

    @Test
    fun `a window that started is never a miss however long it was armed for`() {
        val status = CaptureStatus(
            armedAtEpochMillis = T0,
            startedAtEpochMillis = T0 + CaptureSession.ARM_TIMEOUT_MILLIS,
            count = 8,
        )

        assertEquals(
            CapturePhase.DONE,
            capturePhase(status, T0 + CaptureSession.ARM_TIMEOUT_MILLIS + 90_000),
        )
    }

    @Test
    fun `remaining seconds round up so the countdown never shows zero while running`() {
        val status = CaptureStatus(armedAtEpochMillis = T0, startedAtEpochMillis = T0)

        assertEquals(60, remainingSeconds(status, T0))
        assertEquals(59, remainingSeconds(status, T0 + 1_001))
        // 1ms left is still a second on screen, not "0 s" next to "en cours".
        assertEquals(1, remainingSeconds(status, T0 + CaptureSession.DEFAULT_DURATION_MILLIS - 1))
    }

    @Test
    fun `remaining seconds never go negative`() {
        val status = CaptureStatus(armedAtEpochMillis = T0, startedAtEpochMillis = T0)

        assertEquals(0, remainingSeconds(status, T0 + CaptureSession.DEFAULT_DURATION_MILLIS + 5_000))
    }
}
