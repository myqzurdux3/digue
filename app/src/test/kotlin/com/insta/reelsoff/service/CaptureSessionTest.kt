package com.insta.reelsoff.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private class StepClock(var now: Long = 0L) : Clock {
    override fun nowMillis(): Long = now
}

class CaptureSessionTest {

    @Test
    fun `is inactive until started`() {
        val session = CaptureSession(StepClock())

        assertFalse(session.isActive())
        assertFalse(session.shouldCapture())
    }

    @Test
    fun `captures immediately when started`() {
        val session = CaptureSession(StepClock()).apply { start() }

        assertTrue(session.isActive())
        assertTrue(session.shouldCapture())
    }

    @Test
    fun `captures at most once per interval`() {
        val clock = StepClock()
        val session = CaptureSession(clock).apply { start() }

        session.shouldCapture()
        clock.now = 2_999
        assertFalse(session.shouldCapture())
        clock.now = 3_000
        assertTrue(session.shouldCapture())
    }

    @Test
    fun `goes inactive after the duration`() {
        val clock = StepClock()
        val session = CaptureSession(clock).apply { start() }

        clock.now = 60_001

        assertFalse(session.isActive())
        assertFalse(session.shouldCapture())
    }

    @Test
    fun `restarting extends the window`() {
        val clock = StepClock()
        val session = CaptureSession(clock).apply { start() }

        clock.now = 60_001
        session.start()

        assertTrue(session.isActive())
        assertTrue(session.shouldCapture())
    }
}
