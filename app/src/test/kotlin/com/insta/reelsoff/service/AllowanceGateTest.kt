package com.insta.reelsoff.service

import com.insta.detection.Surface
import org.junit.Assert.assertEquals
import org.junit.Test

class AllowanceGateTest {

    private val blocked = setOf(Surface.REELS, Surface.EXPLORE, Surface.SHORTS)
    private val locked = LockedSettings(
        allowance = AllowanceSettings(
            enabled = true,
            quotaMillis = 300_000,
            windowStartMinutes = 20 * 60,
            windowEndMinutes = 21 * 60,
        ),
        blockedSurfaces = blocked,
    )

    @Test
    fun `an open pass suspends every block`() {
        val state = openPass(locked.allowance, AllowanceState(), at(17, 20, 0), PARIS)
        assertEquals(emptySet<Surface>(), effectiveBlockedSurfaces(locked, state, at(17, 20, 0) + 30_000, PARIS))
    }

    @Test
    fun `a shut pass leaves the switches in force`() {
        assertEquals(blocked, effectiveBlockedSurfaces(locked, AllowanceState(), at(17, 20, 30), PARIS))
    }

    @Test
    fun `an expired pass leaves the switches in force without anyone closing it`() {
        val state = openPass(locked.allowance, AllowanceState(), at(17, 20, 0), PARIS)
        assertEquals(blocked, effectiveBlockedSurfaces(locked, state, at(17, 20, 0) + 300_001, PARIS))
    }

    @Test
    fun `a pass outside its window leaves the switches in force`() {
        val state = openPass(locked.allowance, AllowanceState(), at(17, 20, 55), PARIS)
        assertEquals(blocked, effectiveBlockedSurfaces(locked, state, at(17, 21, 30), PARIS))
    }

    @Test
    fun `a disabled quota leaves the switches in force`() {
        val off = locked.copy(allowance = locked.allowance.copy(enabled = false))
        val state = AllowanceState(day = dayOf(17), passOpenedAtEpochMillis = at(17, 20, 0))
        assertEquals(blocked, effectiveBlockedSurfaces(off, state, at(17, 20, 0) + 30_000, PARIS))
    }
}
