package com.insta.reelsoff.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private class TickClock(var now: Long = 0L) : Clock {
    override fun nowMillis(): Long = now
}

class EventThrottleTest {

    @Test
    fun `lets the first event through`() {
        assertTrue(EventThrottle(TickClock()).shouldProcess())
    }

    @Test
    fun `drops events inside the interval`() {
        val clock = TickClock()
        val throttle = EventThrottle(clock)

        throttle.shouldProcess()
        clock.now = 199

        assertFalse(throttle.shouldProcess())
    }

    @Test
    fun `lets an event through once the interval has elapsed`() {
        val clock = TickClock()
        val throttle = EventThrottle(clock)

        throttle.shouldProcess()
        clock.now = 200

        assertTrue(throttle.shouldProcess())
    }

    @Test
    fun `a dropped event does not restart the interval`() {
        val clock = TickClock()
        val throttle = EventThrottle(clock)

        throttle.shouldProcess()
        clock.now = 150
        throttle.shouldProcess()
        clock.now = 210

        assertTrue(throttle.shouldProcess())
    }
}
