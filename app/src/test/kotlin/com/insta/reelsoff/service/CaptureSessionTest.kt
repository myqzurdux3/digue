package com.insta.reelsoff.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private class StepClock(var now: Long = 0L) : Clock {
    override fun nowMillis(): Long = now
}

class CaptureSessionTest {

    @Test
    fun `captures nothing until armed`() {
        val session = CaptureSession(StepClock())

        assertFalse(session.shouldCapture())
        assertEquals(0, session.capturedCount)
    }

    @Test
    fun `arming alone does not open the window`() {
        val clock = StepClock()
        val session = CaptureSession(clock).apply { arm() }

        // The window must open on the first Instagram event, not on the button
        // press: the user has to leave this app to reach Instagram, and that walk
        // used to be charged against the 60 seconds.
        assertEquals(NEVER, session.startedAtMillis)
    }

    @Test
    fun `the first event opens the window and captures`() {
        val clock = StepClock()
        val session = CaptureSession(clock).apply { arm() }

        clock.now = 20_000
        assertTrue(session.shouldCapture())
        assertEquals(20_000, session.startedAtMillis)
        assertEquals(1, session.capturedCount)
    }

    @Test
    fun `the full window is available however late the first event arrives`() {
        val clock = StepClock()
        val session = CaptureSession(clock).apply { arm() }

        clock.now = 20_000
        session.shouldCapture()

        // 60s after the first event, not 60s after the press.
        clock.now = 20_000 + 60_000
        assertTrue(session.shouldCapture())
        clock.now = 20_000 + 60_001
        assertFalse(session.shouldCapture())
    }

    @Test
    fun `captures at most once per interval`() {
        val clock = StepClock()
        val session = CaptureSession(clock).apply { arm() }

        assertTrue(session.shouldCapture())
        clock.now = 2_999
        assertFalse(session.shouldCapture())
        clock.now = 3_000
        assertTrue(session.shouldCapture())
        assertEquals(2, session.capturedCount)
    }

    @Test
    fun `counts only what it actually wrote`() {
        val clock = StepClock()
        val session = CaptureSession(clock).apply { arm() }

        repeat(5) { session.shouldCapture() }

        // Five calls inside one interval are one snapshot, so a count of 5 here
        // would tell the user five files exist when one does.
        assertEquals(1, session.capturedCount)
    }

    @Test
    fun `a stale arming expires instead of firing much later`() {
        val clock = StepClock()
        val session = CaptureSession(clock).apply { arm() }

        clock.now = 5 * 60_000 + 1

        assertFalse(session.shouldCapture())
        assertEquals(NEVER, session.startedAtMillis)
    }

    @Test
    fun `an armed session still waiting is not yet expired`() {
        val clock = StepClock()
        val session = CaptureSession(clock).apply { arm() }

        clock.now = 5 * 60_000

        assertTrue(session.shouldCapture())
    }

    @Test
    fun `re-arming resets the window and the count`() {
        val clock = StepClock()
        val session = CaptureSession(clock).apply { arm() }

        session.shouldCapture()
        clock.now = 60_001
        assertFalse(session.shouldCapture())

        session.arm()
        assertEquals(0, session.capturedCount)
        assertEquals(NEVER, session.startedAtMillis)
        assertTrue(session.shouldCapture())
        assertEquals(1, session.capturedCount)
    }
}
