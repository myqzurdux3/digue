package com.insta.reelsoff.service

import com.insta.detection.Classification
import com.insta.detection.Surface
import com.insta.detection.Tier

enum class BlockAction {
    NONE,
    BACK,
    HOME,
}

data class BlockDecision(
    val action: BlockAction,
    val recordEpisode: Boolean,
    val tier: Tier?,
) {
    companion object {
        val IDLE = BlockDecision(BlockAction.NONE, recordEpisode = false, tier = null)
    }
}

data class BlockerConfig(
    /** Quiet window after an action, so one action is not counted many times. */
    val cooldownMillis: Long = 600,
    /** Back presses within [escalationWindowMillis] before falling back to HOME. */
    val escalateAfterBacks: Int = 3,
    val escalationWindowMillis: Long = 3_000,
    /** A misfiring detector must be annoying, not device-locking. */
    val homeRateLimitMillis: Long = 30_000,
    /** Silence longer than this closes the current episode. */
    val episodeGapMillis: Long = 2_000,
)

/**
 * Turns a stream of classifications into actions.
 *
 * A back press is not guaranteed to leave the screen — Explore can be the root
 * of the stack, and Instagram can relaunch straight onto Reels. Pressing back
 * blindly on every detection would loop forever, so escalation to HOME is the
 * way out, and the rate limit on HOME is the way out of *that*.
 */
class Blocker(
    private val clock: Clock,
    private val config: BlockerConfig = BlockerConfig(),
) {

    private var lastActionAtMillis = NEVER
    private var lastBlockedAtMillis = NEVER
    private var lastHomeAtMillis = NEVER
    private var escalationWindowStartMillis = NEVER
    private var consecutiveBacks = 0

    fun decide(classification: Classification, blockedSurfaces: Set<Surface>): BlockDecision {
        val now = clock.nowMillis()
        val surface = classification.surface

        if (surface == Surface.OTHER || surface !in blockedSurfaces) {
            consecutiveBacks = 0
            escalationWindowStartMillis = NEVER
            return BlockDecision.IDLE
        }

        if (now - lastActionAtMillis < config.cooldownMillis) return BlockDecision.IDLE

        val recordEpisode = now - lastBlockedAtMillis > config.episodeGapMillis
        lastBlockedAtMillis = now
        lastActionAtMillis = now

        if (now - escalationWindowStartMillis > config.escalationWindowMillis) {
            escalationWindowStartMillis = now
            consecutiveBacks = 0
        }
        consecutiveBacks++

        val action = when {
            // <= rather than <: escalateAfterBacks=3 must mean three BACK presses
            // (BACK, BACK, BACK) before the fourth decision escalates to HOME, per
            // the spec ("à trois échecs en trois secondes, escalade vers
            // GLOBAL_ACTION_HOME") — not two BACKs then HOME on the third.
            consecutiveBacks <= config.escalateAfterBacks -> BlockAction.BACK
            now - lastHomeAtMillis >= config.homeRateLimitMillis -> {
                lastHomeAtMillis = now
                consecutiveBacks = 0
                escalationWindowStartMillis = NEVER
                BlockAction.HOME
            }
            // Rate limited: staying quiet beats re-entering the loop we just left.
            else -> BlockAction.NONE
        }

        return BlockDecision(action, recordEpisode, classification.tier)
    }
}
