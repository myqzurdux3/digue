package com.insta.reelsoff.service

import com.insta.detection.Surface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private val BASE = LockedSettings(
    allowance = AllowanceSettings(
        enabled = true,
        quotaMillis = 300_000,
        windowStartMinutes = 20 * 60,
        windowEndMinutes = 21 * 60,
        cooldownMillis = 24 * 3_600_000,
    ),
    blockedSurfaces = setOf(Surface.REELS, Surface.EXPLORE),
)

private const val DAY_MILLIS = 24 * 3_600_000L

private fun withAllowance(block: AllowanceSettings.() -> AllowanceSettings) =
    BASE.copy(allowance = BASE.allowance.block())

class IsLooseningTest {

    @Test
    fun `an unchanged setting is not a loosening`() {
        assertFalse(isLoosening(BASE, BASE))
    }

    @Test
    fun `more quota loosens, less quota tightens`() {
        assertTrue(isLoosening(BASE, withAllowance { copy(quotaMillis = 600_000) }))
        assertFalse(isLoosening(BASE, withAllowance { copy(quotaMillis = 60_000) }))
    }

    @Test
    fun `a shorter cooldown loosens, a longer one tightens`() {
        assertTrue(isLoosening(BASE, withAllowance { copy(cooldownMillis = 3_600_000) }))
        assertFalse(isLoosening(BASE, withAllowance { copy(cooldownMillis = 48 * 3_600_000) }))
    }

    @Test
    fun `dropping the cooldown to none loosens, which is what keeps the lock shut`() {
        assertTrue(isLoosening(BASE, withAllowance { copy(cooldownMillis = 0) }))
    }

    @Test
    fun `turning the quota on loosens, turning it off tightens`() {
        val off = withAllowance { copy(enabled = false) }
        assertFalse(isLoosening(BASE, off))
        assertTrue(isLoosening(off, BASE))
    }

    @Test
    fun `a wider window loosens, a narrower one tightens`() {
        assertTrue(isLoosening(BASE, withAllowance { copy(windowEndMinutes = 22 * 60) }))
        assertTrue(isLoosening(BASE, withAllowance { copy(windowStartMinutes = 19 * 60) }))
        assertFalse(isLoosening(BASE, withAllowance { copy(windowEndMinutes = 20 * 60 + 30) }))
    }

    @Test
    fun `a window moved sideways loosens, because it opens minutes that were shut`() {
        assertTrue(
            isLoosening(BASE, withAllowance { copy(windowStartMinutes = 21 * 60, windowEndMinutes = 22 * 60) }),
        )
    }

    @Test
    fun `a window that straddles midnight is compared minute by minute`() {
        val night = withAllowance { copy(windowStartMinutes = 22 * 60, windowEndMinutes = 60) }
        val shorterNight = withAllowance { copy(windowStartMinutes = 22 * 60, windowEndMinutes = 30) }
        assertFalse(isLoosening(night, shorterNight))
        assertTrue(isLoosening(shorterNight, night))
    }

    @Test
    fun `unblocking a surface loosens, blocking one tightens`() {
        assertTrue(isLoosening(BASE, BASE.copy(blockedSurfaces = setOf(Surface.EXPLORE))))
        assertFalse(
            isLoosening(
                BASE,
                BASE.copy(blockedSurfaces = setOf(Surface.REELS, Surface.EXPLORE, Surface.SHORTS)),
            ),
        )
    }

    @Test
    fun `a change that both tightens and loosens counts wholly as a loosening`() {
        val mixed = BASE.copy(
            allowance = BASE.allowance.copy(quotaMillis = 60_000),
            blockedSurfaces = setOf(Surface.EXPLORE),
        )
        assertTrue(isLoosening(BASE, mixed))
    }
}

class ArmAndMatureTest {

    private val loosened = withAllowance { copy(quotaMillis = 600_000) }
    private val tightened = withAllowance { copy(quotaMillis = 60_000) }

    @Test
    fun `a tightening arms nothing, so the caller applies it at once`() {
        assertNull(armChange(BASE, tightened, nowEpochMillis = 1_000_000, nowElapsedRealtime = 50_000))
    }

    @Test
    fun `a loosening is armed for the current cooldown`() {
        val pending = armChange(BASE, loosened, nowEpochMillis = 1_000_000, nowElapsedRealtime = 50_000)!!
        assertEquals(loosened, pending.proposed)
        assertEquals(1_000_000L + DAY_MILLIS, pending.effectiveAtEpochMillis)
        assertEquals(50_000L, pending.armedAtElapsedRealtime)
        assertEquals(DAY_MILLIS, pending.cooldownMillis)
    }

    @Test
    fun `the cooldown in force is the current one, not the proposed one`() {
        val shorter = withAllowance { copy(cooldownMillis = 1_000) }
        val pending = armChange(BASE, shorter, nowEpochMillis = 1_000_000, nowElapsedRealtime = 50_000)!!
        assertEquals(DAY_MILLIS, pending.cooldownMillis)
    }

    @Test
    fun `a change is not mature before its wall-clock deadline`() {
        val pending = armChange(BASE, loosened, 1_000_000, 50_000)!!
        assertFalse(hasMatured(pending, 1_000_000 + 3_600_000, 50_000 + 3_600_000))
    }

    @Test
    fun `a change matures when both clocks have run out the cooldown`() {
        val pending = armChange(BASE, loosened, 1_000_000, 50_000)!!
        assertTrue(hasMatured(pending, 1_000_000 + DAY_MILLIS, 50_000 + DAY_MILLIS))
    }

    @Test
    fun `winding the wall clock forward does not mature a change on its own`() {
        val pending = armChange(BASE, loosened, 1_000_000, 50_000)!!
        // Wall clock jumped a week; the device has been awake ten minutes.
        assertFalse(hasMatured(pending, 1_000_000 + 7 * DAY_MILLIS, 50_000 + 600_000))
    }

    @Test
    fun `after a reboot the wall clock decides alone, elapsed time having restarted`() {
        val pending = armChange(BASE, loosened, 1_000_000, 5_000_000)!!
        // elapsedRealtime below the armed value can only mean the device restarted.
        assertTrue(hasMatured(pending, 1_000_000 + DAY_MILLIS, 30_000))
    }

    @Test
    fun `effective settings stay put until the change matures`() {
        val pending = armChange(BASE, loosened, 1_000_000, 50_000)!!
        assertEquals(BASE, effectiveSettings(BASE, pending, 1_000_000 + 3_600_000, 50_000 + 3_600_000))
    }

    @Test
    fun `effective settings switch over once the change matures`() {
        val pending = armChange(BASE, loosened, 1_000_000, 50_000)!!
        assertEquals(loosened, effectiveSettings(BASE, pending, 1_000_000 + DAY_MILLIS, 50_000 + DAY_MILLIS))
    }

    @Test
    fun `with nothing pending the stored settings are the effective ones`() {
        assertEquals(BASE, effectiveSettings(BASE, null, 1_000_000, 50_000))
    }
}
