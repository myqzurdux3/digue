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
    ) = allowanceUiState(settings, allowanceState, pending, blocked, now, 50_000, PARIS)

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
        val pending = PendingChange(
            proposed = LockedSettings(settings.copy(quotaMillis = 600_000), blocked),
            effectiveAtEpochMillis = at(17, 20, 30) + 3_600_000,
            armedAtElapsedRealtime = 50_000,
            cooldownMillis = 24 * 3_600_000,
        )
        val ui = state(pending = pending)
        assertEquals(3_600_000L, ui.pendingInMillis)
        // Still the stored quota, not the proposed one.
        assertEquals(300_000L, ui.quotaMillis)
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
}
