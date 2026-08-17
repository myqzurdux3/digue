package com.insta.reelsoff.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AllowancePassTest {

    private val settings = AllowanceSettings(
        enabled = true,
        quotaMillis = 300_000,
        windowStartMinutes = 20 * 60,
        windowEndMinutes = 21 * 60,
    )

    @Test
    fun `a pass opens inside the window and stamps today`() {
        val now = at(17, 20, 30)
        val opened = openPass(settings, AllowanceState(), now, PARIS)
        assertEquals(now, opened.passOpenedAtEpochMillis)
        assertEquals(dayOf(17), opened.day)
        assertEquals(0L, opened.consumedMillis)
        assertTrue(passIsOpen(settings, opened, now, PARIS))
    }

    @Test
    fun `opening carries over time already spent today`() {
        val state = AllowanceState(day = dayOf(17), consumedMillis = 120_000)
        val opened = openPass(settings, state, at(17, 20, 30), PARIS)
        assertEquals(120_000L, opened.consumedMillis)
        assertEquals(180_000L, remainingMillis(settings, opened, at(17, 20, 30), PARIS))
    }

    @Test
    fun `opening discards time spent on a previous day`() {
        val state = AllowanceState(day = dayOf(16), consumedMillis = 300_000)
        val opened = openPass(settings, state, at(17, 20, 30), PARIS)
        assertEquals(dayOf(17), opened.day)
        assertEquals(0L, opened.consumedMillis)
    }

    @Test
    fun `a pass cannot open outside the window, out of quota, or disabled`() {
        val fresh = AllowanceState()
        assertFalse(canOpenPass(settings, fresh, at(17, 19, 0), PARIS))
        assertFalse(canOpenPass(settings.copy(enabled = false), fresh, at(17, 20, 30), PARIS))
        val spent = AllowanceState(day = dayOf(17), consumedMillis = 300_000)
        assertFalse(canOpenPass(settings, spent, at(17, 20, 30), PARIS))
    }

    @Test
    fun `opening a pass that cannot open changes nothing`() {
        val fresh = AllowanceState()
        assertEquals(fresh, openPass(settings, fresh, at(17, 19, 0), PARIS))
    }

    @Test
    fun `an already open pass is not reopened, which would refund its elapsed time`() {
        val opened = openPass(settings, AllowanceState(), at(17, 20, 0), PARIS)
        val again = openPass(settings, opened, at(17, 20, 30), PARIS)
        assertEquals(opened, again)
    }

    @Test
    fun `closing banks the elapsed time and shuts the pass`() {
        val opened = openPass(settings, AllowanceState(), at(17, 20, 0), PARIS)
        val closed = closePass(opened, at(17, 20, 0) + 90_000, PARIS)
        assertEquals(90_000L, closed.consumedMillis)
        assertEquals(0L, closed.passOpenedAtEpochMillis)
        assertEquals(210_000L, remainingMillis(settings, closed, at(17, 20, 30), PARIS))
    }

    @Test
    fun `closing a shut pass changes nothing`() {
        val closed = AllowanceState(day = dayOf(17), consumedMillis = 90_000)
        assertEquals(closed, closePass(closed, at(17, 20, 30), PARIS))
    }

    @Test
    fun `closing a pass opened on a previous day starts today fresh`() {
        val stale = AllowanceState(
            day = dayOf(16),
            consumedMillis = 60_000,
            passOpenedAtEpochMillis = at(16, 23, 59),
        )
        val closed = closePass(stale, at(17, 0, 30), PARIS)
        assertEquals(dayOf(17), closed.day)
        assertEquals(0L, closed.consumedMillis)
        assertEquals(0L, closed.passOpenedAtEpochMillis)
    }

    @Test
    fun `settle shuts a pass that ran out of quota`() {
        val opened = openPass(settings, AllowanceState(), at(17, 20, 0), PARIS)
        val settled = settle(settings, opened, at(17, 20, 0) + 300_001, PARIS)
        assertEquals(0L, settled.passOpenedAtEpochMillis)
        assertEquals(0L, remainingMillis(settings, settled, at(17, 20, 30), PARIS))
    }

    // Ten minutes of wall clock elapsed on a five-minute quota, and the true
    // figure is what gets banked. remainingMillis floors at zero anyway, and a
    // tidied-up number would be a lie in the one place the user can read it.
    @Test
    fun `settle shuts a pass once the clock leaves the window`() {
        val opened = openPass(settings, AllowanceState(), at(17, 20, 55), PARIS)
        val settled = settle(settings, opened, at(17, 21, 5), PARIS)
        assertEquals(0L, settled.passOpenedAtEpochMillis)
        assertEquals(600_000L, settled.consumedMillis)
    }

    @Test
    fun `settle leaves a running pass alone`() {
        val opened = openPass(settings, AllowanceState(), at(17, 20, 0), PARIS)
        assertEquals(opened, settle(settings, opened, at(17, 20, 0) + 30_000, PARIS))
    }

    @Test
    fun `settle is idempotent`() {
        val opened = openPass(settings, AllowanceState(), at(17, 20, 0), PARIS)
        val once = settle(settings, opened, at(17, 21, 5), PARIS)
        assertEquals(once, settle(settings, once, at(17, 21, 5), PARIS))
    }
}

/**
 * The single place "a pass ended" is decided, shared by the service and the UI so
 * the two cannot disagree about whether to record one.
 */
class PassClosureTest {

    private val settings = AllowanceSettings(
        enabled = true,
        quotaMillis = 300_000,
        windowStartMinutes = 20 * 60,
        windowEndMinutes = 21 * 60,
    )

    @Test
    fun `nothing to record when no pass is running`() {
        assertNull(closureOf(settings, AllowanceState(day = dayOf(17)), at(17, 20, 30), PARIS))
    }

    @Test
    fun `nothing to record while a pass is still running`() {
        val opened = openPass(settings, AllowanceState(), at(17, 20, 0), PARIS)
        assertNull(closureOf(settings, opened, at(17, 20, 0) + 30_000, PARIS))
    }

    @Test
    fun `an expired pass yields the state to persist and its duration`() {
        val opened = openPass(settings, AllowanceState(), at(17, 20, 0), PARIS)
        val closure = closureOf(settings, opened, at(17, 20, 0) + 300_001, PARIS)!!

        assertEquals(0L, closure.state.passOpenedAtEpochMillis)
        assertEquals(300_001L, closure.durationMillis)
    }

    @Test
    fun `the duration counts only this pass, not time banked earlier today`() {
        val earlier = AllowanceState(day = dayOf(17), consumedMillis = 120_000)
        val opened = openPass(settings, earlier, at(17, 20, 0), PARIS)
        val closure = closureOf(settings, opened, at(17, 20, 0) + 180_001, PARIS)!!

        assertEquals(180_001L, closure.durationMillis)
        assertEquals(300_001L, closure.state.consumedMillis)
    }

    @Test
    fun `a pass carried over from an earlier day records nothing`() {
        // closePass discards its time: the day it belonged to has no budget left to
        // charge, and inventing a duration would put the time on the wrong day.
        val stale = AllowanceState(
            day = dayOf(16),
            consumedMillis = 60_000,
            passOpenedAtEpochMillis = at(16, 23, 59),
        )
        val closure = closureOf(settings, stale, at(17, 0, 30), PARIS)!!

        assertEquals(0L, closure.durationMillis)
        assertEquals(dayOf(17), closure.state.day)
    }

    @Test
    fun `settling twice records once`() {
        val opened = openPass(settings, AllowanceState(), at(17, 20, 0), PARIS)
        val first = closureOf(settings, opened, at(17, 21, 30), PARIS)!!

        assertNull(closureOf(settings, first.state, at(17, 21, 30), PARIS))
    }
}
