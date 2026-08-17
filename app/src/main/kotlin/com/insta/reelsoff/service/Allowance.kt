package com.insta.reelsoff.service

import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.ZoneId

/**
 * The daily budget of short-video time, and when it may be spent.
 *
 * [enabled] reads the opposite way to intuition, and the whole lock depends on
 * getting it right: the quota *grants* time, it never removes any — blocking a
 * surface is governed by the switches, not by this. So `enabled = false` is the
 * strictest state (no pass can ever open), and a fresh install lands there, the
 * same way every new surface arrives switched off.
 *
 * The window is minutes since local midnight, not a timestamp. End strictly
 * below start straddles midnight (`22:00 -> 01:00`). End equal to start is an
 * empty window, never openable — between the two readings, the one that blocks.
 *
 * [cooldownMillis] starts at zero, and that is what makes the lock usable:
 * switching the quota on is itself a loosening, so a nonzero default would make
 * a fresh install wait a day before the feature it just enabled did anything.
 * At zero the settings are free to arrange; choosing a delay is a tightening and
 * lands at once; from that moment every loosening waits. The lock is armed by
 * the user, deliberately, in one gesture.
 */
@Serializable
data class AllowanceSettings(
    val enabled: Boolean = false,
    val quotaMillis: Long = 5 * 60_000,
    val windowStartMinutes: Int = 20 * 60,
    val windowEndMinutes: Int = 21 * 60,
    val cooldownMillis: Long = 0,
)

/**
 * How much of today's budget is already spent, and whether a pass is running.
 *
 * [day] is the local epoch day the [consumedMillis] belongs to. A state whose
 * day is not today reads as a fresh quota — that is the whole of the daily
 * reset, and it needs no scheduled job to happen on time.
 *
 * [passOpenedAtEpochMillis] is 0 when shut. Wall clock, not elapsed real time:
 * the countdown has to survive the process dying, and it has to be comparable
 * with the local hour the window is expressed in.
 */
@Serializable
data class AllowanceState(
    val day: Long = 0,
    val consumedMillis: Long = 0,
    val passOpenedAtEpochMillis: Long = 0,
)

fun epochDayOf(nowEpochMillis: Long, zone: ZoneId): Long =
    Instant.ofEpochMilli(nowEpochMillis).atZone(zone).toLocalDate().toEpochDay()

fun minuteOfDay(nowEpochMillis: Long, zone: ZoneId): Int =
    Instant.ofEpochMilli(nowEpochMillis).atZone(zone).let { it.hour * 60 + it.minute }

fun windowContains(settings: AllowanceSettings, minute: Int): Boolean {
    val start = settings.windowStartMinutes
    val end = settings.windowEndMinutes
    return when {
        start == end -> false
        start < end -> minute >= start && minute < end
        else -> minute >= start || minute < end
    }
}

/**
 * Today's spent time, including the pass currently running.
 *
 * Deliberately not capped at the quota: a pass whose process died can run past
 * it, and reporting the true figure beats reporting a tidy one. Every consumer
 * goes through [remainingMillis], which floors at zero.
 */
fun consumedMillisAt(
    settings: AllowanceSettings,
    state: AllowanceState,
    nowEpochMillis: Long,
    zone: ZoneId,
): Long {
    if (state.day != epochDayOf(nowEpochMillis, zone)) return 0
    val running = if (state.passOpenedAtEpochMillis == 0L) {
        0L
    } else {
        // Floored at zero: a wall clock moved backwards must not refund time.
        (nowEpochMillis - state.passOpenedAtEpochMillis).coerceAtLeast(0)
    }
    return state.consumedMillis + running
}

fun remainingMillis(
    settings: AllowanceSettings,
    state: AllowanceState,
    nowEpochMillis: Long,
    zone: ZoneId,
): Long = (settings.quotaMillis - consumedMillisAt(settings, state, nowEpochMillis, zone))
    .coerceAtLeast(0)

/**
 * Whether blocking is currently suspended.
 *
 * Every condition must hold, and the day check is what closes a pass across
 * midnight without anyone having to notice midnight passing.
 */
fun passIsOpen(
    settings: AllowanceSettings,
    state: AllowanceState,
    nowEpochMillis: Long,
    zone: ZoneId,
): Boolean =
    settings.enabled &&
        state.passOpenedAtEpochMillis != 0L &&
        state.day == epochDayOf(nowEpochMillis, zone) &&
        windowContains(settings, minuteOfDay(nowEpochMillis, zone)) &&
        remainingMillis(settings, state, nowEpochMillis, zone) > 0

/**
 * Whether the user may spend from today's budget right now.
 *
 * An already-open pass answers false: reopening one would move
 * [AllowanceState.passOpenedAtEpochMillis] forward and hand back every minute
 * it had already run.
 */
fun canOpenPass(
    settings: AllowanceSettings,
    state: AllowanceState,
    nowEpochMillis: Long,
    zone: ZoneId,
): Boolean =
    settings.enabled &&
        state.passOpenedAtEpochMillis == 0L &&
        windowContains(settings, minuteOfDay(nowEpochMillis, zone)) &&
        remainingMillis(settings, state, nowEpochMillis, zone) > 0

/** No-op when [canOpenPass] is false, so a caller never has to check twice. */
fun openPass(
    settings: AllowanceSettings,
    state: AllowanceState,
    nowEpochMillis: Long,
    zone: ZoneId,
): AllowanceState {
    if (!canOpenPass(settings, state, nowEpochMillis, zone)) return state
    val today = epochDayOf(nowEpochMillis, zone)
    return AllowanceState(
        day = today,
        // Time banked on an earlier day is not today's problem.
        consumedMillis = if (state.day == today) state.consumedMillis else 0,
        passOpenedAtEpochMillis = nowEpochMillis,
    )
}

/**
 * Banks the running pass's elapsed time and shuts it. Idempotent: a shut pass
 * comes back unchanged, so whichever of the UI and the service notices first
 * can close it without coordinating with the other.
 */
fun closePass(state: AllowanceState, nowEpochMillis: Long, zone: ZoneId): AllowanceState {
    if (state.passOpenedAtEpochMillis == 0L) return state
    val today = epochDayOf(nowEpochMillis, zone)
    // A pass opened on an earlier day: its time belongs to that day, which no
    // longer has a budget to charge. Today simply starts clean.
    if (state.day != today) return AllowanceState(day = today)
    val elapsed = (nowEpochMillis - state.passOpenedAtEpochMillis).coerceAtLeast(0)
    return state.copy(
        consumedMillis = state.consumedMillis + elapsed,
        passOpenedAtEpochMillis = 0,
    )
}

/**
 * A pass that settling has just closed: the state to persist, and how long it
 * ran. Deliberately plain numbers rather than a Room entity — this file carries
 * no `android.*` import, and the caller builds the row.
 */
data class PassClosure(
    val state: AllowanceState,
    val durationMillis: Long,
)

/**
 * Whether settling would close a pass, and what that is worth recording.
 *
 * Null means nothing to do, which is the overwhelmingly common answer — no pass
 * running, or one still running. Both the service and the UI go through here, so
 * "a pass ended" is decided once rather than in two places that could disagree.
 *
 * A pass carried over from an earlier day yields null: [closePass] discards its
 * time, because the day it belonged to no longer has a budget to charge, and
 * inventing a duration for it would put time on the wrong day.
 */
fun closureOf(
    settings: AllowanceSettings,
    state: AllowanceState,
    nowEpochMillis: Long,
    zone: ZoneId,
): PassClosure? = closureFrom(state, settle(settings, state, nowEpochMillis, zone))

/**
 * The same, for a pass the user shuts on purpose.
 *
 * Separate from [closureOf] because a pass closed by the "Fermer maintenant"
 * button is still legitimately open — settling it would answer "nothing to do".
 * Both funnel through [closureFrom], so the duration is computed in one place.
 */
fun forcedClosureOf(
    state: AllowanceState,
    nowEpochMillis: Long,
    zone: ZoneId,
): PassClosure? = closureFrom(state, closePass(state, nowEpochMillis, zone))

private fun closureFrom(before: AllowanceState, after: AllowanceState): PassClosure? {
    if (after == before) return null
    val banked = after.consumedMillis - before.consumedMillis
    return PassClosure(after, banked.coerceAtLeast(0))
}

/**
 * Brings a stored state up to date with the clock: shuts a pass that expired,
 * left the window, or belongs to a past day. Every reader calls this before
 * using a state, so the three ways a pass ends live in one place instead of
 * being re-derived by each caller.
 */
fun settle(
    settings: AllowanceSettings,
    state: AllowanceState,
    nowEpochMillis: Long,
    zone: ZoneId,
): AllowanceState =
    if (state.passOpenedAtEpochMillis != 0L && !passIsOpen(settings, state, nowEpochMillis, zone)) {
        closePass(state, nowEpochMillis, zone)
    } else {
        state
    }
