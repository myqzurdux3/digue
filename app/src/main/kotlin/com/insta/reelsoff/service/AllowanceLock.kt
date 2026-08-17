package com.insta.reelsoff.service

import com.insta.detection.Surface
import kotlinx.serialization.Serializable

/**
 * The settings the lock reasons about: the quota and the surface switches
 * together. Both, because a lock that guarded only the quota would be walked
 * around by switching REELS off.
 *
 * This does not replace `BlockSettings`, which stays what the service reads.
 */
@Serializable
data class LockedSettings(
    val allowance: AllowanceSettings,
    val blockedSurfaces: Set<Surface>,
)

/** A loosening waiting out its cooldown. At most one exists at a time. */
@Serializable
data class PendingChange(
    val proposed: LockedSettings,
    val effectiveAtEpochMillis: Long,
    val armedAtElapsedRealtime: Long,
    /** The cooldown in force when this was armed — see [armChange]. */
    val cooldownMillis: Long,
)

/**
 * The minutes of the day this window covers, as a set.
 *
 * A set of 1440 integers rather than circular-interval arithmetic: windows can
 * straddle midnight, and comparing two of them for containment is exactly where
 * an off-by-one silently turns a tightening into a loosening. 1440 membership
 * tests cost nothing on a settings write and are impossible to get subtly wrong.
 */
private fun windowMinutes(settings: AllowanceSettings): Set<Int> =
    (0 until 1440).filterTo(mutableSetOf()) { windowContains(settings, it) }

/**
 * Whether moving from [current] to [proposed] gives the user more room.
 *
 * A change that both tightens and loosens counts wholly as a loosening: the
 * safe reading, and it avoids having to split one edit into two writes of which
 * only half would be delayed.
 *
 * On `enabled`: the quota *grants* time and never removes any, so turning it on
 * is the loosening and turning it off is the tightening. Written the other way
 * round, the lock would be undone in a single write.
 */
fun isLoosening(current: LockedSettings, proposed: LockedSettings): Boolean {
    val c = current.allowance
    val p = proposed.allowance
    if (!c.enabled && p.enabled) return true
    if (p.quotaMillis > c.quotaMillis) return true
    if (p.cooldownMillis < c.cooldownMillis) return true
    if (!windowMinutes(c).containsAll(windowMinutes(p))) return true
    if (!proposed.blockedSurfaces.containsAll(current.blockedSurfaces)) return true
    return false
}

/**
 * Returns the change to hold, or null when it may be applied at once.
 *
 * The cooldown charged is the one **currently in force**, never the proposed
 * one. Otherwise a single write could set the cooldown to zero and take effect
 * immediately, which is the whole lock gone.
 */
fun armChange(
    current: LockedSettings,
    proposed: LockedSettings,
    nowEpochMillis: Long,
    nowElapsedRealtime: Long,
): PendingChange? {
    if (!isLoosening(current, proposed)) return null
    val cooldown = current.allowance.cooldownMillis
    return PendingChange(
        proposed = proposed,
        effectiveAtEpochMillis = nowEpochMillis + cooldown,
        armedAtElapsedRealtime = nowElapsedRealtime,
        cooldownMillis = cooldown,
    )
}

/**
 * Whether a held change may now take effect.
 *
 * Both clocks have to agree, because the wall clock is the user's to move: the
 * settings app winds it forward a week and a wall-clock-only check would ripen
 * every pending loosening on the spot. `elapsedRealtime` cannot be set, only
 * reset — by a reboot, which shows up as a value below the armed one. There is
 * nothing left to compare against after that, so the wall clock decides alone;
 * a reboot is a real event, and never maturing would be worse.
 */
fun hasMatured(
    pending: PendingChange,
    nowEpochMillis: Long,
    nowElapsedRealtime: Long,
): Boolean {
    if (nowEpochMillis < pending.effectiveAtEpochMillis) return false
    if (nowElapsedRealtime < pending.armedAtElapsedRealtime) return true
    return nowElapsedRealtime - pending.armedAtElapsedRealtime >= pending.cooldownMillis
}

/**
 * The settings a caller should write back, or null when there is nothing to do.
 *
 * Readers never need this — [effectiveSettings] derives the in-force values on
 * every read, which is what makes a matured change apply even if the process
 * died before writing it back. This exists because the **lock compares a
 * proposal against the store**: left to drift, the store would hold the
 * pre-loosening values, a new proposal would be measured against the wrong
 * baseline, and the delay the user has already served would be re-armed —
 * rolling back a loosening they had earned.
 */
fun maturedProposal(
    pending: PendingChange?,
    nowEpochMillis: Long,
    nowElapsedRealtime: Long,
): LockedSettings? =
    pending
        ?.takeIf { hasMatured(it, nowEpochMillis, nowElapsedRealtime) }
        ?.proposed

/**
 * The settings actually in force. Readers derive rather than wait to be told,
 * so a matured change applies even if nothing has written it back yet.
 */
fun effectiveSettings(
    stored: LockedSettings,
    pending: PendingChange?,
    nowEpochMillis: Long,
    nowElapsedRealtime: Long,
): LockedSettings =
    if (pending != null && hasMatured(pending, nowEpochMillis, nowElapsedRealtime)) {
        pending.proposed
    } else {
        stored
    }

/**
 * What the blocker should treat as blocked right now.
 *
 * An open pass answers with the empty set, which is `Blocker`'s already-tested
 * "not a blocked surface" path — the quota never enters the blocker itself.
 */
fun effectiveBlockedSurfaces(
    locked: LockedSettings,
    state: AllowanceState,
    nowEpochMillis: Long,
    zone: java.time.ZoneId,
): Set<Surface> =
    if (passIsOpen(locked.allowance, state, nowEpochMillis, zone)) {
        emptySet()
    } else {
        locked.blockedSurfaces
    }
