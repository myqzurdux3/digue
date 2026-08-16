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
        // Three backs then a fourth decision (HOME, or NONE if home is rate
        // limited) — see the F7 fix in Blocker.decide.
        fun escalate(): BlockAction {
            var last = BlockAction.NONE
            repeat(4) {
                last = blocker.decide(reels, blocked).action
                clock.advance(700)
            }
            return last
        }

        assertEquals(BlockAction.HOME, escalate())
        clock.advance(2_000)
        blocker.decide(other, blocked)
        clock.advance(700)

        assertEquals(BlockAction.NONE, escalate())
    }

    @Test
    fun `home becomes available again after the rate limit expires`() {
        repeat(4) {
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
        blocker.decide(reels, blocked)
        clock.advance(700)

        assertEquals(BlockAction.HOME, blocker.decide(reels, blocked).action)
    }

    @Test
    fun `only the opening back of a rate limited burst records an episode`() {
        // Episodes measure the user's reflex, decoupled from whether the blocker
        // acted: within one rate-limited burst, the opening BACK — a genuinely
        // new attempt, since it follows an episode-gap-sized silence — carries
        // recordEpisode=true, while every decision after it in the same burst
        // (BACK while still under the three-strikes threshold, then NONE once
        // HOME is rate limited) carries recordEpisode=false. It is the same
        // episode, still being handled.
        //
        // NOTE (F7 follow-up): an earlier version of this test drove a single
        // decision to both recordEpisode=true and action=NONE, by timing a
        // >2000ms episode gap as the third of three back presses inside the
        // 3000ms escalation window. That specific combination became unreachable
        // once escalation moved from two back presses to three: reaching the
        // rate-limit/HOME branch now takes three BACKs (four total decisions),
        // and three cooldown-spaced gaps (>600ms each) plus one episode-gap-sized
        // gap (>2000ms) sum to at least ~3202ms — over escalationWindowMillis
        // (3000ms) by construction, no matter which of the three gaps is the big
        // one. Do not try to reinstate it; this test asserts the property that
        // still holds instead.

        // Get HOME onto its 30-second rate limit, then reset the escalation
        // counter via a non-blocked surface, same as the setup for the
        // `home is rate limited to once every thirty seconds` test above.
        lateinit var setup: BlockDecision
        repeat(4) {
            setup = blocker.decide(reels, blocked)
            clock.advance(700)
        }
        assertEquals(BlockAction.HOME, setup.action)
        clock.advance(2_000)
        blocker.decide(other, blocked)
        clock.advance(700)

        // A fresh attempt: past the 2-second episode gap, so it opens a new
        // episode, and not yet rate limited, so it still gets a BACK.
        val opening = blocker.decide(reels, blocked)
        assertEquals(BlockAction.BACK, opening.action)
        assertTrue(opening.recordEpisode)

        // The rest of the burst, still inside the 3-second escalation window
        // and the 30-second HOME rate limit: BACK while under the
        // three-strikes threshold, then NONE once it is exceeded — none of it
        // a new episode.
        clock.advance(700)
        val second = blocker.decide(reels, blocked)
        assertEquals(BlockAction.BACK, second.action)
        assertFalse(second.recordEpisode)

        clock.advance(700)
        val third = blocker.decide(reels, blocked)
        assertEquals(BlockAction.BACK, third.action)
        assertFalse(third.recordEpisode)

        clock.advance(700)
        val fourth = blocker.decide(reels, blocked)
        assertEquals(BlockAction.NONE, fourth.action)
        assertFalse(fourth.recordEpisode)

        clock.advance(700)
        val fifth = blocker.decide(reels, blocked)
        assertEquals(BlockAction.NONE, fifth.action)
        assertFalse(fifth.recordEpisode)
    }

    @Test
    fun `does nothing when blockedSurfaces is empty`() {
        val decision = blocker.decide(reels, emptySet())

        assertEquals(BlockAction.NONE, decision.action)
        assertFalse(decision.recordEpisode)
    }
}
