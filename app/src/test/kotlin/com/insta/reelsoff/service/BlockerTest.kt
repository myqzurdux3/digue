package com.insta.reelsoff.service

import com.insta.detection.Classification
import com.insta.detection.Surface
import com.insta.detection.Tier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeClock(var now: Long = 0L) : Clock {
    override fun nowMillis(): Long = now
    fun advance(millis: Long) { now += millis }
}

class BlockerTest {

    private val clock = FakeClock()
    private val blocker = Blocker(clock)
    private val blocked = setOf(Surface.REELS, Surface.EXPLORE)

    private val reels = Classification(Surface.REELS, Tier.HIGH)
    private val explore = Classification(Surface.EXPLORE, Tier.HIGH)
    private val other = Classification.OTHER

    @Test
    fun `does nothing on a non blocked surface`() {
        val decision = blocker.decide(other, blocked)

        assertEquals(BlockAction.NONE, decision.action)
        assertFalse(decision.recordEpisode)
    }

    @Test
    fun `does nothing when the surface is blocked but disabled in settings`() {
        val decision = blocker.decide(reels, setOf(Surface.EXPLORE))

        assertEquals(BlockAction.NONE, decision.action)
        assertFalse(decision.recordEpisode)
    }

    @Test
    fun `presses back and records an episode on first detection`() {
        val decision = blocker.decide(reels, blocked)

        assertEquals(BlockAction.BACK, decision.action)
        assertTrue(decision.recordEpisode)
        assertEquals(Tier.HIGH, decision.tier)
    }

    @Test
    fun `stays quiet during the cooldown`() {
        blocker.decide(reels, blocked)
        clock.advance(300)

        val decision = blocker.decide(reels, blocked)

        assertEquals(BlockAction.NONE, decision.action)
        assertFalse(decision.recordEpisode)
    }

    @Test
    fun `presses back again once the cooldown has passed`() {
        blocker.decide(reels, blocked)
        clock.advance(700)

        assertEquals(BlockAction.BACK, blocker.decide(reels, blocked).action)
    }

    @Test
    fun `a burst of back presses counts as one episode`() {
        assertTrue(blocker.decide(reels, blocked).recordEpisode)
        clock.advance(700)
        assertFalse(blocker.decide(reels, blocked).recordEpisode)
        clock.advance(700)
        assertFalse(blocker.decide(reels, blocked).recordEpisode)
    }

    @Test
    fun `a fresh attempt after the episode gap counts again`() {
        assertTrue(blocker.decide(reels, blocked).recordEpisode)
        clock.advance(5_000)

        assertTrue(blocker.decide(reels, blocked).recordEpisode)
    }

    @Test
    fun `escalates to home after three failed back presses`() {
        assertEquals(BlockAction.BACK, blocker.decide(reels, blocked).action)
        clock.advance(700)
        assertEquals(BlockAction.BACK, blocker.decide(reels, blocked).action)
        clock.advance(700)

        assertEquals(BlockAction.HOME, blocker.decide(reels, blocked).action)
    }

    @Test
    fun `does not escalate when back presses are spread beyond the window`() {
        blocker.decide(reels, blocked)
        clock.advance(4_000)
        blocker.decide(reels, blocked)
        clock.advance(4_000)

        assertEquals(BlockAction.BACK, blocker.decide(reels, blocked).action)
    }

    @Test
    fun `landing on a non blocked surface resets the escalation counter`() {
        blocker.decide(reels, blocked)
        clock.advance(700)
        blocker.decide(reels, blocked)
        clock.advance(700)
        blocker.decide(other, blocked)
        clock.advance(700)

        assertEquals(BlockAction.BACK, blocker.decide(reels, blocked).action)
    }

    @Test
    fun `home is rate limited to once every thirty seconds`() {
        fun escalate() {
            repeat(3) {
                blocker.decide(reels, blocked)
                clock.advance(700)
            }
        }

        escalate()
        clock.advance(2_000)
        blocker.decide(other, blocked)
        clock.advance(700)

        blocker.decide(reels, blocked)
        clock.advance(700)
        blocker.decide(reels, blocked)
        clock.advance(700)
        val secondEscalation = blocker.decide(reels, blocked)

        assertEquals(BlockAction.NONE, secondEscalation.action)
    }

    @Test
    fun `home becomes available again after the rate limit expires`() {
        repeat(3) {
            blocker.decide(reels, blocked)
            clock.advance(700)
        }
        clock.advance(31_000)
        blocker.decide(other, blocked)
        clock.advance(700)

        blocker.decide(reels, blocked)
        clock.advance(700)
        blocker.decide(reels, blocked)
        clock.advance(700)

        assertEquals(BlockAction.HOME, blocker.decide(reels, blocked).action)
    }

    @Test
    fun `reports the tier that fired so the ui can flag degraded detection`() {
        val decision = blocker.decide(Classification(Surface.EXPLORE, Tier.LOW), blocked)

        assertEquals(Tier.LOW, decision.tier)
        assertEquals(BlockAction.BACK, decision.action)
    }

    @Test
    fun `explore and reels are tracked by the same escalation counter`() {
        blocker.decide(reels, blocked)
        clock.advance(700)
        blocker.decide(explore, blocked)
        clock.advance(700)

        assertEquals(BlockAction.HOME, blocker.decide(reels, blocked).action)
    }

    @Test
    fun `records an episode even when home is rate limited and nothing happens`() {
        // Episodes measure the user's reflex, decoupled from whether the blocker
        // acted. While the user sits on Reels, classifications keep arriving and
        // refresh lastBlockedAtMillis, so the 2-second episode gap never elapses.
        // Reaching recordEpisode=true with action=NONE requires a real gap in
        // blocked classifications — meaning the user left and came back. That is
        // a NEW attempt, and we count it: episodes track distinct user efforts.

        // Escalate to HOME (finishes at 1400 ms)
        assertEquals(BlockAction.BACK, blocker.decide(reels, blocked).action)
        clock.advance(700)
        assertEquals(BlockAction.BACK, blocker.decide(reels, blocked).action)
        clock.advance(700)
        assertEquals(BlockAction.HOME, blocker.decide(reels, blocked).action)

        // Advance past the 2-second episode gap (now at 3900 ms, inside 30-second home rate limit)
        clock.advance(2_500)

        // Back #1 with new episode (recordEpisode=true, escalationWindowStartMillis=3900)
        assertEquals(BlockAction.BACK, blocker.decide(reels, blocked).action)
        clock.advance(700)

        // Back #2 within the escalation window (now at 4600 ms)
        assertEquals(BlockAction.BACK, blocker.decide(reels, blocked).action)

        // Create a 2+ second gap before back #3, but stay within the 3-second escalation
        // window (3900 + 2900 = 6800; window expires at 3900 + 3000 = 6900)
        clock.advance(2_200)

        // Back #3 triggers rate-limited HOME with recordEpisode=true
        val decision = blocker.decide(reels, blocked)
        assertEquals(BlockAction.NONE, decision.action)
        assertTrue(decision.recordEpisode)
    }

    @Test
    fun `does nothing when blockedSurfaces is empty`() {
        val decision = blocker.decide(reels, emptySet())

        assertEquals(BlockAction.NONE, decision.action)
        assertFalse(decision.recordEpisode)
    }
}
