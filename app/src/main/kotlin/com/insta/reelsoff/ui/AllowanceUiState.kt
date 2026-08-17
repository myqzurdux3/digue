package com.insta.reelsoff.ui

import com.insta.detection.Surface
import com.insta.reelsoff.service.AllowanceSettings
import com.insta.reelsoff.service.AllowanceState
import com.insta.reelsoff.service.LockedSettings
import com.insta.reelsoff.service.PendingChange
import com.insta.reelsoff.service.canOpenPass
import com.insta.reelsoff.service.effectiveSettings
import com.insta.reelsoff.service.minuteOfDay
import com.insta.reelsoff.service.passIsOpen
import com.insta.reelsoff.service.remainingMillis
import com.insta.reelsoff.service.settle
import com.insta.reelsoff.service.windowContains
import java.time.ZoneId

/**
 * Everything the quota panel draws, already decided. The panel renders; it does
 * not reason about clocks, and it never sees the stored settings once a pending
 * change has matured past them.
 */
data class AllowanceUiState(
    val enabled: Boolean = false,
    val quotaMillis: Long = 0,
    val remainingMillis: Long = 0,
    val windowStartMinutes: Int = 0,
    val windowEndMinutes: Int = 0,
    val cooldownMillis: Long = 0,
    val insideWindow: Boolean = false,
    val canOpen: Boolean = false,
    val passRunning: Boolean = false,
    /** Millis until a held loosening takes effect, or null when none is held. */
    val pendingInMillis: Long? = null,
)

fun allowanceUiState(
    stored: AllowanceSettings,
    state: AllowanceState,
    pending: PendingChange?,
    blockedSurfaces: Set<Surface>,
    nowEpochMillis: Long,
    nowElapsedRealtime: Long,
    zone: ZoneId,
): AllowanceUiState {
    val storedLocked = LockedSettings(stored, blockedSurfaces)
    val effectiveLocked = effectiveSettings(
        stored = storedLocked,
        pending = pending,
        nowEpochMillis = nowEpochMillis,
        nowElapsedRealtime = nowElapsedRealtime,
    )
    val effective = effectiveLocked.allowance
    // Reported only while still held: once it is in force it is no longer news,
    // and `effective` already reflects it. A pending change is armed only when
    // isLoosening was true, so the two are never equal at arming time.
    //
    // Counted against BOTH clocks, exactly as `hasMatured` decides, so the two can
    // never disagree. Against the wall clock alone — which is what this used to do
    // — winding the phone's clock forward a week left the panel reading "actif
    // dans 0 s" for as long as the real cooldown had left to run. The lock held
    // throughout, which is the point of the second clock; it was the screen that
    // told the user nothing about why nothing was happening.
    val stillWaiting = pending
        ?.takeIf { effectiveLocked == storedLocked }
        ?.let { held ->
            val byWallClock = held.effectiveAtEpochMillis - nowEpochMillis
            // Elapsed time below the armed value means the phone rebooted. There is
            // nothing left to compare against, and `hasMatured` lets the wall clock
            // decide alone in that case, so this side has to stop objecting.
            val byElapsedRealtime = if (nowElapsedRealtime < held.armedAtElapsedRealtime) {
                0L
            } else {
                held.cooldownMillis - (nowElapsedRealtime - held.armedAtElapsedRealtime)
            }
            maxOf(byWallClock, byElapsedRealtime).coerceAtLeast(0)
        }
    // Settled first, and this matters for the button rather than for the numbers.
    //
    // canOpenPass refuses while an opening stamp is on the state, and this used to
    // ask it about the state exactly as stored. A pass that nobody ever settled —
    // opened, then abandoned before the service could see another event from a
    // watched app — therefore kept its stamp, and vetoed the button on the
    // following day, when the quota was fresh and the window open. The panel read
    // "5 min restantes sur 5 min" over a dead control, and it healed only once the
    // user opened a watched app, which is the very thing they wanted the pass for.
    //
    // Deriving instead of waiting to be told is the same rule the rest of the quota
    // follows: settle here, exactly as openPass does before writing anything.
    val settled = settle(effective, state, nowEpochMillis, zone)
    return AllowanceUiState(
        enabled = effective.enabled,
        quotaMillis = effective.quotaMillis,
        remainingMillis = remainingMillis(effective, settled, nowEpochMillis, zone),
        windowStartMinutes = effective.windowStartMinutes,
        windowEndMinutes = effective.windowEndMinutes,
        cooldownMillis = effective.cooldownMillis,
        insideWindow = windowContains(effective, minuteOfDay(nowEpochMillis, zone)),
        canOpen = canOpenPass(effective, settled, nowEpochMillis, zone),
        // settle only ever closes a pass that passIsOpen already calls shut, so
        // this answers the same as it did on the raw state.
        passRunning = passIsOpen(effective, settled, nowEpochMillis, zone),
        pendingInMillis = stillWaiting,
    )
}
