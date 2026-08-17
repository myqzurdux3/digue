package com.insta.reelsoff.service

import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

internal val PARIS: ZoneId = ZoneId.of("Europe/Paris")

/** Local wall-clock time in [PARIS], as epoch millis. */
internal fun at(day: Int, hour: Int, minute: Int = 0): Long =
    LocalDateTime.of(2026, 8, day, hour, minute).atZone(PARIS).toInstant().toEpochMilli()

internal fun dayOf(day: Int): Long = epochDayOf(at(day, 12), PARIS)

class AllowanceWindowTest {

    private val evening = AllowanceSettings(
        enabled = true,
        windowStartMinutes = 20 * 60,
        windowEndMinutes = 21 * 60,
    )

    @Test
    fun `the window includes its start minute and excludes its end minute`() {
        assertTrue(windowContains(evening, 20 * 60))
        assertTrue(windowContains(evening, 20 * 60 + 59))
        assertFalse(windowContains(evening, 21 * 60))
        assertFalse(windowContains(evening, 19 * 60 + 59))
    }

    @Test
    fun `a window whose end precedes its start straddles midnight`() {
        val night = evening.copy(windowStartMinutes = 22 * 60, windowEndMinutes = 60)
        assertTrue(windowContains(night, 23 * 60))
        assertTrue(windowContains(night, 0))
        assertTrue(windowContains(night, 59))
        assertFalse(windowContains(night, 60))
        assertFalse(windowContains(night, 12 * 60))
    }

    @Test
    fun `a window whose end equals its start is empty, not a whole day`() {
        val empty = evening.copy(windowStartMinutes = 9 * 60, windowEndMinutes = 9 * 60)
        assertFalse(windowContains(empty, 9 * 60))
        assertFalse(windowContains(empty, 0))
        assertFalse(windowContains(empty, 12 * 60))
    }

    @Test
    fun `the minute of day is read in the given zone`() {
        assertEquals(20 * 60 + 30, minuteOfDay(at(17, 20, 30), PARIS))
    }
}

class AllowanceQuotaTest {

    private val settings = AllowanceSettings(enabled = true, quotaMillis = 300_000)

    @Test
    fun `a closed pass consumes nothing beyond what was already banked`() {
        val state = AllowanceState(day = dayOf(17), consumedMillis = 60_000)
        assertEquals(60_000L, consumedMillisAt(state, at(17, 20, 30), PARIS))
        assertEquals(240_000L, remainingMillis(settings, state, at(17, 20, 30), PARIS))
    }

    @Test
    fun `an open pass consumes wall-clock time as it runs`() {
        val opened = at(17, 20, 30)
        val state = AllowanceState(day = dayOf(17), consumedMillis = 60_000, passOpenedAtEpochMillis = opened)
        assertEquals(120_000L, consumedMillisAt(state, opened + 60_000, PARIS))
        assertEquals(180_000L, remainingMillis(settings, state, opened + 60_000, PARIS))
    }

    @Test
    fun `a state from an earlier day reads as a fresh quota`() {
        val state = AllowanceState(day = dayOf(16), consumedMillis = 300_000)
        assertEquals(0L, consumedMillisAt(state, at(17, 20, 30), PARIS))
        assertEquals(300_000L, remainingMillis(settings, state, at(17, 20, 30), PARIS))
    }

    @Test
    fun `remaining time never goes negative`() {
        val state = AllowanceState(day = dayOf(17), consumedMillis = 400_000)
        assertEquals(0L, remainingMillis(settings, state, at(17, 20, 30), PARIS))
    }
}

class PassIsOpenTest {

    // A two-hour quota, so that every test below fails for the one reason it
    // names. With a five-minute quota the "left the window" case would also be
    // an exhausted case, and the test would pass without proving anything about
    // the window. Exhaustion gets its own settings, below.
    private val settings = AllowanceSettings(
        enabled = true,
        quotaMillis = 2 * 3_600_000,
        windowStartMinutes = 20 * 60,
        windowEndMinutes = 21 * 60,
    )
    private val open = AllowanceState(day = dayOf(17), passOpenedAtEpochMillis = at(17, 20, 0))

    @Test
    fun `a pass opened inside the window with quota left is open`() {
        assertTrue(passIsOpen(settings, open, at(17, 20, 30), PARIS))
    }

    @Test
    fun `a pass that was never opened is shut`() {
        assertFalse(passIsOpen(settings, AllowanceState(day = dayOf(17)), at(17, 20, 30), PARIS))
    }

    @Test
    fun `a pass is shut once the clock leaves the window`() {
        assertFalse(passIsOpen(settings, open, at(17, 21, 1), PARIS))
    }

    @Test
    fun `a pass is shut once the quota runs out`() {
        val short = settings.copy(quotaMillis = 300_000)
        assertTrue(passIsOpen(short, open, at(17, 20, 0) + 299_000, PARIS))
        assertFalse(passIsOpen(short, open, at(17, 20, 0) + 300_001, PARIS))
    }

    @Test
    fun `a pass opened on a previous day is shut, whatever the hour`() {
        val stale = AllowanceState(day = dayOf(16), passOpenedAtEpochMillis = at(16, 20, 0))
        assertFalse(passIsOpen(settings, stale, at(17, 20, 30), PARIS))
    }

    @Test
    fun `a disabled quota never opens, which is the strictest state`() {
        assertFalse(passIsOpen(settings.copy(enabled = false), open, at(17, 20, 30), PARIS))
    }
}
