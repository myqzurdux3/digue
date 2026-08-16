package com.insta.reelsoff.ui

import com.insta.detection.Tier
import com.insta.reelsoff.data.BlockEvent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DegradedDetectionTest {

    private fun event(tier: Tier) =
        BlockEvent(epochMillis = 0, surface = "REELS", ruleTier = tier.name)

    /**
     * Silence is not evidence of breakage: zero blocks may be exactly what
     * success looks like. Only the reported tier is an honest signal.
     */
    @Test
    fun `no events is not degraded`() {
        assertFalse(isDegraded(emptyList()))
    }

    @Test
    fun `high tier events are not degraded`() {
        assertFalse(isDegraded(listOf(event(Tier.HIGH), event(Tier.HIGH))))
    }

    @Test
    fun `a single high tier event among low ones is not degraded`() {
        assertFalse(isDegraded(listOf(event(Tier.LOW), event(Tier.HIGH), event(Tier.LOW))))
    }

    @Test
    fun `only low tier events is degraded`() {
        assertTrue(isDegraded(listOf(event(Tier.LOW), event(Tier.LOW))))
    }

    @Test
    fun `only medium tier events is degraded`() {
        assertTrue(isDegraded(listOf(event(Tier.MEDIUM))))
    }

    @Test
    fun `an unparseable tier is treated as degraded`() {
        assertTrue(isDegraded(listOf(BlockEvent(epochMillis = 0, surface = "REELS", ruleTier = "UNKNOWN"))))
    }
}
