package com.insta.reelsoff.ui

import com.insta.detection.Surface
import com.insta.reelsoff.service.AllowanceSettings
import com.insta.reelsoff.service.AllowanceState
import com.insta.reelsoff.service.LockedSettings
import com.insta.reelsoff.service.PendingChange
import com.insta.reelsoff.service.epochDayOf
import com.insta.reelsoff.service.openPass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

private val PARIS: ZoneId = ZoneId.of("Europe/Paris")

private fun at(day: Int, hour: Int, minute: Int = 0): Long =
    LocalDateTime.of(2026, 8, day, hour, minute).atZone(PARIS).toInstant().toEpochMilli()

class AllowanceUiStateTest {

    private val settings = AllowanceSettings(
        enabled = true,
        quotaMillis = 300_000,
        windowStartMinutes = 20 * 60,
        windowEndMinutes = 21 * 60,
    )
    private val blocked = setOf(Surface.REELS)

    private fun state(
        allowanceState: AllowanceState = AllowanceState(),
        pending: PendingChange? = null,
        now: Long = at(17, 20, 30),
        nowElapsedRealtime: Long = 50_000,
    ) = allowanceUiState(settings, allowanceState, pending, blocked, now, nowElapsedRealtime, PARIS)

    @Test
    fun `a disabled quota reports itself off and offers nothing`() {
        val ui = allowanceUiState(
            settings.copy(enabled = false),
            AllowanceState(),
            null,
            blocked,
            at(17, 20, 30),
            50_000,
            PARIS,
        )
        assertFalse(ui.enabled)
        assertFalse(ui.canOpen)
        assertFalse(ui.passRunning)
    }

    @Test
    fun `inside the window with a full quota, the pass can be opened`() {
        val ui = state()
        assertTrue(ui.canOpen)
        assertFalse(ui.passRunning)
        assertTrue(ui.insideWindow)
        assertEquals(300_000L, ui.remainingMillis)
    }

    @Test
    fun `outside the window nothing can be opened`() {
        val ui = state(now = at(17, 15, 0))
        assertFalse(ui.canOpen)
        assertFalse(ui.insideWindow)
    }

    @Test
    fun `a running pass reports its remaining time counting down`() {
        val opened = openPass(settings, AllowanceState(), at(17, 20, 0), PARIS)
        val ui = state(allowanceState = opened, now = at(17, 20, 0) + 60_000)
        assertTrue(ui.passRunning)
        assertFalse(ui.canOpen)
        assertEquals(240_000L, ui.remainingMillis)
    }

    @Test
    fun `an exhausted quota can no longer be opened`() {
        val spent = AllowanceState(day = epochDayOf(at(17, 12), PARIS), consumedMillis = 300_000)
        val ui = state(allowanceState = spent)
        assertFalse(ui.canOpen)
        assertEquals(0L, ui.remainingMillis)
    }

    @Test
    fun `an unmatured pending change is reported with the time it still has to wait`() {
        // Coherent by construction, the way armChange builds one: the deadline is
        // the arming instant plus the cooldown, on both clocks at once.
        val pending = PendingChange(
            proposed = LockedSettings(settings.copy(quotaMillis = 600_000), blocked),
            effectiveAtEpochMillis = at(17, 20, 30) + 3_600_000,
            armedAtElapsedRealtime = 50_000,
            cooldownMillis = 3_600_000,
        )
        val ui = state(pending = pending)
        assertEquals(3_600_000L, ui.pendingInMillis)
        // Still the stored quota, not the proposed one.
        assertEquals(300_000L, ui.quotaMillis)
    }

    /**
     * The wall clock belongs to the user, and winding it forward is the obvious
     * way to try to hurry a loosening along. `hasMatured` refuses, because elapsed
     * real time has not moved — and the panel has to say the same thing. Counting
     * on the wall clock alone, it read "actif dans 0 s" for as long as the real
     * cooldown had left to run: the lock held and the screen explained nothing.
     */
    @Test
    fun `winding the wall clock forward does not run the countdown down`() {
        val pending = PendingChange(
            proposed = LockedSettings(settings.copy(quotaMillis = 600_000), blocked),
            // Already in the past as far as the wall clock is concerned.
            effectiveAtEpochMillis = at(17, 20, 30) - 7 * 24 * 3_600_000L,
            armedAtElapsedRealtime = 50_000,
            cooldownMillis = 3_600_000,
        )

        // Barely any real time has passed since arming.
        val ui = state(pending = pending, nowElapsedRealtime = 50_000 + 1_000)

        assertEquals(3_600_000L - 1_000L, ui.pendingInMillis)
        assertEquals("the change must not be in force", 300_000L, ui.quotaMillis)
    }

    /**
     * A reboot resets elapsed real time, so there is nothing left to measure the
     * cooldown against and the wall clock decides alone — `hasMatured` says so,
     * and the countdown must not go on claiming a wait that can no longer be
     * checked.
     */
    @Test
    fun `after a reboot the countdown follows the wall clock alone`() {
        val pending = PendingChange(
            proposed = LockedSettings(settings.copy(quotaMillis = 600_000), blocked),
            effectiveAtEpochMillis = at(17, 20, 30) + 600_000,
            armedAtElapsedRealtime = 9_000_000,
            cooldownMillis = 24 * 3_600_000,
        )

        // Below the armed value: the phone restarted.
        val ui = state(pending = pending, nowElapsedRealtime = 4_000)

        assertEquals(600_000L, ui.pendingInMillis)
    }

    @Test
    fun `no pending change reports none`() {
        assertNull(state().pendingInMillis)
    }

    @Test
    fun `a matured pending change is in force and no longer reported as waiting`() {
        val pending = PendingChange(
            proposed = LockedSettings(settings.copy(quotaMillis = 600_000), blocked),
            effectiveAtEpochMillis = at(17, 20, 30) - 1_000,
            armedAtElapsedRealtime = 0,
            cooldownMillis = 0,
        )
        val ui = state(pending = pending)
        assertNull(ui.pendingInMillis)
        assertEquals(600_000L, ui.quotaMillis)
    }

    /**
     * A pass nobody ever settled must not lock the user out the next day.
     *
     * `canOpenPass` refuses while an opening stamp is still on the state, and the
     * panel used to ask it about the state exactly as stored. So a pass opened at
     * 20:55, abandoned, and never noticed by the service — which needs an event
     * from a watched app, and gets none once the user has left — left the stamp
     * behind. The following day the quota is fresh, the window is open, the panel
     * says "5 min restantes sur 5 min", and the button is dead. It heals only when
     * the user opens a watched app, which is the thing they wanted the pass for.
     */
    @Test
    fun `a pass left open on a previous day does not block opening one today`() {
        val stale = AllowanceState(
            day = epochDayOf(at(16, 20, 55), PARIS),
            consumedMillis = 60_000,
            passOpenedAtEpochMillis = at(16, 20, 55),
        )

        val ui = state(allowanceState = stale, now = at(17, 20, 30))

        assertFalse("the stale pass is not running", ui.passRunning)
        assertTrue("yesterday's stamp must not veto today's pass", ui.canOpen)
        assertEquals("today's quota is untouched", 300_000L, ui.remainingMillis)
    }

    /**
     * The same state, one day earlier in the day's own window: the pass really has
     * run out of quota, so the button stays shut — for the right reason this time.
     */
    @Test
    fun `an expired pass from today still blocks opening, on quota not on its stamp`() {
        val spent = AllowanceState(
            day = epochDayOf(at(17, 20, 0), PARIS),
            consumedMillis = 0,
            passOpenedAtEpochMillis = at(17, 20, 0),
        )

        val ui = state(allowanceState = spent, now = at(17, 20, 0) + 400_000)

        assertFalse(ui.passRunning)
        assertFalse(ui.canOpen)
        assertEquals(0L, ui.remainingMillis)
    }
}
