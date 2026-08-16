package com.insta.reelsoff.service

/** Injected so the blocker's timing rules can be tested without waiting. */
interface Clock {
    fun nowMillis(): Long
}

/**
 * Uses elapsed real time rather than wall clock: the blocker reasons about
 * intervals, and a clock change must not confuse it.
 */
object SystemClock : Clock {
    override fun nowMillis(): Long = android.os.SystemClock.elapsedRealtime()
}

/**
 * "Long ago" sentinel, used by every timing rule in this package.
 *
 * Deliberately not Long.MIN_VALUE: `now - Long.MIN_VALUE` overflows back to a
 * negative number, which would make every "has enough time passed" check fail
 * on the very first call — no first block, no first event, no first capture.
 */
internal const val NEVER: Long = Long.MIN_VALUE / 4
